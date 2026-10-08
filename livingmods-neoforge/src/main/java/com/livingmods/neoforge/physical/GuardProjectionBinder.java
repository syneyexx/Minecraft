package com.livingmods.neoforge.physical;

import com.livingmods.common.config.LivingModsConfig;
import com.livingmods.neoforge.LivingModsMod;
import com.livingmods.neoforge.entity.LivingModsEntities;
import com.livingmods.neoforge.entity.ProjectedHumanoidEntity;
import com.livingmods.neoforge.sidecar.SidecarClient;
import com.livingmods.neoforge.sidecar.WorldSessionLifecycle;
import com.livingmods.protocol.MessageType;
import com.livingmods.protocol.PayloadIo;
import com.livingmods.protocol.RequestPayloads;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Settlement guard projection from canonical security / profession context.
 */
public final class GuardProjectionBinder {
    private static final GuardProjectionBinder INSTANCE = new GuardProjectionBinder();

    private final LivingModsConfig config = LivingModsConfig.defaults();
    private final Map<UUID, List<UUID>> projected = new ConcurrentHashMap<>();
    private final Set<String> pending = ConcurrentHashMap.newKeySet();
    private int tick;

    private GuardProjectionBinder() {}

    public static GuardProjectionBinder get() { return INSTANCE; }

    public void clear() {
        projected.clear();
        pending.clear();
        tick = 0;
    }

    public void onServerTick(MinecraftServer server) {
        if (++tick % 60 != 0) return;
        SidecarClient client = WorldSessionLifecycle.activeClient();
        if (client == null || !client.isReady()) return;
        ServerLevel level = server.overworld();
        for (ServerPlayer player : level.players()) {
            requestNear(level, client, player.blockPosition());
        }
        despawnFar(level);
    }

    private void requestNear(ServerLevel level, SidecarClient client, BlockPos pos) {
        String key = (pos.getX() >> 7) + ":" + (pos.getZ() >> 7);
        if (!pending.add(key)) return;
        try {
            byte[] payload = new RequestPayloads.NearbyQuery(pos.getX(), pos.getZ(), 128, 24).encode();
            client.sendAsync(MessageType.GET_MAP_OVERLAY, payload)
                    .thenAccept(env -> level.getServer().execute(() -> {
                        try {
                            apply(level, env);
                        } finally {
                            pending.remove(key);
                        }
                    }))
                    .exceptionally(ex -> {
                        pending.remove(key);
                        return null;
                    });
        } catch (Exception e) {
            pending.remove(key);
        }
    }

    private void apply(ServerLevel level, com.livingmods.protocol.Envelope envelope) {
        if (envelope == null || envelope.isError()) return;
        try {
            Map<String, String> data = PayloadIo.decodeStrings(envelope.payload());
            String packed = data.getOrDefault("guardsPacked", "");
            if (packed.isBlank()) return;
            int used = projected.values().stream().mapToInt(List::size).sum();
            int budget = Math.max(8, config.armyProjectionCap() / 2);
            for (String row : packed.split(";")) {
                if (row.isBlank() || used >= budget) break;
                String[] p = row.split("\\|");
                if (p.length < 5) continue;
                UUID settlementId = UUID.fromString(p[0]);
                UUID faction = UUID.fromString(p[1]);
                int x = Integer.parseInt(p[2]);
                int z = Integer.parseInt(p[3]);
                String duty = p[4];
                String culture = p.length > 5 ? p[5] : "avalon";
                used += ensureGuards(level, settlementId, faction, x, z, duty, culture, budget - used);
            }
        } catch (Exception e) {
            LivingModsMod.LOG.debug("Guard projection apply failed: {}", e.toString());
        }
    }

    private int ensureGuards(
            ServerLevel level, UUID settlementId, UUID faction, int x, int z,
            String duty, String culture, int remaining
    ) {
        List<UUID> existing = projected.computeIfAbsent(settlementId, id -> new ArrayList<>());
        existing.removeIf(id -> level.getEntity(id) == null);
        int desired = Math.min(4, Math.max(1, remaining));
        // Duty offsets: gate / civic / patrol / market.
        int[][] offsets = switch (duty == null ? "patrol" : duty.toLowerCase()) {
            case "gate" -> new int[][]{{0, -18}, {0, 18}};
            case "civic", "center" -> new int[][]{{2, 2}, {-2, -2}};
            case "market" -> new int[][]{{8, 0}, {-8, 0}};
            default -> new int[][]{{6, 6}, {-6, 6}, {6, -6}};
        };
        int spawned = 0;
        while (existing.size() < desired && spawned < offsets.length) {
            int[] o = offsets[existing.size() % offsets.length];
            int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x + o[0], z + o[1]);
            ProjectedHumanoidEntity guard = LivingModsEntities.PROJECTED_HUMANOID.get().create(level);
            if (guard == null) break;
            guard.moveTo(x + o[0] + 0.5, y, z + o[1] + 0.5, 0, 0);
            boolean hostile = false;
            UUID kingdom = faction == null ? settlementId : faction;
            for (ServerPlayer nearby : level.players()) {
                if (com.livingmods.neoforge.gameplay.FactionDispositionCache.get()
                        .guardsHostileToPlayer(nearby.getUUID(), settlementId, kingdom)) {
                    hostile = true;
                    break;
                }
            }
            guard.bind(ProjectedHumanoidEntity.Kind.GUARD, settlementId, faction, 1L,
                    "Guard", duty == null ? "PATROL" : duty.toUpperCase(), culture, hostile);
            guard.setNavigationTarget(x + o[0], z + o[1]);
            level.addFreshEntity(guard);
            existing.add(guard.getUUID());
            spawned++;
        }
        return spawned;
    }

    private void despawnFar(ServerLevel level) {
        for (var it = projected.entrySet().iterator(); it.hasNext(); ) {
            var e = it.next();
            boolean near = false;
            for (UUID eid : e.getValue()) {
                var entity = level.getEntity(eid);
                if (entity == null) continue;
                for (ServerPlayer p : level.players()) {
                    if (p.distanceToSqr(entity) < 160 * 160) {
                        near = true;
                        break;
                    }
                }
                if (near) break;
            }
            if (!near) {
                for (UUID eid : e.getValue()) {
                    var entity = level.getEntity(eid);
                    if (entity != null) entity.discard();
                }
                it.remove();
            }
        }
    }
}
