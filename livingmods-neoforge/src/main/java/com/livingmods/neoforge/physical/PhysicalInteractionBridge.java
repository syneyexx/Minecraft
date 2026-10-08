package com.livingmods.neoforge.physical;

import com.livingmods.common.model.PhysicalOutcomeType;
import com.livingmods.neoforge.LivingModsMod;
import com.livingmods.neoforge.entity.CitizenEntity;
import com.livingmods.neoforge.sidecar.SidecarClient;
import com.livingmods.neoforge.sidecar.WorldSessionLifecycle;
import com.livingmods.protocol.MessageType;
import com.livingmods.protocol.PhysicalOutcomePayload;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.npc.Villager;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Single server-side bridge translating Minecraft events into verified canonical outcomes.
 * No ad-hoc sendAsync scattered across gameplay code.
 */
public final class PhysicalInteractionBridge {

    private PhysicalInteractionBridge() {}

    @SubscribeEvent
    public static void onDeath(LivingDeathEvent event) {
        if (!(event.getEntity().level() instanceof net.minecraft.server.level.ServerLevel)) {
            return;
        }
        LivingEntity victim = event.getEntity();
        UUID playerId = null;
        if (event.getSource().getEntity() instanceof ServerPlayer player) {
            playerId = player.getUUID();
        }
        if (victim instanceof CitizenEntity citizen && citizen.citizenIdOrNull() != null) {
            // Real death (not LOD despawn).
            Map<String, String> evidence = new LinkedHashMap<>();
            evidence.put("citizenId", citizen.citizenIdOrNull().toString());
            evidence.put("entityKind", "CITIZEN");
            if (playerId != null) evidence.put("playerId", playerId.toString());
            report(PhysicalOutcomeType.ENTITY_KILLED, playerId, citizen.citizenIdOrNull(),
                    (int) victim.getX(), (int) victim.getY(), (int) victim.getZ(), evidence);
            return;
        }
        if (victim instanceof Villager villager) {
            var tag = villager.getPersistentData();
            if (tag.contains("livingmods_kind")) {
                String kind = tag.getString("livingmods_kind");
                Map<String, String> evidence = new LinkedHashMap<>();
                evidence.put("entityKind", kind);
                if (playerId != null) evidence.put("playerId", playerId.toString());
                UUID target = null;
                if ("CARAVAN".equals(kind) && tag.hasUUID("livingmods_shipment")) {
                    target = tag.getUUID("livingmods_shipment");
                    evidence.put("shipmentId", target.toString());
                    evidence.put("lossFraction", "0.5");
                    evidence.put("playerAttacked", String.valueOf(playerId != null));
                    report(PhysicalOutcomeType.CARAVAN_DAMAGED, playerId, target,
                            (int) victim.getX(), (int) victim.getY(), (int) victim.getZ(), evidence);
                    return;
                }
                if ("SOLDIER".equals(kind) && tag.hasUUID("livingmods_army")) {
                    target = tag.getUUID("livingmods_army");
                    evidence.put("armyId", target.toString());
                    evidence.put("casualties", "1");
                    ArmyProjectionBinder.get().reportCasualties(target, playerId == null ? new UUID(0, 0) : playerId, 1);
                    return;
                }
                if ("BANDIT".equals(kind)) {
                    evidence.put("entityKind", "BANDIT");
                    report(PhysicalOutcomeType.ENTITY_KILLED, playerId, victim.getUUID(),
                            (int) victim.getX(), (int) victim.getY(), (int) victim.getZ(), evidence);
                }
            }
        }
    }

    @SubscribeEvent
    public static void onInteractEntity(PlayerInteractEvent.EntityInteract event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        if (!(event.getTarget() instanceof CitizenEntity citizen)) return;
        if (citizen.citizenIdOrNull() == null) return;
        // Dialogue / task discovery hook — reputation-neutral talk event via PLAYER_ACTION.
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("action", "CITIZEN_TALKED");
        fields.put("playerId", player.getUUID().toString());
        fields.put("citizenId", citizen.citizenIdOrNull().toString());
        fields.put("x", String.valueOf((int) citizen.getX()));
        fields.put("z", String.valueOf((int) citizen.getZ()));
        SidecarClient client = WorldSessionLifecycle.activeClient();
        if (client == null || !client.isReady()) return;
        try {
            client.sendAsync(MessageType.PLAYER_ACTION, com.livingmods.protocol.PayloadIo.encodeStrings(fields));
        } catch (Exception e) {
            LivingModsMod.LOG.debug("Citizen talk bridge failed: {}", e.toString());
        }
    }

    public static void reportTaskDelivery(
            ServerPlayer player,
            UUID taskId,
            UUID settlementId,
            String resource,
            double amount
    ) {
        Map<String, String> evidence = new LinkedHashMap<>();
        evidence.put("taskId", taskId.toString());
        evidence.put("settlementId", settlementId.toString());
        evidence.put("resource", resource);
        evidence.put("amount", String.valueOf(amount));
        evidence.put("serverVerified", "true");
        evidence.put("playerId", player.getUUID().toString());
        report(PhysicalOutcomeType.TASK_ITEM_DELIVERED, player.getUUID(), taskId,
                (int) player.getX(), (int) player.getY(), (int) player.getZ(), evidence);
    }

    public static void reportCampCleared(ServerPlayer player, UUID campId, UUID settlementId) {
        Map<String, String> evidence = new LinkedHashMap<>();
        evidence.put("campId", campId.toString());
        evidence.put("campCleared", "true");
        if (settlementId != null) evidence.put("settlementId", settlementId.toString());
        evidence.put("playerId", player.getUUID().toString());
        report(PhysicalOutcomeType.CAMP_CLEARED, player.getUUID(), campId,
                (int) player.getX(), (int) player.getY(), (int) player.getZ(), evidence);
    }

    private static void report(
            PhysicalOutcomeType type,
            UUID playerId,
            UUID targetId,
            int x,
            int y,
            int z,
            Map<String, String> evidence
    ) {
        SidecarClient client = WorldSessionLifecycle.activeClient();
        if (client == null || !client.isReady()) return;
        try {
            if (playerId != null) {
                evidence.putIfAbsent("playerId", playerId.toString());
            }
            client.sendAsync(MessageType.REPORT_PHYSICAL_OUTCOME, new PhysicalOutcomePayload(
                    type,
                    playerId == null ? new UUID(0, 0) : playerId,
                    targetId,
                    x, y, z,
                    0L,
                    evidence
            ).encode());
        } catch (Exception e) {
            LivingModsMod.LOG.debug("PhysicalInteractionBridge report failed: {}", e.toString());
        }
    }
}
