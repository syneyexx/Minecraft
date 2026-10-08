package com.livingmods.neoforge.gameplay;

import com.livingmods.common.model.ResourceType;
import com.livingmods.neoforge.LivingModsMod;
import com.livingmods.neoforge.entity.CitizenEntity;
import com.livingmods.neoforge.entity.ProjectedHumanoidEntity;
import com.livingmods.neoforge.network.CitizenInteractionPayloads;
import com.livingmods.neoforge.physical.PhysicalInteractionBridge;
import com.livingmods.neoforge.sidecar.SidecarClient;
import com.livingmods.neoforge.sidecar.WorldSessionLifecycle;
import com.livingmods.protocol.MessageType;
import com.livingmods.protocol.PlayerActionRequest;
import com.livingmods.protocol.PlayerActionResponse;
import com.livingmods.protocol.PlayerActionType;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Server-side player command bridge: verified Minecraft facts → typed PLAYER_ACTION → UI feedback.
 * Cooperates with {@link PhysicalInteractionBridge} without duplicating physical outcomes.
 */
public final class PlayerGameplayBridge {
    private static int tickCounter;

    private PlayerGameplayBridge() {}

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        if (++tickCounter % 80 != 0) return;
        var server = event.getServer();
        if (server == null) return;
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            FactionDispositionCache.get().tickRefresh(player.getUUID());
        }
    }

    @SubscribeEvent
    public static void onInteractEntity(PlayerInteractEvent.EntityInteract event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        Entity target = event.getTarget();
        if (target instanceof CitizenEntity citizen && citizen.citizenIdOrNull() != null) {
            event.setCanceled(true);
            openCitizenInteraction(player, citizen);
            return;
        }
        if (target instanceof ProjectedHumanoidEntity projected) {
            event.setCanceled(true);
            openProjectedInteraction(player, projected);
        }
    }

    @SubscribeEvent
    public static void onPlayerKill(LivingDeathEvent event) {
        if (!(event.getSource().getEntity() instanceof ServerPlayer player)) return;
        if (!(event.getEntity().level() instanceof net.minecraft.server.level.ServerLevel)) return;
        // Crime consequences for attacking LivingMods entities — PhysicalInteractionBridge reports outcomes;
        // here we attach player crime when the victim is a protected civilian/guard.
        if (event.getEntity() instanceof CitizenEntity) {
            notify(player, "Crime recorded: assault/murder of a citizen");
        } else if (event.getEntity() instanceof ProjectedHumanoidEntity projected) {
            switch (projected.kind()) {
                case GUARD -> notify(player, "You are wanted for attacking a guard");
                case CARAVAN -> notify(player, "Caravan robbery noted by local authorities");
                case SOLDIER -> notify(player, "Military hostilities escalate");
                default -> {
                }
            }
            // Mark wanted locally for AI until sidecar refresh lands.
            FactionDispositionCache.get().putPlayerLegal(player.getUUID(), "WANTED", true);
        }
    }

    private static void openCitizenInteraction(ServerPlayer player, CitizenEntity citizen) {
        if (player.distanceTo(citizen) > PlayerInteractionSessions.MAX_DISTANCE) {
            notify(player, "Too far to talk");
            return;
        }
        SidecarClient client = WorldSessionLifecycle.activeClient();
        if (client == null || !client.isReady()) {
            notify(player, "Civilization systems unavailable");
            return;
        }
        Map<String, String> meta = new LinkedHashMap<>();
        meta.put("source", "citizen_interact");
        PlayerActionRequest request = new PlayerActionRequest(
                PlayerActionType.OPEN_INTERACTION,
                player.getUUID(),
                new UUID(0, 0),
                new UUID(0, 0),
                citizen.citizenIdOrNull(),
                citizen.getUUID(),
                new UUID(0, 0),
                (int) citizen.getX(),
                (int) citizen.getY(),
                (int) citizen.getZ(),
                0L,
                meta
        );
        try {
            client.sendAsync(MessageType.PLAYER_ACTION, request.encode()).thenAccept(env -> {
                player.getServer().execute(() -> {
                    try {
                        PlayerActionResponse response = PlayerActionResponse.decode(env.payload());
                        if (!response.success()) {
                            notify(player, response.message());
                            return;
                        }
                        UUID settlementId = parseUuid(response.data().get("settlementId"));
                        UUID kingdomId = parseUuid(response.data().get("kingdomId"));
                        var session = PlayerInteractionSessions.get().open(
                                player, citizen, citizen.citizenIdOrNull(), settlementId, kingdomId,
                                response.sessionId().getLeastSignificantBits(), response.data());
                        if (session == null) {
                            notify(player, "Interaction rejected");
                            return;
                        }
                        player.connection.send(CitizenInteractionPayloads.OpenScreen.fromResponse(
                                session.sessionId(), response));
                    } catch (Exception e) {
                        LivingModsMod.LOG.debug("Citizen interaction decode failed: {}", e.toString());
                        notify(player, "Could not open conversation");
                    }
                });
            });
        } catch (Exception e) {
            LivingModsMod.LOG.debug("Citizen interaction IPC failed: {}", e.toString());
        }
    }

    private static void openProjectedInteraction(ServerPlayer player, ProjectedHumanoidEntity projected) {
        if (player.distanceTo(projected) > PlayerInteractionSessions.MAX_DISTANCE) {
            notify(player, "Too far");
            return;
        }
        switch (projected.kind()) {
            case CARAVAN -> {
                String cargo = projected.cargoMeta();
                notify(player, "Caravan" + (cargo.isBlank() ? "" : " cargo: " + cargo)
                        + " — trade/escort via nearby merchants or task journal");
                // Offer escort discovery via QUERY context
                requestAction(player, PlayerActionType.QUERY_PLAYER_CONTEXT, Map.of("caravanId",
                        projected.canonicalIdOrNull() == null ? "" : projected.canonicalIdOrNull().toString()),
                        (int) projected.getX(), (int) projected.getY(), (int) projected.getZ(),
                        null, null, projected.canonicalIdOrNull());
            }
            case GUARD -> {
                String legal = FactionDispositionCache.get().legalStatus(player.getUUID());
                notify(player, "Guard: local watch. Your legal status: " + legal);
            }
            case BANDIT -> notify(player, "Bandits regard you with hostility.");
            case SOLDIER -> notify(player, "Soldier on campaign. Hostility follows war state.");
            default -> notify(player, projected.kind().name());
        }
    }

    public static void handleDialogueChoice(ServerPlayer player, UUID sessionId, String choice) {
        var session = PlayerInteractionSessions.get().require(player, sessionId);
        if (session == null) {
            notify(player, "Conversation expired");
            return;
        }
        if (choice == null || choice.isBlank() || choice.equals("LEAVE")) {
            PlayerInteractionSessions.get().close(player.getUUID());
            return;
        }
        if (choice.startsWith("TALK:")) {
            String topic = choice.substring(5).toLowerCase(java.util.Locale.ROOT);
            Map<String, String> meta = new LinkedHashMap<>();
            meta.put("topic", topic);
            meta.put("intent", topic);
            requestAction(player, PlayerActionType.REQUEST_DIALOGUE_TOPIC, meta,
                    (int) player.getX(), (int) player.getY(), (int) player.getZ(),
                    session.citizenId(), session.settlementId(), session.sessionId(),
                    resp -> player.connection.send(CitizenInteractionPayloads.DialogueUpdate.fromResponse(resp)));
            return;
        }
        if (choice.equals("OPEN_MARKET")) {
            openMarket(player, session);
            return;
        }
        if (choice.startsWith("ACCEPT_TASK:")) {
            String taskId = choice.substring("ACCEPT_TASK:".length());
            Map<String, String> meta = Map.of("taskId", taskId);
            UUID tid = parseUuid(taskId);
            requestAction(player, PlayerActionType.ACCEPT_TASK, meta,
                    (int) player.getX(), (int) player.getY(), (int) player.getZ(),
                    session.citizenId(), session.settlementId(), tid,
                    resp -> {
                        notify(player, resp.message());
                        if (resp.success()) {
                            player.connection.send(CitizenInteractionPayloads.TaskJournalUpdate.accepted(resp));
                        }
                    });
            return;
        }
        if (choice.equals("JOIN_FACTION") && session.kingdomId() != null) {
            requestAction(player, PlayerActionType.JOIN_FACTION, Map.of(),
                    (int) player.getX(), (int) player.getY(), (int) player.getZ(),
                    session.citizenId(), session.settlementId(), session.kingdomId(),
                    resp -> notify(player, resp.message()));
            return;
        }
        if (choice.equals("LEAVE_FACTION") && session.kingdomId() != null) {
            requestAction(player, PlayerActionType.LEAVE_FACTION, Map.of(),
                    (int) player.getX(), (int) player.getY(), (int) player.getZ(),
                    session.citizenId(), session.settlementId(), session.kingdomId(),
                    resp -> notify(player, resp.message()));
            return;
        }
        if (choice.equals("SURRENDER_TO_GUARDS")) {
            Map<String, String> meta = new LinkedHashMap<>();
            requestAction(player, PlayerActionType.SURRENDER_TO_GUARDS, meta,
                    (int) player.getX(), (int) player.getY(), (int) player.getZ(),
                    session.citizenId(), session.settlementId(), session.kingdomId(),
                    resp -> notify(player, resp.message()));
            return;
        }
        if (choice.equals("PAY_FINE")) {
            payFine(player, session);
            return;
        }
        if (choice.equals("MANAGE_REALM") || choice.equals("FOUND_REALM_INFO") || choice.equals("VIEW_KINGDOM")
                || choice.equals("DIPLOMACY") || choice.equals("VIEW_TASKS_HIGH")) {
            requestAction(player, PlayerActionType.QUERY_PLAYER_CONTEXT, Map.of("panel", choice),
                    (int) player.getX(), (int) player.getY(), (int) player.getZ(),
                    session.citizenId(), session.settlementId(), session.kingdomId(),
                    resp -> player.connection.send(CitizenInteractionPayloads.RealmPanel.fromResponse(resp, choice)));
        }
    }

    public static void handleMarketBuy(ServerPlayer player, UUID settlementId, String resource, int amount) {
        amount = Math.max(1, Math.min(64, amount));
        ResourceType type;
        try {
            type = ResourceType.valueOf(resource.toUpperCase(java.util.Locale.ROOT));
        } catch (Exception e) {
            notify(player, "Unknown resource");
            return;
        }
        // Quote via GET_MARKET_STATE then validate payment.
        SidecarClient client = WorldSessionLifecycle.activeClient();
        if (client == null || !client.isReady()) {
            notify(player, "Market unavailable");
            return;
        }
        try {
            var query = new com.livingmods.protocol.RequestPayloads.SettlementQuery(settlementId);
            final int buyAmount = amount;
            client.sendAsync(MessageType.GET_MARKET_STATE, query.encode()).thenAccept(env -> {
                player.getServer().execute(() -> {
                    try {
                        Map<String, String> market = com.livingmods.protocol.PayloadIo.decodeStrings(env.payload());
                        if (!"ok".equals(market.get("status"))) {
                            notify(player, "No market here");
                            return;
                        }
                        double unit = Double.parseDouble(market.getOrDefault("price_" + type.name(), "1"));
                        // Apply rough reputation markup locally; canonical re-validates stock.
                        int goldNeeded = Math.max(1, (int) Math.ceil(unit * buyAmount));
                        if (!consumeCurrency(player, goldNeeded)) {
                            notify(player, "Need " + goldNeeded + " gold ingots");
                            return;
                        }
                        Optional<Item> item = ResourceItemMapping.itemFor(type);
                        if (item.isEmpty()) {
                            refundCurrency(player, goldNeeded);
                            notify(player, "Resource not tradeable as items");
                            return;
                        }
                        if (!player.getInventory().add(new ItemStack(item.get(), buyAmount))) {
                            refundCurrency(player, goldNeeded);
                            notify(player, "Inventory full");
                            return;
                        }
                        Map<String, String> meta = new LinkedHashMap<>();
                        meta.put("resource", type.name());
                        meta.put("amount", String.valueOf(buyAmount));
                        meta.put("serverVerified", "true");
                        meta.put("paymentConsumed", "true");
                        meta.put("paymentGold", String.valueOf(goldNeeded));
                        requestAction(player, PlayerActionType.BUY_RESOURCE, meta,
                                (int) player.getX(), (int) player.getY(), (int) player.getZ(),
                                null, settlementId, null,
                                resp -> {
                                    if (!resp.success()) {
                                        // Roll back items + currency on canonical rejection.
                                        removeItems(player, item.get(), buyAmount);
                                        refundCurrency(player, goldNeeded);
                                    }
                                    notify(player, resp.message());
                                });
                    } catch (Exception e) {
                        notify(player, "Market transaction failed");
                    }
                });
            });
        } catch (Exception e) {
            notify(player, "Market request failed");
        }
    }

    public static void handleMarketSell(ServerPlayer player, UUID settlementId, String resource, int amount) {
        amount = Math.max(1, Math.min(64, amount));
        Optional<Item> item = ResourceItemMapping.itemFor(resource);
        if (item.isEmpty()) {
            notify(player, "Cannot sell that resource");
            return;
        }
        if (!consumeItems(player, item.get(), amount)) {
            notify(player, "You lack the items");
            return;
        }
        Map<String, String> meta = new LinkedHashMap<>();
        meta.put("resource", resource.toUpperCase(java.util.Locale.ROOT));
        meta.put("amount", String.valueOf(amount));
        meta.put("serverVerified", "true");
        meta.put("inventoryConsumed", "true");
        final int sellAmount = amount;
        requestAction(player, PlayerActionType.SELL_RESOURCE, meta,
                (int) player.getX(), (int) player.getY(), (int) player.getZ(),
                null, settlementId, null,
                resp -> {
                    if (!resp.success()) {
                        player.getInventory().add(new ItemStack(item.get(), sellAmount));
                        notify(player, resp.message());
                        return;
                    }
                    int gold = 1;
                    try {
                        gold = Integer.parseInt(resp.data().getOrDefault("paymentGold", "1"));
                    } catch (Exception ignored) {
                    }
                    player.getInventory().add(new ItemStack(ResourceItemMapping.currencyItem(), Math.max(1, gold)));
                    notify(player, resp.message());
                });
    }

    public static void handleRealmAction(ServerPlayer player, PlayerActionType type, Map<String, String> meta) {
        requestAction(player, type, meta == null ? Map.of() : meta,
                (int) player.getX(), (int) player.getY(), (int) player.getZ(),
                null, null, null,
                resp -> notify(player, resp.message()));
    }

    public static void handleFoundRealm(ServerPlayer player, String name, String culture, int x, int z) {
        Map<String, String> meta = new LinkedHashMap<>();
        meta.put("realmName", name == null ? "New Realm" : name);
        meta.put("culture", culture == null ? "avalon" : culture);
        PlayerActionRequest request = new PlayerActionRequest(
                PlayerActionType.FOUND_REALM, player.getUUID(),
                new UUID(0, 0), new UUID(0, 0), new UUID(0, 0), new UUID(0, 0), new UUID(0, 0),
                x, (int) player.getY(), z, 0L, meta);
        SidecarClient client = WorldSessionLifecycle.activeClient();
        if (client == null || !client.isReady()) {
            notify(player, "Cannot found realm — systems offline");
            return;
        }
        try {
            client.sendAsync(MessageType.PLAYER_ACTION, request.encode()).thenAccept(env -> {
                player.getServer().execute(() -> {
                    try {
                        PlayerActionResponse resp = PlayerActionResponse.decode(env.payload());
                        notify(player, resp.message());
                        if (resp.success()) {
                            notify(player, "Founding construction underway");
                        }
                    } catch (Exception e) {
                        notify(player, "Founding failed");
                    }
                });
            });
        } catch (Exception e) {
            notify(player, "Founding request failed");
        }
    }

    public static void handleTaskDelivery(ServerPlayer player, UUID taskId, UUID settlementId, String resource, double amount) {
        boolean ok = PhysicalInteractionBridge.reportTaskDelivery(player, taskId, settlementId, resource, amount);
        notify(player, ok ? "Delivery submitted" : "Delivery failed — check inventory");
    }

    public static void handleAbandonTask(ServerPlayer player, UUID taskId) {
        Map<String, String> meta = Map.of("taskId", taskId.toString());
        requestAction(player, PlayerActionType.ABANDON_TASK, meta,
                (int) player.getX(), (int) player.getY(), (int) player.getZ(),
                null, null, taskId,
                resp -> notify(player, resp.message()));
    }

    public static void discoverNearbySettlement(ServerPlayer player) {
        discoverAt(player, (int) player.getX(), (int) player.getZ());
    }

    public static void discoverAt(ServerPlayer player, int x, int z) {
        int px = x == 0 && z == 0 ? (int) player.getX() : x;
        int pz = x == 0 && z == 0 ? (int) player.getZ() : z;
        requestAction(player, PlayerActionType.DISCOVER_SETTLEMENT, Map.of(),
                px, (int) player.getY(), pz,
                null, null, null,
                resp -> {
                    if (resp.success()) notify(player, resp.message());
                });
    }

    private static void openMarket(ServerPlayer player, PlayerInteractionSessions.Session session) {
        if (session.settlementId() == null) {
            notify(player, "No market settlement");
            return;
        }
        SidecarClient client = WorldSessionLifecycle.activeClient();
        if (client == null || !client.isReady()) return;
        try {
            var query = new com.livingmods.protocol.RequestPayloads.SettlementQuery(session.settlementId());
            client.sendAsync(MessageType.GET_MARKET_STATE, query.encode()).thenAccept(env -> {
                player.getServer().execute(() -> {
                    try {
                        Map<String, String> market = com.livingmods.protocol.PayloadIo.decodeStrings(env.payload());
                        player.connection.send(CitizenInteractionPayloads.MarketScreenData.of(
                                session.sessionId(), session.settlementId(), market));
                    } catch (Exception e) {
                        notify(player, "Could not open market");
                    }
                });
            });
        } catch (Exception e) {
            notify(player, "Market unavailable");
        }
    }

    private static void payFine(ServerPlayer player, PlayerInteractionSessions.Session session) {
        int estimate = 20;
        if (!consumeCurrency(player, estimate)) {
            notify(player, "Need gold to pay fine");
            return;
        }
        Map<String, String> meta = new LinkedHashMap<>();
        meta.put("serverVerified", "true");
        meta.put("amount", String.valueOf(estimate));
        requestAction(player, PlayerActionType.PAY_FINE, meta,
                (int) player.getX(), (int) player.getY(), (int) player.getZ(),
                session.citizenId(), session.settlementId(), session.kingdomId(),
                resp -> {
                    if (!resp.success()) {
                        refundCurrency(player, estimate);
                    } else {
                        FactionDispositionCache.get().putPlayerLegal(player.getUUID(),
                                resp.data().getOrDefault("legalStatus", "CLEAR"), false);
                    }
                    notify(player, resp.message());
                });
    }

    private static void requestAction(
            ServerPlayer player,
            PlayerActionType type,
            Map<String, String> meta,
            int x, int y, int z,
            UUID citizenId,
            UUID settlementId,
            UUID targetOrKingdom
    ) {
        requestAction(player, type, meta, x, y, z, citizenId, settlementId, targetOrKingdom,
                resp -> notify(player, resp.message()));
    }

    private static void requestAction(
            ServerPlayer player,
            PlayerActionType type,
            Map<String, String> meta,
            int x, int y, int z,
            UUID citizenId,
            UUID settlementId,
            UUID targetOrKingdom,
            java.util.function.Consumer<PlayerActionResponse> callback
    ) {
        SidecarClient client = WorldSessionLifecycle.activeClient();
        if (client == null || !client.isReady()) {
            notify(player, "Systems unavailable");
            return;
        }
        UUID kingdomId = new UUID(0, 0);
        UUID targetId = new UUID(0, 0);
        if (type == PlayerActionType.JOIN_FACTION || type == PlayerActionType.LEAVE_FACTION
                || type == PlayerActionType.PAY_FINE || type == PlayerActionType.SURRENDER_TO_GUARDS
                || type == PlayerActionType.REQUEST_DIPLOMATIC_ACTION
                || type == PlayerActionType.DECLARE_SUPPORT_IN_WAR) {
            kingdomId = targetOrKingdom == null ? new UUID(0, 0) : targetOrKingdom;
        } else {
            targetId = targetOrKingdom == null ? new UUID(0, 0) : targetOrKingdom;
        }
        PlayerActionRequest request = new PlayerActionRequest(
                type, player.getUUID(), kingdomId,
                settlementId == null ? new UUID(0, 0) : settlementId,
                citizenId == null ? new UUID(0, 0) : citizenId,
                targetId, new UUID(0, 0), x, y, z, 0L, meta);
        try {
            client.sendAsync(MessageType.PLAYER_ACTION, request.encode()).thenAccept(env -> {
                player.getServer().execute(() -> {
                    try {
                        PlayerActionResponse resp = PlayerActionResponse.decode(env.payload());
                        if (callback != null) callback.accept(resp);
                    } catch (Exception e) {
                        LivingModsMod.LOG.debug("Player action decode failed: {}", e.toString());
                    }
                });
            });
        } catch (Exception e) {
            LivingModsMod.LOG.debug("Player action IPC failed: {}", e.toString());
        }
    }

    private static boolean consumeCurrency(ServerPlayer player, int amount) {
        return consumeItems(player, ResourceItemMapping.currencyItem(), amount);
    }

    private static void refundCurrency(ServerPlayer player, int amount) {
        player.getInventory().add(new ItemStack(ResourceItemMapping.currencyItem(), amount));
    }

    private static boolean consumeItems(ServerPlayer player, Item item, int needed) {
        int counted = 0;
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (stack.is(item)) counted += stack.getCount();
        }
        if (counted < needed) return false;
        int remaining = needed;
        for (int i = 0; i < player.getInventory().getContainerSize() && remaining > 0; i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (!stack.is(item)) continue;
            int take = Math.min(remaining, stack.getCount());
            stack.shrink(take);
            remaining -= take;
        }
        return true;
    }

    private static void removeItems(ServerPlayer player, Item item, int amount) {
        consumeItems(player, item, amount);
    }

    private static void notify(ServerPlayer player, String message) {
        if (player == null || message == null || message.isBlank()) return;
        player.displayClientMessage(Component.literal("[MineLife] " + message), false);
    }

    private static UUID parseUuid(String raw) {
        if (raw == null || raw.isBlank()) return new UUID(0, 0);
        try {
            return UUID.fromString(raw);
        } catch (Exception e) {
            return new UUID(0, 0);
        }
    }
}
