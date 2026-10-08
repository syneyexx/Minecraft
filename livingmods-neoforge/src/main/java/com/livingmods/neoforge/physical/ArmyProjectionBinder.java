package com.livingmods.neoforge.physical;

import com.livingmods.common.config.LivingModsConfig;
import com.livingmods.common.model.PhysicalOutcomeType;
import com.livingmods.neoforge.LivingModsMod;
import com.livingmods.neoforge.sidecar.SidecarClient;
import com.livingmods.neoforge.sidecar.WorldSessionLifecycle;
import com.livingmods.protocol.MessageType;
import com.livingmods.protocol.PhysicalOutcomePayload;
import com.livingmods.protocol.PayloadIo;
import com.livingmods.protocol.RequestPayloads;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Interest-based army LOD: representative squads, never 1:1 manpower.
 * Physical kills map to aggregated canonical casualties via outcomes.
 */
public final class ArmyProjectionBinder {
    private static final ArmyProjectionBinder INSTANCE = new ArmyProjectionBinder();

    private final LivingModsConfig config = LivingModsConfig.defaults();
    /** armyId → list of entity UUIDs */
    private final Map<UUID, List<UUID>> projected = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> strength = new ConcurrentHashMap<>();
    private final Set<String> pending = ConcurrentHashMap.newKeySet();
    private int tick;

    private ArmyProjectionBinder() {}

    public static ArmyProjectionBinder get() { return INSTANCE; }

    public void clear() {
        projected.clear();
        strength.clear();
        pending.clear();
        tick = 0;
    }

    public void onServerTick(MinecraftServer server) {
        if (++tick % 80 != 0) return;
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
                    pos.getX(), pos.getZ(), 160, config.armyProjectionCap()).encode();
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
            String packed = data.getOrDefault("armiesPacked", "");
            if (packed.isBlank()) return;
            int budget = config.armyProjectionCap();
            int used = projected.values().stream().mapToInt(List::size).sum();
            for (String row : packed.split(";")) {
                if (row.isBlank() || used >= budget) break;
                String[] p = row.split("\\|");
                if (p.length < 5) continue;
                UUID armyId = UUID.fromString(p[0]);
                int x = Integer.parseInt(p[2]);
                int z = Integer.parseInt(p[3]);
                int manpower = Integer.parseInt(p[4]);
                used += ensureSquad(level, armyId, x, z, manpower, budget - used);
            }
        } catch (Exception e) {
            LivingModsMod.LOG.debug("Army projection apply failed: {}", e.toString());
        }
    }

    private int ensureSquad(ServerLevel level, UUID armyId, int x, int z, int manpower, int remainingBudget) {
        strength.put(armyId, manpower);
        List<UUID> existing = projected.computeIfAbsent(armyId, id -> new ArrayList<>());
        existing.removeIf(id -> level.getEntity(id) == null);
        int desired = Math.max(2, Math.min(8, manpower / 40 + 2));
        desired = Math.min(desired, remainingBudget);
        int spawned = 0;
        while (existing.size() < desired) {
            int ox = (existing.size() % 3) * 2;
            int oz = (existing.size() / 3) * 2;
            int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x + ox, z + oz);
            Villager soldier = EntityType.VILLAGER.create(level);
            if (soldier == null) break;
            soldier.setCustomName(net.minecraft.network.chat.Component.literal("Soldier"));
            soldier.setCustomNameVisible(true);
            soldier.moveTo(x + ox + 0.5, y, z + oz + 0.5, 0, 0);
            soldier.getPersistentData().putUUID("livingmods_army", armyId);
            soldier.getPersistentData().putString("livingmods_kind", "SOLDIER");
            level.addFreshEntity(soldier);
            existing.add(soldier.getUUID());
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
                    if (p.distanceToSqr(entity) < 192 * 192) {
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
                strength.remove(e.getKey());
            }
        }
    }

    public void reportCasualties(UUID armyId, UUID playerId, int physicalKills) {
        SidecarClient client = WorldSessionLifecycle.activeClient();
        if (client == null || !client.isReady()) return;
        List<UUID> squad = projected.getOrDefault(armyId, List.of());
        Map<String, String> evidence = new LinkedHashMap<>();
        evidence.put("armyId", armyId.toString());
        evidence.put("casualties", String.valueOf(Math.max(1, physicalKills)));
        evidence.put("projectedSquads", String.valueOf(Math.max(1, squad.size())));
        try {
            client.sendAsync(MessageType.REPORT_PHYSICAL_OUTCOME, new PhysicalOutcomePayload(
                    PhysicalOutcomeType.ARMY_CASUALTIES, playerId, armyId, 0, 0, 0, 0L, evidence).encode());
        } catch (Exception e) {
            LivingModsMod.LOG.debug("Army casualty report failed: {}", e.toString());
        }
    }
}
