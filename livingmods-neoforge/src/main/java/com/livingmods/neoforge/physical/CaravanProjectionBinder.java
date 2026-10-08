package com.livingmods.neoforge.physical;

import com.livingmods.common.config.LivingModsConfig;
import com.livingmods.common.model.PhysicalOutcomeType;
import com.livingmods.neoforge.LivingModsMod;
import com.livingmods.neoforge.entity.LivingModsEntities;
import com.livingmods.neoforge.entity.ProjectedHumanoidEntity;
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
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Interest-based caravan LOD: lead merchant + optional guard.
 * Follows canonical shipment route positions; arrival detected server-side.
 */
public final class CaravanProjectionBinder {
    private static final CaravanProjectionBinder INSTANCE = new CaravanProjectionBinder();

    private final LivingModsConfig config = LivingModsConfig.defaults();
    private final Map<UUID, UUID> projected = new ConcurrentHashMap<>();
    private final Map<UUID, UUID> guards = new ConcurrentHashMap<>();
    private final Map<UUID, String> cargoMeta = new ConcurrentHashMap<>();
    private final Map<UUID, int[]> lastPos = new ConcurrentHashMap<>();
    private final Map<UUID, int[]> destinations = new ConcurrentHashMap<>();
    private final Set<UUID> arrivedReported = ConcurrentHashMap.newKeySet();
    private final Set<String> pending = ConcurrentHashMap.newKeySet();
    private int tick;

    private CaravanProjectionBinder() {}

    public static CaravanProjectionBinder get() { return INSTANCE; }

    public void clear() {
        projected.clear();
        guards.clear();
        cargoMeta.clear();
        lastPos.clear();
        destinations.clear();
        arrivedReported.clear();
        pending.clear();
        tick = 0;
    }

    public void onServerTick(MinecraftServer server) {
        if (++tick % 30 != 0) return;
        SidecarClient client = WorldSessionLifecycle.activeClient();
        if (client == null || !client.isReady()) return;
        ServerLevel level = server.overworld();
        for (ServerPlayer player : level.players()) {
            requestNear(level, client, player.blockPosition());
        }
        updateMovementAndArrival(level);
        despawnFar(level);
    }

    private void requestNear(ServerLevel level, SidecarClient client, BlockPos pos) {
        String key = (pos.getX() >> 6) + ":" + (pos.getZ() >> 6);
        if (!pending.add(key)) return;
        try {
            byte[] payload = new RequestPayloads.NearbyQuery(
                    pos.getX(), pos.getZ(), 128, config.caravanProjectionCap()).encode();
            client.sendAsync(MessageType.GET_PHYSICAL_PROJECTION_PLAN, payload)
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
            String packed = data.getOrDefault("caravansPacked", "");
            if (packed.isBlank()) return;
            for (String row : packed.split(";")) {
                if (row.isBlank()) continue;
                String[] p = row.split("\\|");
                if (p.length < 3) continue;
                UUID shipment = UUID.fromString(p[0]);
                int x = Integer.parseInt(p[1]);
                int z = Integer.parseInt(p[2]);
                String goods = p.length > 3 ? p[3] : "GOODS";
                String qty = p.length > 4 ? p[4] : "0";
                int destX = p.length > 5 ? Integer.parseInt(p[5]) : x;
                int destZ = p.length > 6 ? Integer.parseInt(p[6]) : z;
                String culture = p.length > 7 ? p[7] : "avalon";
                destinations.put(shipment, new int[]{destX, destZ});
                ensure(level, shipment, x, z, goods, qty, culture);
            }
        } catch (Exception e) {
            LivingModsMod.LOG.debug("Caravan projection apply failed: {}", e.toString());
        }
    }

    private void ensure(ServerLevel level, UUID shipmentId, int x, int z, String goods, String qty, String culture) {
        if (projected.size() >= config.caravanProjectionCap() && !projected.containsKey(shipmentId)) {
            return;
        }
        // Rebind after reload.
        for (ProjectedHumanoidEntity e : level.getEntitiesOfClass(ProjectedHumanoidEntity.class,
                new net.minecraft.world.phys.AABB(x - 32, 0, z - 32, x + 32, 256, z + 32))) {
            if (e.kind() == ProjectedHumanoidEntity.Kind.CARAVAN
                    && shipmentId.equals(e.canonicalIdOrNull())) {
                projected.put(shipmentId, e.getUUID());
                e.setNavigationTarget(x, z);
                e.setCargoMeta(goods + ":" + qty);
                cargoMeta.put(shipmentId, goods + ":" + qty);
                lastPos.put(shipmentId, new int[]{x, z});
                return;
            }
        }
        UUID existing = projected.get(shipmentId);
        if (existing != null && level.getEntity(existing) instanceof ProjectedHumanoidEntity lead) {
            lead.setNavigationTarget(x, z);
            lead.setCargoMeta(goods + ":" + qty);
            cargoMeta.put(shipmentId, goods + ":" + qty);
            lastPos.put(shipmentId, new int[]{x, z});
            return;
        }
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        ProjectedHumanoidEntity lead = LivingModsEntities.PROJECTED_HUMANOID.get().create(level);
        if (lead == null) return;
        lead.moveTo(x + 0.5, y, z + 0.5, 0, 0);
        lead.bind(ProjectedHumanoidEntity.Kind.CARAVAN, shipmentId, null, 1L,
                "Caravan " + goods, "MERCHANT", culture, false);
        lead.setCargoMeta(goods + ":" + qty);
        lead.setNavigationTarget(x, z);
        level.addFreshEntity(lead);
        projected.put(shipmentId, lead.getUUID());
        cargoMeta.put(shipmentId, goods + ":" + qty);
        lastPos.put(shipmentId, new int[]{x, z});

        // Bounded escort guard.
        if (!guards.containsKey(shipmentId)) {
            ProjectedHumanoidEntity guard = LivingModsEntities.PROJECTED_HUMANOID.get().create(level);
            if (guard != null) {
                guard.moveTo(x + 1.5, y, z + 0.5, 0, 0);
                guard.bind(ProjectedHumanoidEntity.Kind.GUARD, shipmentId, null, 1L,
                        "Caravan Guard", "ESCORT", culture, false);
                guard.setNavigationTarget(x, z);
                level.addFreshEntity(guard);
                guards.put(shipmentId, guard.getUUID());
            }
        }
    }

    private void updateMovementAndArrival(ServerLevel level) {
        for (var e : projected.entrySet()) {
            UUID shipment = e.getKey();
            if (!(level.getEntity(e.getValue()) instanceof ProjectedHumanoidEntity lead)) continue;
            int[] dest = destinations.get(shipment);
            int[] pos = lastPos.get(shipment);
            if (dest != null) {
                lead.setNavigationTarget(dest[0], dest[1]);
                UUID guardId = guards.get(shipment);
                if (guardId != null && level.getEntity(guardId) instanceof ProjectedHumanoidEntity g) {
                    g.setNavigationTarget(dest[0], dest[1]);
                }
                double dx = lead.getX() - dest[0];
                double dz = lead.getZ() - dest[1];
                if (dx * dx + dz * dz < 16 && arrivedReported.add(shipment)) {
                    reportArrived(shipment, new UUID(0, 0));
                }
            } else if (pos != null) {
                lead.setNavigationTarget(pos[0], pos[1]);
            }
        }
    }

    private void despawnFar(ServerLevel level) {
        for (var it = projected.entrySet().iterator(); it.hasNext(); ) {
            var e = it.next();
            var entity = level.getEntity(e.getValue());
            if (entity == null) {
                it.remove();
                continue;
            }
            boolean near = false;
            for (ServerPlayer p : level.players()) {
                if (p.distanceToSqr(entity) < 160 * 160) {
                    near = true;
                    break;
                }
            }
            if (!near) {
                entity.discard();
                UUID g = guards.remove(e.getKey());
                if (g != null) {
                    var ge = level.getEntity(g);
                    if (ge != null) ge.discard();
                }
                it.remove();
                cargoMeta.remove(e.getKey());
                lastPos.remove(e.getKey());
                destinations.remove(e.getKey());
            }
        }
    }

    public void reportArrived(UUID shipmentId, UUID playerId) {
        report(shipmentId, playerId, PhysicalOutcomeType.CARAVAN_ARRIVED,
                Map.of("shipmentId", shipmentId.toString()));
    }

    public void reportDamaged(UUID shipmentId, UUID playerId, boolean playerAttacked, double lossFraction) {
        Map<String, String> ev = new LinkedHashMap<>();
        ev.put("shipmentId", shipmentId.toString());
        ev.put("lossFraction", String.format(java.util.Locale.ROOT, "%.2f", Math.max(0.05, Math.min(1.0, lossFraction))));
        ev.put("playerAttacked", String.valueOf(playerAttacked));
        report(shipmentId, playerId, PhysicalOutcomeType.CARAVAN_DAMAGED, ev);
    }

    private void report(UUID shipmentId, UUID playerId, PhysicalOutcomeType type, Map<String, String> evidence) {
        SidecarClient client = WorldSessionLifecycle.activeClient();
        if (client == null || !client.isReady()) return;
        try {
            client.sendAsync(MessageType.REPORT_PHYSICAL_OUTCOME, new PhysicalOutcomePayload(
                    type, playerId == null ? new UUID(0, 0) : playerId, shipmentId, 0, 0, 0, 0L, evidence).encode());
        } catch (Exception e) {
            LivingModsMod.LOG.debug("Caravan outcome failed: {}", e.toString());
        }
    }
}
