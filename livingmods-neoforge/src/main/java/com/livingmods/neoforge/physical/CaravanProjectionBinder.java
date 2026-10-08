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

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Interest-based caravan LOD: lead merchant entity + cargo metadata.
 * Canonical shipment remains authority.
 */
public final class CaravanProjectionBinder {
    private static final CaravanProjectionBinder INSTANCE = new CaravanProjectionBinder();

    private final LivingModsConfig config = LivingModsConfig.defaults();
    private final Map<UUID, UUID> projected = new ConcurrentHashMap<>();
    private final Map<UUID, String> cargoMeta = new ConcurrentHashMap<>();
    private final Set<String> pending = ConcurrentHashMap.newKeySet();
    private int tick;

    private CaravanProjectionBinder() {}

    public static CaravanProjectionBinder get() { return INSTANCE; }

    public void clear() {
        projected.clear();
        cargoMeta.clear();
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
            if (packed.isBlank()) {
                // Fallback: caravan ids only.
                String ids = data.getOrDefault("caravanIds", "");
                int i = 0;
                for (String id : ids.split(",")) {
                    if (id.isBlank()) continue;
                    UUID shipment = UUID.fromString(id.trim().replace("shipment:", ""));
                    ensure(level, shipment, level.players().isEmpty() ? 0 : (int) level.players().get(0).getX() + i * 3,
                            level.players().isEmpty() ? 0 : (int) level.players().get(0).getZ(),
                            "UNKNOWN", "0");
                    i++;
                }
                return;
            }
            for (String row : packed.split(";")) {
                if (row.isBlank()) continue;
                String[] p = row.split("\\|");
                if (p.length < 3) continue;
                UUID shipment = UUID.fromString(p[0]);
                int x = Integer.parseInt(p[1]);
                int z = Integer.parseInt(p[2]);
                String goods = p.length > 3 ? p[3] : "GOODS";
                String qty = p.length > 4 ? p[4] : "0";
                ensure(level, shipment, x, z, goods, qty);
            }
        } catch (Exception e) {
            LivingModsMod.LOG.debug("Caravan projection apply failed: {}", e.toString());
        }
    }

    private void ensure(ServerLevel level, UUID shipmentId, int x, int z, String goods, String qty) {
        if (projected.size() >= config.caravanProjectionCap() && !projected.containsKey(shipmentId)) {
            return;
        }
        UUID existing = projected.get(shipmentId);
        if (existing != null && level.getEntity(existing) != null) {
            cargoMeta.put(shipmentId, goods + ":" + qty);
            return;
        }
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        Villager lead = EntityType.VILLAGER.create(level);
        if (lead == null) return;
        lead.setCustomName(net.minecraft.network.chat.Component.literal("Caravan " + goods));
        lead.setCustomNameVisible(true);
        lead.moveTo(x + 0.5, y, z + 0.5, 0, 0);
        lead.getPersistentData().putUUID("livingmods_shipment", shipmentId);
        lead.getPersistentData().putString("livingmods_kind", "CARAVAN");
        lead.getPersistentData().putString("livingmods_cargo", goods + ":" + qty);
        level.addFreshEntity(lead);
        projected.put(shipmentId, lead.getUUID());
        cargoMeta.put(shipmentId, goods + ":" + qty);
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
                it.remove();
                cargoMeta.remove(e.getKey());
            }
        }
    }

    public void reportArrived(UUID shipmentId, UUID playerId) {
        report(shipmentId, playerId, PhysicalOutcomeType.CARAVAN_ARRIVED, Map.of("shipmentId", shipmentId.toString()));
    }

    public void reportDamaged(UUID shipmentId, UUID playerId, boolean playerAttacked) {
        Map<String, String> ev = new LinkedHashMap<>();
        ev.put("shipmentId", shipmentId.toString());
        ev.put("lossFraction", "0.4");
        ev.put("playerAttacked", String.valueOf(playerAttacked));
        report(shipmentId, playerId, PhysicalOutcomeType.CARAVAN_DAMAGED, ev);
    }

    private void report(UUID shipmentId, UUID playerId, PhysicalOutcomeType type, Map<String, String> evidence) {
        SidecarClient client = WorldSessionLifecycle.activeClient();
        if (client == null || !client.isReady()) return;
        try {
            client.sendAsync(MessageType.REPORT_PHYSICAL_OUTCOME, new PhysicalOutcomePayload(
                    type, playerId, shipmentId, 0, 0, 0, 0L, evidence).encode());
        } catch (Exception e) {
            LivingModsMod.LOG.debug("Caravan outcome failed: {}", e.toString());
        }
    }
}
