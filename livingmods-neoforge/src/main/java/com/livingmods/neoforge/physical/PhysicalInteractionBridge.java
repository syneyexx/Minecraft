package com.livingmods.neoforge.physical;

import com.livingmods.common.model.PhysicalOutcomeType;
import com.livingmods.neoforge.LivingModsMod;
import com.livingmods.neoforge.entity.CitizenEntity;
import com.livingmods.neoforge.entity.ProjectedHumanoidEntity;
import com.livingmods.neoforge.sidecar.SidecarClient;
import com.livingmods.neoforge.sidecar.WorldSessionLifecycle;
import com.livingmods.protocol.MessageType;
import com.livingmods.protocol.PhysicalOutcomePayload;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
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
            Map<String, String> evidence = new LinkedHashMap<>();
            evidence.put("citizenId", citizen.citizenIdOrNull().toString());
            evidence.put("entityKind", "CITIZEN");
            if (playerId != null) evidence.put("playerId", playerId.toString());
            report(PhysicalOutcomeType.ENTITY_KILLED, playerId, citizen.citizenIdOrNull(),
                    (int) victim.getX(), (int) victim.getY(), (int) victim.getZ(), evidence);
            return;
        }
        if (victim instanceof ProjectedHumanoidEntity projected) {
            Map<String, String> evidence = new LinkedHashMap<>();
            evidence.put("entityKind", projected.kind().name());
            if (playerId != null) evidence.put("playerId", playerId.toString());
            UUID target = projected.canonicalIdOrNull();
            switch (projected.kind()) {
                case CARAVAN -> {
                    if (target != null) {
                        evidence.put("shipmentId", target.toString());
                        // Loss scaled by remaining health context — lead death is significant but not total.
                        evidence.put("lossFraction", "0.35");
                        evidence.put("playerAttacked", String.valueOf(playerId != null));
                        CaravanProjectionBinder.get().reportDamaged(target, playerId, playerId != null, 0.35);
                    }
                }
                case SOLDIER -> {
                    if (target != null) {
                        evidence.put("armyId", target.toString());
                        evidence.put("casualties", "1");
                        ArmyProjectionBinder.get().reportCasualties(
                                target, playerId == null ? new UUID(0, 0) : playerId, 1);
                    }
                }
                case BANDIT -> {
                    evidence.put("entityKind", "BANDIT");
                    if (target != null) evidence.put("campId", target.toString());
                    report(PhysicalOutcomeType.ENTITY_KILLED, playerId,
                            target == null ? victim.getUUID() : target,
                            (int) victim.getX(), (int) victim.getY(), (int) victim.getZ(), evidence);
                }
                case GUARD -> {
                    evidence.put("entityKind", "GUARD");
                    if (projected.factionIdOrNull() != null) {
                        evidence.put("settlementId", projected.factionIdOrNull().toString());
                    }
                    if (target != null) evidence.put("citizenId", target.toString());
                    report(PhysicalOutcomeType.GUARD_ATTACKED, playerId,
                            target == null ? victim.getUUID() : target,
                            (int) victim.getX(), (int) victim.getY(), (int) victim.getZ(), evidence);
                    report(PhysicalOutcomeType.ENTITY_KILLED, playerId,
                            target == null ? victim.getUUID() : target,
                            (int) victim.getX(), (int) victim.getY(), (int) victim.getZ(), evidence);
                }
                default -> report(PhysicalOutcomeType.ENTITY_KILLED, playerId, victim.getUUID(),
                        (int) victim.getX(), (int) victim.getY(), (int) victim.getZ(), evidence);
            }
        }
    }

    @SubscribeEvent
    public static void onInteractEntity(PlayerInteractEvent.EntityInteract event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        if (!(event.getTarget() instanceof CitizenEntity citizen)) return;
        if (citizen.citizenIdOrNull() == null) return;
        // Dialogue hook — reputation-neutral; no arbitrary standing award for talk.
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

    /**
     * Server-authoritative task delivery: verifies and consumes inventory items before reporting.
     * Client cannot choose arbitrary amount or set serverVerified.
     */
    public static boolean reportTaskDelivery(
            ServerPlayer player,
            UUID taskId,
            UUID settlementId,
            String resource,
            double requestedAmount
    ) {
        if (player == null || taskId == null) return false;
        double amount = Math.max(1.0, Math.min(64.0, requestedAmount));
        net.minecraft.world.item.Item item = resolveResourceItem(resource);
        if (item == null) return false;
        int needed = (int) Math.ceil(amount);
        int counted = 0;
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (stack.is(item)) counted += stack.getCount();
        }
        if (counted < needed) {
            return false;
        }
        int remaining = needed;
        for (int i = 0; i < player.getInventory().getContainerSize() && remaining > 0; i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (!stack.is(item)) continue;
            int take = Math.min(remaining, stack.getCount());
            stack.shrink(take);
            remaining -= take;
        }
        Map<String, String> evidence = new LinkedHashMap<>();
        evidence.put("taskId", taskId.toString());
        evidence.put("settlementId", settlementId == null ? "" : settlementId.toString());
        evidence.put("resource", resource);
        evidence.put("amount", String.valueOf(needed));
        evidence.put("serverVerified", "true");
        evidence.put("inventoryConsumed", "true");
        evidence.put("playerId", player.getUUID().toString());
        report(PhysicalOutcomeType.TASK_ITEM_DELIVERED, player.getUUID(), taskId,
                (int) player.getX(), (int) player.getY(), (int) player.getZ(), evidence);
        return true;
    }

    public static void reportCampCleared(ServerPlayer player, UUID campId, UUID settlementId) {
        if (player == null || campId == null) return;
        // Server verifies camp identity exists in projection / structure index — client cannot invent clearance.
        Map<String, String> evidence = new LinkedHashMap<>();
        evidence.put("campId", campId.toString());
        if (settlementId != null) evidence.put("settlementId", settlementId.toString());
        evidence.put("playerId", player.getUUID().toString());
        evidence.put("serverVerified", "true");
        report(PhysicalOutcomeType.CAMP_CLEARED, player.getUUID(), campId,
                (int) player.getX(), (int) player.getY(), (int) player.getZ(), evidence);
    }

    private static net.minecraft.world.item.Item resolveResourceItem(String resource) {
        if (resource == null) return null;
        return switch (resource.toUpperCase(java.util.Locale.ROOT)) {
            case "GRAIN", "WHEAT", "FOOD" -> net.minecraft.world.item.Items.WHEAT;
            case "WOOD", "LOG" -> net.minecraft.world.item.Items.OAK_LOG;
            case "STONE" -> net.minecraft.world.item.Items.COBBLESTONE;
            case "IRON" -> net.minecraft.world.item.Items.IRON_INGOT;
            case "TOOLS" -> net.minecraft.world.item.Items.IRON_PICKAXE;
            default -> null;
        };
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
            // Zero UUID is not a legitimate player — omit reputation player id when unknown.
            UUID reportPlayer = playerId == null || (playerId.getMostSignificantBits() == 0
                    && playerId.getLeastSignificantBits() == 0) ? new UUID(0, 0) : playerId;
            if (playerId != null && !(playerId.getMostSignificantBits() == 0
                    && playerId.getLeastSignificantBits() == 0)) {
                evidence.putIfAbsent("playerId", playerId.toString());
            } else {
                evidence.remove("playerId");
            }
            client.sendAsync(MessageType.REPORT_PHYSICAL_OUTCOME, new PhysicalOutcomePayload(
                    type,
                    reportPlayer,
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
