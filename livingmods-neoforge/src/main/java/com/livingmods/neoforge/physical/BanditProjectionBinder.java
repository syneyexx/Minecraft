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
 * Bounded hostile bandit projection tied to canonical camp IDs.
 * Despawn is not death; actual kills feed ENTITY_KILLED → camp strength.
 */
public final class BanditProjectionBinder {
    private static final BanditProjectionBinder INSTANCE = new BanditProjectionBinder();

    private final LivingModsConfig config = LivingModsConfig.defaults();
    private final Map<UUID, List<UUID>> projected = new ConcurrentHashMap<>();
    private final Set<String> pending = ConcurrentHashMap.newKeySet();
    private int tick;

    private BanditProjectionBinder() {}

    public static BanditProjectionBinder get() { return INSTANCE; }

    public void clear() {
        projected.clear();
        pending.clear();
        tick = 0;
    }

    public void onServerTick(MinecraftServer server) {
        if (++tick % 50 != 0) return;
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
            byte[] payload = new RequestPayloads.NearbyQuery(
                    pos.getX(), pos.getZ(), 144, config.banditProjectionCap()).encode();
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
            String packed = data.getOrDefault("banditsPacked", "");
            if (packed.isBlank()) return;
            int budget = config.banditProjectionCap();
            int used = projected.values().stream().mapToInt(List::size).sum();
            for (String row : packed.split(";")) {
                if (row.isBlank() || used >= budget) break;
                String[] p = row.split("\\|");
                if (p.length < 4) continue;
                UUID campId = UUID.fromString(p[0]);
                int x = Integer.parseInt(p[1]);
                int z = Integer.parseInt(p[2]);
                int strength = Integer.parseInt(p[3]);
                String culture = p.length > 4 ? p[4] : "avalon";
                used += ensureCamp(level, campId, x, z, strength, culture, budget - used);
            }
        } catch (Exception e) {
            LivingModsMod.LOG.debug("Bandit projection apply failed: {}", e.toString());
        }
    }

    private int ensureCamp(ServerLevel level, UUID campId, int x, int z, int strength, String culture, int remaining) {
        List<UUID> existing = projected.computeIfAbsent(campId, id -> new ArrayList<>());
        for (ProjectedHumanoidEntity e : level.getEntitiesOfClass(ProjectedHumanoidEntity.class,
                new net.minecraft.world.phys.AABB(x - 40, 0, z - 40, x + 40, 256, z + 40))) {
            if (e.kind() == ProjectedHumanoidEntity.Kind.BANDIT
                    && campId.equals(e.canonicalIdOrNull())
                    && !existing.contains(e.getUUID())) {
                existing.add(e.getUUID());
            }
        }
        existing.removeIf(id -> level.getEntity(id) == null);
        int desired = Math.max(2, Math.min(6, strength / 20 + 2));
        desired = Math.min(desired, remaining);
        int spawned = 0;
        while (existing.size() < desired) {
            int ox = (existing.size() % 3) * 2 - 2;
            int oz = (existing.size() / 3) * 2 - 2;
            int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x + ox, z + oz);
            ProjectedHumanoidEntity bandit = LivingModsEntities.PROJECTED_HUMANOID.get().create(level);
            if (bandit == null) break;
            bandit.moveTo(x + ox + 0.5, y, z + oz + 0.5, 0, 0);
            bandit.bind(ProjectedHumanoidEntity.Kind.BANDIT, campId, null, 1L,
                    "Bandit", "RAIDER", culture, true);
            bandit.setNavigationTarget(x, z);
            level.addFreshEntity(bandit);
            existing.add(bandit.getUUID());
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
                    if (p.distanceToSqr(entity) < 176 * 176) {
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
