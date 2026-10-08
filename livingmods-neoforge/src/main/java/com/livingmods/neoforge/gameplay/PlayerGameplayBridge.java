package com.livingmods.neoforge.gameplay;

import com.livingmods.common.model.PlayerLegalStatus;
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
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Server-side player command bridge: verified Minecraft facts → typed PLAYER_ACTION → UI feedback.
 * Cooperates with {@link PhysicalInteractionBridge} without duplicating physical outcomes.
 */
public final class PlayerGameplayBridge {
    private static int tickCounter;
    /** Pending market quotes awaiting physical commit (quoteId → gold). */
    private static final ConcurrentHashMap<UUID, PendingQuote> pendingQuotes = new ConcurrentHashMap<>();

    private record PendingQuote(UUID quoteId, UUID settlementId, String resource, int amount, boolean buy, int gold, long createdMs) {
        boolean expired() { return System.currentTimeMillis() - createdMs > 55_000L; }
    }

    private PlayerGameplayBridge() {}

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        if (++tickCounter % 80 != 0) return;
        var server = event.getServer();
        if (server == null) return;
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            FactionDispositionCache.get().tickRefresh(player.getUUID());
            BlockPos pos = player.blockPosition();
            PlayerKnowledgeCache.get().tickRefresh(player.getUUID(), pos.getX(), pos.getY(), pos.getZ());
            // Nearby observation — server-derived position only.
            if (tickCounter % 160 == 0) {
                discoverNearbySettlement(player);
            }
        }
        pendingQuotes.entrySet().removeIf(e -> e.getValue().expired());
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
        // UI/cache hints only — PhysicalInteractionBridge / PhysicalOutcomeApplier own canonical crime.
        if (event.getEntity() instanceof CitizenEntity) {
            notify(player, "Crime recorded: assault/murder of a citizen");
        } else if (event.getEntity() instanceof ProjectedHumanoidEntity projected) {
            UUID faction = projected.factionIdOrNull();
            switch (projected.kind()) {
                case GUARD -> {
                    notify(player, "You are wanted for attacking a guard");
                    if (faction != null) {
                        FactionDispositionCache.get().markWantedLocal(player.getUUID(), faction);
                    }
                }
                case CARAVAN -> notify(player, "Caravan robbery noted by local authorities");
                case SOLDIER -> {
                    notify(player, "Military hostilities escalate");
                    if (faction != null) {
                        FactionDispositionCache.get().markWantedLocal(player.getUUID(), faction);
                    }
                }
                case BANDIT -> notify(player, "Bandit slain");
                default -> {
                }
            }
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
        BlockPos pos = player.blockPosition();
        PlayerActionRequest request = new PlayerActionRequest(
                PlayerActionType.OPEN_INTERACTION,
                player.getUUID(),
                new UUID(0, 0),
                new UUID(0, 0),
                citizen.citizenIdOrNull(),
                citizen.getUUID(),
                new UUID(0, 0),
                pos.getX(), pos.getY(), pos.getZ(),
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
        BlockPos pos = player.blockPosition();
        switch (projected.kind()) {
            case CARAVAN -> {
                String cargo = projected.cargoMeta();
                notify(player, "Caravan" + (cargo.isBlank() ? "" : " cargo: " + cargo)
                        + " — trade/escort via nearby merchants or task journal");
                requestAction(player, PlayerActionType.QUERY_PLAYER_CONTEXT, Map.of("caravanId",
                        projected.canonicalIdOrNull() == null ? "" : projected.canonicalIdOrNull().toString()),
                        pos.getX(), pos.getY(), pos.getZ(),
                        null, null, projected.canonicalIdOrNull());
            }
            case GUARD -> {
                UUID faction = projected.factionIdOrNull();
                String legal = FactionDispositionCache.get().legalStatus(player.getUUID(), faction);
                notify(player, "Guard: local watch. Your legal status here: " + legal);
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
        BlockPos pos = player.blockPosition();
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
                    pos.getX(), pos.getY(), pos.getZ(),
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
                    pos.getX(), pos.getY(), pos.getZ(),
                    session.citizenId(), session.settlementId(), tid,
                    resp -> {
                        notify(player, resp.message());
                        if (resp.success()) {
                            player.connection.send(CitizenInteractionPayloads.TaskJournalUpdate.fromResponse(resp));
                        }
                    });
            return;
        }
        if (choice.equals("JOIN_FACTION") && session.kingdomId() != null) {
            requestAction(player, PlayerActionType.JOIN_FACTION, Map.of(),
                    pos.getX(), pos.getY(), pos.getZ(),
                    session.citizenId(), session.settlementId(), session.kingdomId(),
                    resp -> notify(player, resp.message()));
            return;
        }
        if (choice.equals("LEAVE_FACTION") && session.kingdomId() != null) {
            requestAction(player, PlayerActionType.LEAVE_FACTION, Map.of(),
                    pos.getX(), pos.getY(), pos.getZ(),
                    session.citizenId(), session.settlementId(), session.kingdomId(),
                    resp -> notify(player, resp.message()));
            return;
        }
        if (choice.equals("SURRENDER_TO_GUARDS")) {
            requestAction(player, PlayerActionType.SURRENDER_TO_GUARDS, Map.of(),
                    pos.getX(), pos.getY(), pos.getZ(),
                    session.citizenId(), session.settlementId(), session.kingdomId(),
                    resp -> {
                        notify(player, resp.message());
                        if (resp.success() && session.kingdomId() != null) {
                            try {
                                PlayerLegalStatus st = PlayerLegalStatus.valueOf(
                                        resp.data().getOrDefault("legalStatus", "FINE_OUTSTANDING"));
                                FactionDispositionCache.get().clearWantedLocal(
                                        player.getUUID(), session.kingdomId(), st);
                            } catch (Exception ignored) {
                            }
                        }
                    });
            return;
        }
        if (choice.equals("PAY_FINE")) {
            payFine(player, session);
            return;
        }
        if (choice.equals("MANAGE_REALM") || choice.equals("FOUND_REALM_INFO") || choice.equals("VIEW_KINGDOM")
                || choice.equals("DIPLOMACY") || choice.equals("VIEW_TASKS_HIGH")) {
            requestAction(player, PlayerActionType.QUERY_PLAYER_CONTEXT, Map.of("panel", choice),
                    pos.getX(), pos.getY(), pos.getZ(),
                    session.citizenId(), session.settlementId(), session.kingdomId(),
                    resp -> {
                        PlayerKnowledgeCache.get().applyContext(player.getUUID(), resp.data());
                        player.connection.send(CitizenInteractionPayloads.RealmPanel.fromResponse(resp, choice));
                    });
        }
    }

    /** Session-bound market quote then physical commit. */
    public static void handleMarketTransaction(ServerPlayer player, UUID sessionId, UUID settlementId,
                                               String resource, int amount, boolean buy) {
        amount = Math.max(1, Math.min(64, amount));
        var session = PlayerInteractionSessions.get().require(player, sessionId);
        if (session == null) {
            notify(player, "Market session expired — talk to a merchant again");
            return;
        }
        if (session.settlementId() == null || !session.settlementId().equals(settlementId)) {
            notify(player, "Market settlement mismatch");
            return;
        }
        // Distance: ensure player still near session target entity if present.
        Entity target = player.level() instanceof net.minecraft.server.level.ServerLevel sl
                ? sl.getEntity(session.targetEntityId()) : null;
        if (target != null && player.distanceTo(target) > PlayerInteractionSessions.MAX_DISTANCE) {
            notify(player, "Too far from merchant");
            return;
        }
        ResourceType type;
        try {
            type = ResourceType.valueOf(resource.toUpperCase(java.util.Locale.ROOT));
        } catch (Exception e) {
            notify(player, "Unknown resource");
            return;
        }
        BlockPos pos = player.blockPosition();
        Map<String, String> meta = new LinkedHashMap<>();
        meta.put("resource", type.name());
        meta.put("amount", String.valueOf(amount));
        meta.put("buy", String.valueOf(buy));
        meta.put("direction", buy ? "BUY" : "SELL");
        final int tradeAmount = amount;
        requestAction(player, PlayerActionType.MARKET_QUOTE, meta,
                pos.getX(), pos.getY(), pos.getZ(),
                session.citizenId(), session.settlementId(), session.sessionId(),
                resp -> {
                    if (!resp.success()) {
                        notify(player, resp.message());
                        return;
                    }
                    UUID quoteId = parseUuid(resp.data().get("quoteId"));
                    int gold = 1;
                    try {
                        gold = Integer.parseInt(resp.data().getOrDefault("paymentGold", "1"));
                    } catch (Exception ignored) {
                    }
                    pendingQuotes.put(quoteId, new PendingQuote(
                            quoteId, session.settlementId(), type.name(), tradeAmount, buy, gold,
                            System.currentTimeMillis()));
                    commitMarketQuote(player, session, quoteId, type, tradeAmount, buy, gold);
                });
    }

    private static void commitMarketQuote(
            ServerPlayer player,
            PlayerInteractionSessions.Session session,
            UUID quoteId,
            ResourceType type,
            int amount,
            boolean buy,
            int gold
    ) {
        ServerInventoryTransaction tx = new ServerInventoryTransaction(player);
        Optional<Item> item = ResourceItemMapping.itemFor(type);
        if (item.isEmpty()) {
            notify(player, "Resource not tradeable as items");
            return;
        }
        BlockPos pos = player.blockPosition();
        if (buy) {
            if (!tx.consumeCurrency(gold)) {
                notify(player, "Need " + gold + " gold ingots");
                return;
            }
            if (!tx.give(item.get(), amount)) {
                tx.rollback();
                notify(player, "Inventory full");
                return;
            }
            Map<String, String> meta = new LinkedHashMap<>();
            meta.put("quoteId", quoteId.toString());
            meta.put("serverVerified", "true");
            meta.put("paymentGold", String.valueOf(gold));
            meta.put("resource", type.name());
            meta.put("amount", String.valueOf(amount));
            meta.put("buy", "true");
            requestAction(player, PlayerActionType.MARKET_COMMIT, meta,
                    pos.getX(), pos.getY(), pos.getZ(),
                    session.citizenId(), session.settlementId(), session.sessionId(),
                    resp -> {
                        if (!resp.success()) {
                            tx.rollback();
                            notify(player, resp.message());
                            return;
                        }
                        tx.commit();
                        notify(player, resp.message());
                        refreshMarket(player, session);
                    });
        } else {
            if (!tx.consume(item.get(), amount)) {
                notify(player, "You lack the items");
                return;
            }
            Map<String, String> meta = new LinkedHashMap<>();
            meta.put("quoteId", quoteId.toString());
            meta.put("serverVerified", "true");
            meta.put("inventoryConsumed", "true");
            meta.put("paymentGold", String.valueOf(gold));
            meta.put("resource", type.name());
            meta.put("amount", String.valueOf(amount));
            meta.put("buy", "false");
            requestAction(player, PlayerActionType.MARKET_COMMIT, meta,
                    pos.getX(), pos.getY(), pos.getZ(),
                    session.citizenId(), session.settlementId(), session.sessionId(),
                    resp -> {
                        if (!resp.success()) {
                            tx.rollback();
                            notify(player, resp.message());
                            return;
                        }
                        if (!tx.giveCurrency(gold)) {
                            // Items already consumed; try add gold with room — if fail, restore items.
                            tx.rollback();
                            notify(player, "Inventory full — sale cancelled");
                            return;
                        }
                        tx.commit();
                        notify(player, resp.message());
                        refreshMarket(player, session);
                    });
        }
    }

    public static void handleRealmAction(ServerPlayer player, PlayerActionType type, Map<String, String> meta) {
        BlockPos pos = player.blockPosition();
        requestAction(player, type, meta == null ? Map.of() : meta,
                pos.getX(), pos.getY(), pos.getZ(),
                null, null, null,
                resp -> notify(player, resp.message()));
    }

    public static void handleFoundRealm(ServerPlayer player, String name, String culture) {
        BlockPos pos = player.blockPosition();
        Map<String, String> previewMeta = new LinkedHashMap<>();
        previewMeta.put("realmName", name == null ? "New Realm" : name);
        previewMeta.put("culture", culture == null ? "avalon" : culture);
        requestAction(player, PlayerActionType.PREVIEW_FOUND_REALM, previewMeta,
                pos.getX(), pos.getY(), pos.getZ(),
                null, null, null,
                preview -> {
                    if (!preview.success()) {
                        notify(player, preview.message());
                        return;
                    }
                    int goldCost = 25;
                    try {
                        goldCost = Integer.parseInt(preview.data().getOrDefault("goldCost", "25"));
                    } catch (Exception ignored) {
                    }
                    ServerInventoryTransaction tx = new ServerInventoryTransaction(player);
                    if (!tx.consumeCurrency(goldCost)) {
                        notify(player, "Need " + goldCost + " gold ingots to found a realm");
                        return;
                    }
                    Map<String, String> meta = new LinkedHashMap<>();
                    meta.put("realmName", name == null ? "New Realm" : name);
                    meta.put("culture", culture == null ? "avalon" : culture);
                    meta.put("serverVerified", "true");
                    meta.put("paymentConsumed", "true");
                    meta.put("paymentGold", String.valueOf(goldCost));
                    BlockPos p2 = player.blockPosition();
                    requestAction(player, PlayerActionType.FOUND_REALM, meta,
                            p2.getX(), p2.getY(), p2.getZ(),
                            null, null, null,
                            resp -> {
                                if (!resp.success()) {
                                    tx.rollback();
                                    notify(player, resp.message());
                                    return;
                                }
                                tx.commit();
                                notify(player, resp.message());
                                notify(player, "Founding construction underway");
                                FactionDispositionCache.get().tickRefresh(player.getUUID());
                            });
                });
    }

    public static void handleTaskDelivery(ServerPlayer player, UUID taskId, UUID settlementId, String resource, double amount) {
        boolean ok = PhysicalInteractionBridge.reportTaskDelivery(player, taskId, settlementId, resource, amount);
        notify(player, ok ? "Delivery submitted" : "Delivery failed — check inventory / accepted task");
    }

    public static void handleAbandonTask(ServerPlayer player, UUID taskId) {
        BlockPos pos = player.blockPosition();
        Map<String, String> meta = Map.of("taskId", taskId.toString());
        requestAction(player, PlayerActionType.ABANDON_TASK, meta,
                pos.getX(), pos.getY(), pos.getZ(),
                null, null, taskId,
                resp -> notify(player, resp.message()));
    }

    public static void requestTaskJournal(ServerPlayer player) {
        BlockPos pos = player.blockPosition();
        requestAction(player, PlayerActionType.QUERY_TASK_JOURNAL, Map.of(),
                pos.getX(), pos.getY(), pos.getZ(),
                null, null, null,
                resp -> player.connection.send(CitizenInteractionPayloads.TaskJournalUpdate.fromResponse(resp)));
    }

    public static void requestDashboard(ServerPlayer player) {
        BlockPos pos = player.blockPosition();
        // Dashboard metrics via existing LivingModsNetwork path + player context.
        com.livingmods.neoforge.network.LivingModsNetwork.sendDashboardTo(player);
        requestAction(player, PlayerActionType.QUERY_PLAYER_CONTEXT, Map.of("dashboard", "true"),
                pos.getX(), pos.getY(), pos.getZ(),
                null, null, null,
                resp -> {
                    PlayerKnowledgeCache.get().applyContext(player.getUUID(), resp.data());
                    FactionDispositionCache.get().tickRefresh(player.getUUID());
                    player.connection.send(new CitizenInteractionPayloads.DashboardContext(resp.data()));
                });
    }

    public static void requestRealmPanel(ServerPlayer player, String panel) {
        BlockPos pos = player.blockPosition();
        requestAction(player, PlayerActionType.QUERY_PLAYER_CONTEXT, Map.of("panel", panel == null ? "MANAGE_REALM" : panel),
                pos.getX(), pos.getY(), pos.getZ(),
                null, null, null,
                resp -> {
                    PlayerKnowledgeCache.get().applyContext(player.getUUID(), resp.data());
                    player.connection.send(CitizenInteractionPayloads.RealmPanel.fromResponse(resp,
                            panel == null ? "MANAGE_REALM" : panel));
                });
    }

    /** Server-authoritative nearby discovery only — never uses client map click coords. */
    public static void discoverNearbySettlement(ServerPlayer player) {
        BlockPos pos = player.blockPosition();
        requestAction(player, PlayerActionType.DISCOVER_SETTLEMENT, Map.of(),
                pos.getX(), pos.getY(), pos.getZ(),
                null, null, null,
                resp -> {
                    if (resp.success()) {
                        PlayerKnowledgeCache.get().applyContext(player.getUUID(),
                                Map.of("knownSettlements",
                                        resp.data().getOrDefault("settlementId", "") + "|"
                                                + resp.data().getOrDefault("name", "")
                                                + "||||||||OBSERVED"));
                        notify(player, resp.message());
                    }
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

    private static void refreshMarket(ServerPlayer player, PlayerInteractionSessions.Session session) {
        openMarket(player, session);
    }

    private static void payFine(ServerPlayer player, PlayerInteractionSessions.Session session) {
        BlockPos pos = player.blockPosition();
        requestAction(player, PlayerActionType.QUERY_FINE, Map.of(),
                pos.getX(), pos.getY(), pos.getZ(),
                session.citizenId(), session.settlementId(), session.kingdomId(),
                quote -> {
                    if (!quote.success()) {
                        notify(player, quote.message());
                        return;
                    }
                    int required = 0;
                    try {
                        required = Integer.parseInt(quote.data().getOrDefault("outstandingFine", "0"));
                    } catch (Exception ignored) {
                    }
                    if (required <= 0) {
                        notify(player, "No outstanding fine");
                        return;
                    }
                    ServerInventoryTransaction tx = new ServerInventoryTransaction(player);
                    if (!tx.consumeCurrency(required)) {
                        notify(player, "Need " + required + " gold ingots to pay fine in full");
                        return;
                    }
                    Map<String, String> meta = new LinkedHashMap<>();
                    meta.put("serverVerified", "true");
                    meta.put("amount", String.valueOf(required));
                    BlockPos p2 = player.blockPosition();
                    requestAction(player, PlayerActionType.PAY_FINE, meta,
                            p2.getX(), p2.getY(), p2.getZ(),
                            session.citizenId(), session.settlementId(), session.kingdomId(),
                            resp -> {
                                if (!resp.success()) {
                                    tx.rollback();
                                    notify(player, resp.message());
                                    return;
                                }
                                tx.commit();
                                if (session.kingdomId() != null) {
                                    FactionDispositionCache.get().clearWantedLocal(
                                            player.getUUID(), session.kingdomId(), PlayerLegalStatus.CLEAR);
                                }
                                notify(player, resp.message());
                            });
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
                || type == PlayerActionType.DECLARE_SUPPORT_IN_WAR
                || type == PlayerActionType.QUERY_FINE) {
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
