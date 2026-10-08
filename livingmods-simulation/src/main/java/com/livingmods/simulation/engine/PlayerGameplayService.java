package com.livingmods.simulation.engine;

import com.livingmods.common.culture.CultureDefinition;
import com.livingmods.common.culture.CultureKeys;
import com.livingmods.common.culture.CultureRegistry;
import com.livingmods.common.event.CivilizationEventType;
import com.livingmods.common.event.HistoricalEvent;
import com.livingmods.common.geo.BlockPos2;
import com.livingmods.common.id.CitizenId;
import com.livingmods.common.id.CrimeId;
import com.livingmods.common.id.CultureId;
import com.livingmods.common.id.HistoricalEventId;
import com.livingmods.common.id.KingdomId;
import com.livingmods.common.id.PlayerId;
import com.livingmods.common.id.SettlementId;
import com.livingmods.common.id.TreatyId;
import com.livingmods.common.model.CrimeStatus;
import com.livingmods.common.model.CrimeType;
import com.livingmods.common.model.CrimeVerdict;
import com.livingmods.common.model.DiplomaticRelation;
import com.livingmods.common.model.FactionStanding;
import com.livingmods.common.model.PlayerLegalStatus;
import com.livingmods.common.model.Profession;
import com.livingmods.common.model.ResourceType;
import com.livingmods.common.model.TaskStatus;
import com.livingmods.common.model.TreatyType;
import com.livingmods.protocol.PlayerActionRequest;
import com.livingmods.protocol.PlayerActionResponse;
import com.livingmods.protocol.PlayerActionType;
import com.livingmods.simulation.CanonicalWorldState;
import com.livingmods.simulation.state.CitizenState;
import com.livingmods.simulation.state.CrimeState;
import com.livingmods.simulation.state.DiplomacyState;
import com.livingmods.simulation.state.EmergentTaskState;
import com.livingmods.simulation.state.KingdomState;
import com.livingmods.simulation.state.MarketState;
import com.livingmods.simulation.state.PlayerKnowledgeState;
import com.livingmods.simulation.state.PlayerLegalRecord;
import com.livingmods.simulation.state.SettlementState;
import com.livingmods.simulation.state.StockpileState;
import com.livingmods.simulation.state.WarState;
import com.livingmods.simulation.tick.SimulationContext;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Canonical player-command authority. NeoForge validates Minecraft facts then routes here
 * via typed PlayerActionRequest — never client-direct mutation.
 */
public final class PlayerGameplayService {
    public static final int MAX_INTERACT_DISTANCE = 8;
    public static final int MAX_TRADE_AMOUNT = 64;
    /** Player currency authority: ResourceType.GOLD priced market trades; Minecraft GOLD_INGOT physical. */
    public static final ResourceType CURRENCY = ResourceType.GOLD;

    private final PlayerSystemsEngine playerSystems = new PlayerSystemsEngine();
    private final DialogueEngine dialogue = new DialogueEngine();
    private final EmergentTaskEngine tasks = new EmergentTaskEngine();
    private final CultureRegistry cultures = new CultureRegistry();
    private final MarketQuoteStore marketQuotes = new MarketQuoteStore();

    public PlayerActionResponse handle(
            CanonicalWorldState state,
            PlayerActionRequest request,
            SimulationContext ctx
    ) {
        if (request == null || request.action() == null) {
            return PlayerActionResponse.fail(PlayerActionType.TALK, "Missing action");
        }
        PlayerId player = PlayerId.of(request.playerId() == null ? new UUID(0, 0) : request.playerId());
        if (isZero(player) && request.action() != PlayerActionType.QUERY_PLAYER_CONTEXT) {
            return PlayerActionResponse.fail(request.action(), "Invalid player identity");
        }
        return switch (request.action()) {
            case OPEN_INTERACTION, TALK -> openInteraction(state, player, request, ctx);
            case REQUEST_DIALOGUE_TOPIC -> dialogueTopic(state, player, request);
            case ACCEPT_TASK -> acceptTask(state, player, request, ctx);
            case ABANDON_TASK -> abandonTask(state, player, request);
            case BUY_RESOURCE -> buyResource(state, player, request, ctx);
            case SELL_RESOURCE -> sellResource(state, player, request, ctx);
            case JOIN_FACTION -> joinFaction(state, player, request, ctx);
            case LEAVE_FACTION -> leaveFaction(state, player, request, ctx);
            case FOUND_REALM -> foundRealm(state, player, request, ctx);
            case SET_TAX_POLICY -> setTax(state, player, request, ctx);
            case SET_DEFENSE_POLICY -> setDefense(state, player, request, ctx);
            case SET_FOOD_POLICY -> setFood(state, player, request, ctx);
            case SET_CONSTRUCTION_POLICY -> setConstruction(state, player, request, ctx);
            case SET_MIGRATION_POLICY -> setMigration(state, player, request, ctx);
            case REQUEST_DIPLOMATIC_ACTION -> diplomacyAction(state, player, request, ctx);
            case DECLARE_SUPPORT_IN_WAR -> declareWarSupport(state, player, request, ctx);
            case PAY_FINE -> payFine(state, player, request, ctx);
            case SURRENDER_TO_GUARDS -> surrender(state, player, request, ctx);
            case DISCOVER_SETTLEMENT -> discoverSettlement(state, player, request);
            case QUERY_PLAYER_CONTEXT -> queryContext(state, player, request);
            case MARKET_QUOTE -> marketQuote(state, player, request, ctx);
            case MARKET_COMMIT -> marketCommit(state, player, request, ctx);
            case QUERY_TASK_JOURNAL -> queryTaskJournal(state, player, request);
            case PREVIEW_FOUND_REALM -> previewFoundRealm(state, player, request);
            case QUERY_FINE -> queryFine(state, player, request);
        };
    }

    private PlayerActionResponse openInteraction(
            CanonicalWorldState state,
            PlayerId player,
            PlayerActionRequest request,
            SimulationContext ctx
    ) {
        CitizenId citizenId = CitizenId.of(nz(request.citizenId()));
        CitizenState citizen = state.citizens().get(citizenId);
        if (citizen == null || !citizen.alive()) {
            return PlayerActionResponse.fail(request.action(), "Citizen not found");
        }
        SettlementState settlement = state.settlements().get(citizen.settlementId());
        if (settlement == null) {
            return PlayerActionResponse.fail(request.action(), "Settlement missing");
        }
        // Distance: prefer settlement center when entity coords unavailable; NeoForge also validates.
        if (request.blockX() != 0 || request.blockZ() != 0) {
            double dx = settlement.center().x() - request.blockX();
            double dz = settlement.center().z() - request.blockZ();
            // Soft check — NeoForge distance is authoritative for entity interact.
            if (dx * dx + dz * dz > 512 * 512) {
                return PlayerActionResponse.fail(request.action(), "Too far from settlement");
            }
        }

        KingdomId kingdom = settlement.ownerKingdom().orElse(null);
        state.playerReputation().knowledge().discoverSettlement(
                player, settlement.id(), PlayerKnowledgeState.KnowledgeLevel.OBSERVED);
        if (kingdom != null) {
            state.playerReputation().knowledge().discoverKingdom(
                    player, kingdom, PlayerKnowledgeState.KnowledgeLevel.KNOWN);
        }

        String attitude = kingdom == null ? "Neutral"
                : state.playerReputation().attitudeLabel(player, kingdom);
        String standing = kingdom == null ? "NEUTRAL"
                : state.playerReputation().standing(player, kingdom).name();
        double reputation = kingdom == null ? 0.0
                : state.playerReputation().reputation(player, kingdom);

        List<String> actions = contextualActions(state, player, citizen, settlement, kingdom);
        List<String> lines = new ArrayList<>();
        for (var line : dialogue.respond(state, citizenId, "greeting", player)) {
            lines.add(line.speaker() + ": " + line.text());
        }

        // Discover nearby open tasks through conversation.
        List<String> taskIds = new ArrayList<>();
        for (EmergentTaskState task : state.emergentTasks().values()) {
            if (!task.open()) continue;
            if (!task.settlementId().equals(settlement.id())) continue;
            if (!taskVisibleTo(state, player, citizen, task)) continue;
            task.markDiscovered();
            taskIds.add(task.id().toString());
            if (taskIds.size() >= 4) break;
        }

        UUID sessionId = UUID.nameUUIDFromBytes(
                (player.value() + "|" + citizenId.value() + "|" + state.saveRevision()).getBytes());

        Map<String, String> data = new LinkedHashMap<>();
        data.put("citizenId", citizenId.value().toString());
        data.put("citizenName", citizen.givenName() + " " + citizen.familyName());
        data.put("profession", citizen.profession().name());
        data.put("settlementId", settlement.id().value().toString());
        data.put("settlementName", settlement.name());
        data.put("kingdomId", kingdom == null ? "" : kingdom.value().toString());
        data.put("kingdomName", kingdom == null ? "Independent"
                : Optional.ofNullable(state.kingdoms().get(kingdom)).map(KingdomState::name).orElse("Unknown"));
        data.put("attitude", attitude);
        data.put("standing", standing);
        data.put("isRuler", String.valueOf(citizen.ruler() || citizen.profession() == Profession.RULER));
        data.put("taskIds", String.join(",", taskIds));
        data.put("legalStatus", legalStatusLabel(state, player, kingdom, settlement.id()));
        data.put("sessionRevision", String.valueOf(state.saveRevision()));

        playerSystems.recordPlayerAction(state, player,
                kingdom == null ? KingdomId.of(new UUID(0, 1)) : kingdom, 0.0, ctx);

        return new PlayerActionResponse(
                true, "ok", "Conversation opened",
                request.action(), sessionId, reputation, standing, actions, lines, data);
    }

    private List<String> contextualActions(
            CanonicalWorldState state,
            PlayerId player,
            CitizenState citizen,
            SettlementState settlement,
            KingdomId kingdom
    ) {
        List<String> actions = new ArrayList<>();
        actions.add("TALK:GREETING");
        actions.add("TALK:SETTLEMENT");
        actions.add("TALK:WORK");
        actions.add("TALK:RUMORS");
        actions.add("TALK:PROBLEMS");
        if (settlement.hunger() > 0.25 || settlement.unrest() > 0.3) {
            actions.add("TALK:FOOD");
        }
        boolean warNearby = false;
        if (kingdom != null) {
            for (WarState w : state.wars().values()) {
                if (w.active() && (w.aggressor().equals(kingdom) || w.defender().equals(kingdom))) {
                    warNearby = true;
                    break;
                }
            }
        }
        if (warNearby) actions.add("TALK:WAR");
        actions.add("TALK:RULER");

        Profession p = citizen.profession();
        if (p == Profession.MERCHANT || p == Profession.TRADER) {
            actions.add("OPEN_MARKET");
            actions.add("TALK:TRADE");
        }
        if (p == Profession.GUARD || p == Profession.SOLDIER) {
            actions.add("TALK:CRIME");
            actions.add("TALK:GUARDS");
            PlayerLegalRecord legal = kingdom == null ? null
                    : state.playerReputation().legalRecord(player, kingdom);
            if (legal != null && legal.isWantedOrWorse()) {
                actions.add("SURRENDER_TO_GUARDS");
                if (legal.outstandingFine() > 0) actions.add("PAY_FINE");
            }
        }
        if (p == Profession.GOVERNMENT_OFFICIAL || p == Profession.NOBLE || p == Profession.RULER
                || citizen.ruler()) {
            actions.add("VIEW_KINGDOM");
            if (kingdom != null) {
                FactionStanding standing = state.playerReputation().standing(player, kingdom);
                double rep = state.playerReputation().reputation(player, kingdom);
                if (!standing.atLeast(FactionStanding.CITIZEN) && rep >= 0.15
                        && !hasSevereCrime(state, player, kingdom)) {
                    actions.add("JOIN_FACTION");
                }
                if (standing.atLeast(FactionStanding.CITIZEN) && standing != FactionStanding.RULER) {
                    actions.add("LEAVE_FACTION");
                }
                if (standing.atLeast(FactionStanding.TRUSTED) || citizen.ruler()) {
                    actions.add("VIEW_TASKS_HIGH");
                }
            }
            KingdomId ruled = state.playerReputation().ruledKingdom(player);
            if (ruled != null && ruled.equals(kingdom)) {
                actions.add("MANAGE_REALM");
                actions.add("DIPLOMACY");
            }
        }
        if (p == Profession.HEALER) {
            actions.add("TALK:HEALTH");
        }
        // Tasks available via this citizen
        for (EmergentTaskState task : state.emergentTasks().values()) {
            if (!task.available()) continue;
            if (!task.settlementId().equals(settlement.id())) continue;
            if (!taskVisibleTo(state, player, citizen, task)) continue;
            actions.add("ACCEPT_TASK:" + task.id());
            break;
        }
        if (state.playerReputation().ruledKingdom(player) == null
                && (p == Profession.GOVERNMENT_OFFICIAL || citizen.ruler() || p == Profession.NOBLE)) {
            actions.add("FOUND_REALM_INFO");
        }
        actions.add("LEAVE");
        return actions;
    }

    private boolean taskVisibleTo(
            CanonicalWorldState state,
            PlayerId player,
            CitizenState citizen,
            EmergentTaskState task
    ) {
        Profession p = citizen.profession();
        return switch (task.type()) {
            case FOOD_DELIVERY, MEDICINE_DELIVERY ->
                    p == Profession.MERCHANT || p == Profession.TRADER || p == Profession.FARMER
                            || p == Profession.GOVERNMENT_OFFICIAL || p == Profession.HEALER
                            || citizen.ruler();
            case BANDIT_REMOVAL ->
                    p == Profession.GUARD || p == Profession.SOLDIER || p == Profession.GOVERNMENT_OFFICIAL
                            || citizen.ruler();
            case ESCORT, MISSING_CARAVAN ->
                    p == Profession.MERCHANT || p == Profession.TRADER || p == Profession.GUARD
                            || p == Profession.SAILOR;
            case CONSTRUCTION_RESOURCES ->
                    p == Profession.BUILDER || p == Profession.GOVERNMENT_OFFICIAL || citizen.ruler();
            case DIPLOMATIC_DELIVERY ->
                    p == Profession.GOVERNMENT_OFFICIAL || p == Profession.NOBLE || citizen.ruler()
                            || state.playerReputation().standing(player,
                            settlementKingdom(state, task.settlementId())).atLeast(FactionStanding.TRUSTED);
        };
    }

    private KingdomId settlementKingdom(CanonicalWorldState state, SettlementId sid) {
        SettlementState s = state.settlements().get(sid);
        return s == null ? KingdomId.of(new UUID(0, 1)) : s.ownerKingdom().orElse(KingdomId.of(new UUID(0, 1)));
    }

    private PlayerActionResponse dialogueTopic(
            CanonicalWorldState state,
            PlayerId player,
            PlayerActionRequest request
    ) {
        CitizenId citizenId = CitizenId.of(nz(request.citizenId()));
        String topic = request.meta("topic");
        if (topic.isBlank()) topic = request.meta("intent");
        if (topic.isBlank()) topic = "greeting";
        List<String> lines = new ArrayList<>();
        for (var line : dialogue.respond(state, citizenId, topic, player)) {
            lines.add(line.speaker() + ": " + line.text());
        }
        Map<String, String> data = new LinkedHashMap<>();
        data.put("topic", topic);
        data.put("citizenId", citizenId.value().toString());
        return new PlayerActionResponse(
                true, "ok", "Dialogue",
                PlayerActionType.REQUEST_DIALOGUE_TOPIC, request.sessionId(),
                0, "NEUTRAL", List.of(), lines, data);
    }

    private PlayerActionResponse acceptTask(
            CanonicalWorldState state,
            PlayerId player,
            PlayerActionRequest request,
            SimulationContext ctx
    ) {
        UUID taskId = nz(request.targetId());
        if (taskId.getMostSignificantBits() == 0 && taskId.getLeastSignificantBits() == 0) {
            String raw = request.meta("taskId");
            if (!raw.isBlank()) {
                try { taskId = UUID.fromString(raw); } catch (Exception ignored) {}
            }
        }
        EmergentTaskState task = state.emergentTasks().get(taskId);
        if (task == null) return PlayerActionResponse.fail(PlayerActionType.ACCEPT_TASK, "Task not found");
        if (!task.available() && !task.assignedTo(player)) {
            return PlayerActionResponse.fail(PlayerActionType.ACCEPT_TASK, "Task not available");
        }
        // Eligibility: reputation / crime
        KingdomId kingdom = settlementKingdom(state, task.settlementId());
        if (hasSevereCrime(state, player, kingdom)
                && state.playerReputation().reputation(player, kingdom) < 0) {
            return PlayerActionResponse.fail(PlayerActionType.ACCEPT_TASK, "Wanted — tasks unavailable");
        }
        if (!task.accept(player, ctx.time())) {
            return PlayerActionResponse.fail(PlayerActionType.ACCEPT_TASK, "Could not accept task");
        }
        state.playerReputation().knowledge().markTask(player, task.id());
        String camp = task.problemTags().get("campId");
        if (camp != null && !camp.isBlank()) {
            try {
                state.playerReputation().knowledge().discoverCamp(player, UUID.fromString(camp));
            } catch (Exception ignored) {}
        }
        Map<String, String> data = new LinkedHashMap<>();
        data.put("taskId", task.id().toString());
        data.put("title", task.title());
        data.put("type", task.type().name());
        data.put("taskType", task.type().name());
        data.put("settlementId", task.settlementId().value().toString());
        SettlementState dest = state.settlements().get(task.settlementId());
        data.put("settlementName", dest == null ? "" : dest.name());
        data.put("status", task.status().name());
        data.put("description", task.description());
        data.put("resource", task.problemTags().getOrDefault("resource", ""));
        data.put("amount", task.problemTags().getOrDefault("amount", ""));
        data.put("wood", task.problemTags().getOrDefault("wood", ""));
        data.put("stone", task.problemTags().getOrDefault("stone", ""));
        data.put("campId", task.problemTags().getOrDefault("campId", ""));
        data.put("shipment", task.problemTags().getOrDefault("shipment", ""));
        // Also emit packed tasks row for typed journal decode.
        data.put("tasks", task.id() + "|" + task.status().name() + "|" + task.type().name()
                + "|" + sanitizePipe(task.title()) + "|" + sanitizePipe(task.description())
                + "|" + task.settlementId().value() + "|" + sanitizePipe(dest == null ? "" : dest.name())
                + "|" + task.problemTags().getOrDefault("resource", "")
                + "|" + task.problemTags().getOrDefault("amount", "")
                + "|" + task.problemTags().getOrDefault("wood", "")
                + "|" + task.problemTags().getOrDefault("stone", "")
                + "|" + task.problemTags().getOrDefault("campId", "")
                + "|" + task.problemTags().getOrDefault("shipment", "")
                + "|true|");
        state.appendHistory(new HistoricalEvent(
                HistoricalEventId.deterministic(state.seed(), state.history().size()),
                CivilizationEventType.PLAYER_REPUTATION_CHANGED,
                ctx.time(),
                "Task accepted",
                player + " accepted " + task.title(),
                Optional.empty(),
                Map.of("taskId", task.id().toString(), "player", player.toString())
        ));
        return PlayerActionResponse.ok(PlayerActionType.ACCEPT_TASK, "Task accepted: " + task.title(), data);
    }

    private PlayerActionResponse abandonTask(
            CanonicalWorldState state,
            PlayerId player,
            PlayerActionRequest request
    ) {
        UUID taskId = nz(request.targetId());
        String raw = request.meta("taskId");
        if (!raw.isBlank()) {
            try { taskId = UUID.fromString(raw); } catch (Exception ignored) {}
        }
        EmergentTaskState task = state.emergentTasks().get(taskId);
        if (task == null || !task.assignedTo(player)) {
            return PlayerActionResponse.fail(PlayerActionType.ABANDON_TASK, "No such accepted task");
        }
        task.abandon(player);
        KingdomId kingdom = settlementKingdom(state, task.settlementId());
        state.playerReputation().adjust(player, kingdom, -0.03);
        return PlayerActionResponse.ok(PlayerActionType.ABANDON_TASK, "Task abandoned",
                Map.of("taskId", task.id().toString(), "status", task.status().name()));
    }

    /**
     * Legacy buy path retained for compatibility; prefer MARKET_QUOTE + MARKET_COMMIT.
     * Requires serverVerified and exact paymentGold matching quote semantics.
     */
    private PlayerActionResponse buyResource(
            CanonicalWorldState state,
            PlayerId player,
            PlayerActionRequest request,
            SimulationContext ctx
    ) {
        if (!"true".equalsIgnoreCase(request.meta("serverVerified"))) {
            return PlayerActionResponse.fail(PlayerActionType.BUY_RESOURCE, "Unverified transaction");
        }
        SettlementId sid = SettlementId.of(nz(request.settlementId()));
        ResourceType resource = parseResource(request.meta("resource"));
        int amount = Math.max(1, Math.min(MAX_TRADE_AMOUNT, request.metaInt("amount", 1)));
        if (resource == null) {
            return PlayerActionResponse.fail(PlayerActionType.BUY_RESOURCE, "Unknown resource");
        }
        MarketState market = state.markets().get(sid);
        StockpileState stock = state.stockpiles().get(sid);
        if (market == null || stock == null) {
            return PlayerActionResponse.fail(PlayerActionType.BUY_RESOURCE, "No market");
        }
        double available = stock.get(resource);
        if (available + 1e-6 < amount) {
            return PlayerActionResponse.fail(PlayerActionType.BUY_RESOURCE, "Insufficient stock");
        }
        KingdomId kingdom = settlementKingdom(state, sid);
        double priceMult = priceMultiplier(state, player, kingdom);
        double unitPrice = market.price(resource) * priceMult;
        int goldIngots = Math.max(1, (int) Math.ceil(unitPrice * amount));
        int claimedGold = request.metaInt("paymentGold", -1);
        if (claimedGold != goldIngots) {
            return PlayerActionResponse.fail(PlayerActionType.BUY_RESOURCE,
                    "Payment mismatch — re-quote required");
        }
        stock.add(resource, -amount);
        stock.add(CURRENCY, goldIngots);
        SettlementState settlement = state.settlements().get(sid);
        if (settlement != null && settlement.ownerKingdom().isPresent()) {
            KingdomState k = state.kingdoms().get(settlement.ownerKingdom().get());
            if (k != null) k.setTreasury(k.treasury() + goldIngots * 0.2);
        }
        playerSystems.recordPlayerAction(state, player, kingdom, 0.02, ctx);
        Map<String, String> data = new LinkedHashMap<>();
        data.put("resource", resource.name());
        data.put("amount", String.valueOf(amount));
        data.put("unitPrice", String.format(Locale.ROOT, "%.3f", unitPrice));
        data.put("total", String.valueOf(goldIngots));
        data.put("paymentGold", String.valueOf(goldIngots));
        data.put("stock", String.format(Locale.ROOT, "%.1f", stock.get(resource)));
        return PlayerActionResponse.ok(PlayerActionType.BUY_RESOURCE, "Purchased " + amount + " " + resource, data);
    }

    private PlayerActionResponse sellResource(
            CanonicalWorldState state,
            PlayerId player,
            PlayerActionRequest request,
            SimulationContext ctx
    ) {
        if (!"true".equalsIgnoreCase(request.meta("serverVerified"))
                || !"true".equalsIgnoreCase(request.meta("inventoryConsumed"))) {
            return PlayerActionResponse.fail(PlayerActionType.SELL_RESOURCE, "Unverified sale");
        }
        SettlementId sid = SettlementId.of(nz(request.settlementId()));
        ResourceType resource = parseResource(request.meta("resource"));
        int amount = Math.max(1, Math.min(MAX_TRADE_AMOUNT, request.metaInt("amount", 1)));
        if (resource == null) {
            return PlayerActionResponse.fail(PlayerActionType.SELL_RESOURCE, "Unknown resource");
        }
        MarketState market = state.markets().computeIfAbsent(sid, MarketState::new);
        StockpileState stock = state.stockpiles().computeIfAbsent(sid, StockpileState::new);
        KingdomId kingdom = settlementKingdom(state, sid);
        double priceMult = priceMultiplier(state, player, kingdom);
        double unitPrice = market.price(resource) * 0.7 / priceMult;
        int goldIngots = Math.max(1, (int) Math.ceil(unitPrice * amount));
        KingdomState k = state.kingdoms().get(kingdom);
        if (k != null && k.treasury() < goldIngots * 0.1 && stock.get(CURRENCY) < goldIngots) {
            return PlayerActionResponse.fail(PlayerActionType.SELL_RESOURCE, "Market cannot pay");
        }
        stock.add(resource, amount);
        stock.add(CURRENCY, -Math.min(stock.get(CURRENCY), goldIngots));
        if (k != null) k.setTreasury(Math.max(0, k.treasury() - goldIngots * 0.1));
        playerSystems.recordPlayerAction(state, player, kingdom, 0.03, ctx);
        Map<String, String> data = new LinkedHashMap<>();
        data.put("resource", resource.name());
        data.put("amount", String.valueOf(amount));
        data.put("unitPrice", String.format(Locale.ROOT, "%.3f", unitPrice));
        data.put("total", String.valueOf(goldIngots));
        data.put("paymentGold", String.valueOf(goldIngots));
        return PlayerActionResponse.ok(PlayerActionType.SELL_RESOURCE, "Sold " + amount + " " + resource, data);
    }

    private PlayerActionResponse marketQuote(
            CanonicalWorldState state,
            PlayerId player,
            PlayerActionRequest request,
            SimulationContext ctx
    ) {
        SettlementId sid = SettlementId.of(nz(request.settlementId()));
        ResourceType resource = parseResource(request.meta("resource"));
        int amount = Math.max(1, Math.min(MAX_TRADE_AMOUNT, request.metaInt("amount", 1)));
        boolean buy = !"false".equalsIgnoreCase(request.meta("buy"))
                && !"SELL".equalsIgnoreCase(request.meta("direction"));
        if (resource == null) {
            return PlayerActionResponse.fail(PlayerActionType.MARKET_QUOTE, "Unknown resource");
        }
        MarketState market = state.markets().get(sid);
        StockpileState stock = state.stockpiles().get(sid);
        if (market == null || stock == null) {
            return PlayerActionResponse.fail(PlayerActionType.MARKET_QUOTE, "No market");
        }
        KingdomId kingdom = settlementKingdom(state, sid);
        double priceMult = priceMultiplier(state, player, kingdom);
        double unitPrice;
        int goldIngots;
        if (buy) {
            if (stock.get(resource) + 1e-6 < amount) {
                return PlayerActionResponse.fail(PlayerActionType.MARKET_QUOTE, "Insufficient stock");
            }
            unitPrice = market.price(resource) * priceMult;
            goldIngots = Math.max(1, (int) Math.ceil(unitPrice * amount));
        } else {
            unitPrice = market.price(resource) * 0.7 / priceMult;
            goldIngots = Math.max(1, (int) Math.ceil(unitPrice * amount));
            KingdomState k = state.kingdoms().get(kingdom);
            if (k != null && k.treasury() < goldIngots * 0.1 && stock.get(CURRENCY) < goldIngots) {
                return PlayerActionResponse.fail(PlayerActionType.MARKET_QUOTE, "Market cannot pay");
            }
        }
        UUID quoteId = UUID.nameUUIDFromBytes(
                (player.value() + "|" + sid + "|" + resource + "|" + amount + "|" + buy + "|" + ctx.time().absoluteTicks())
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        MarketQuoteStore.Quote quote = new MarketQuoteStore.Quote(
                quoteId, player, sid, resource, amount, buy, goldIngots, unitPrice,
                System.currentTimeMillis(), ctx.time().absoluteTicks());
        marketQuotes.put(quote);
        Map<String, String> data = new LinkedHashMap<>();
        data.put("quoteId", quoteId.toString());
        data.put("resource", resource.name());
        data.put("amount", String.valueOf(amount));
        data.put("buy", String.valueOf(buy));
        data.put("paymentGold", String.valueOf(goldIngots));
        data.put("unitPrice", String.format(Locale.ROOT, "%.3f", unitPrice));
        data.put("settlementId", sid.value().toString());
        data.put("stock", String.format(Locale.ROOT, "%.1f", stock.get(resource)));
        data.put("expiresMs", "60000");
        return PlayerActionResponse.ok(PlayerActionType.MARKET_QUOTE,
                (buy ? "Buy" : "Sell") + " quote: " + goldIngots + " gold", data);
    }

    private PlayerActionResponse marketCommit(
            CanonicalWorldState state,
            PlayerId player,
            PlayerActionRequest request,
            SimulationContext ctx
    ) {
        if (!"true".equalsIgnoreCase(request.meta("serverVerified"))) {
            return PlayerActionResponse.fail(PlayerActionType.MARKET_COMMIT, "Unverified transaction");
        }
        UUID quoteId;
        try {
            quoteId = UUID.fromString(request.meta("quoteId"));
        } catch (Exception e) {
            return PlayerActionResponse.fail(PlayerActionType.MARKET_COMMIT, "Missing quote");
        }
        MarketQuoteStore.Quote quote = marketQuotes.consume(quoteId, player);
        if (quote == null) {
            return PlayerActionResponse.fail(PlayerActionType.MARKET_COMMIT, "Quote expired or already used");
        }
        SettlementId sid = quote.settlementId();
        UUID reqSid = nz(request.settlementId());
        if (!sid.value().equals(reqSid)
                && !(reqSid.getMostSignificantBits() == 0L && reqSid.getLeastSignificantBits() == 0L)) {
            return PlayerActionResponse.fail(PlayerActionType.MARKET_COMMIT, "Settlement mismatch");
        }
        ResourceType resource = quote.resource();
        int amount = quote.amount();
        int goldIngots = quote.goldIngots();
        int claimed = request.metaInt("paymentGold", -1);
        if (claimed != goldIngots) {
            return PlayerActionResponse.fail(PlayerActionType.MARKET_COMMIT, "Payment mismatch");
        }
        MarketState market = state.markets().get(sid);
        StockpileState stock = state.stockpiles().get(sid);
        if (market == null || stock == null) {
            return PlayerActionResponse.fail(PlayerActionType.MARKET_COMMIT, "No market");
        }
        KingdomId kingdom = settlementKingdom(state, sid);
        if (quote.buy()) {
            if (stock.get(resource) + 1e-6 < amount) {
                return PlayerActionResponse.fail(PlayerActionType.MARKET_COMMIT, "Stock changed");
            }
            stock.add(resource, -amount);
            stock.add(CURRENCY, goldIngots);
            SettlementState settlement = state.settlements().get(sid);
            if (settlement != null && settlement.ownerKingdom().isPresent()) {
                KingdomState k = state.kingdoms().get(settlement.ownerKingdom().get());
                if (k != null) k.setTreasury(k.treasury() + goldIngots * 0.2);
            }
            playerSystems.recordPlayerAction(state, player, kingdom, 0.02, ctx);
            Map<String, String> data = tradeData(resource, amount, quote.unitPrice(), goldIngots, stock);
            data.put("buy", "true");
            return PlayerActionResponse.ok(PlayerActionType.MARKET_COMMIT,
                    "Purchased " + amount + " " + resource, data);
        }
        if (!"true".equalsIgnoreCase(request.meta("inventoryConsumed"))) {
            return PlayerActionResponse.fail(PlayerActionType.MARKET_COMMIT, "Inventory not consumed");
        }
        KingdomState k = state.kingdoms().get(kingdom);
        if (k != null && k.treasury() < goldIngots * 0.1 && stock.get(CURRENCY) < goldIngots) {
            return PlayerActionResponse.fail(PlayerActionType.MARKET_COMMIT, "Market cannot pay");
        }
        stock.add(resource, amount);
        stock.add(CURRENCY, -Math.min(stock.get(CURRENCY), goldIngots));
        if (k != null) k.setTreasury(Math.max(0, k.treasury() - goldIngots * 0.1));
        playerSystems.recordPlayerAction(state, player, kingdom, 0.03, ctx);
        Map<String, String> data = tradeData(resource, amount, quote.unitPrice(), goldIngots, stock);
        data.put("buy", "false");
        return PlayerActionResponse.ok(PlayerActionType.MARKET_COMMIT,
                "Sold " + amount + " " + resource, data);
    }

    private static Map<String, String> tradeData(
            ResourceType resource, int amount, double unitPrice, int gold, StockpileState stock
    ) {
        Map<String, String> data = new LinkedHashMap<>();
        data.put("resource", resource.name());
        data.put("amount", String.valueOf(amount));
        data.put("unitPrice", String.format(Locale.ROOT, "%.3f", unitPrice));
        data.put("total", String.valueOf(gold));
        data.put("paymentGold", String.valueOf(gold));
        data.put("stock", String.format(Locale.ROOT, "%.1f", stock.get(resource)));
        return data;
    }

    private double priceMultiplier(CanonicalWorldState state, PlayerId player, KingdomId kingdom) {
        double rep = state.playerReputation().reputation(player, kingdom);
        if (rep >= 0.5) return 0.9;
        if (rep <= -0.3) return 1.25;
        return 1.0;
    }

    private PlayerActionResponse joinFaction(
            CanonicalWorldState state,
            PlayerId player,
            PlayerActionRequest request,
            SimulationContext ctx
    ) {
        KingdomId kingdom = KingdomId.of(nz(request.kingdomId()));
        if (isZeroKingdom(kingdom)) {
            String raw = request.meta("kingdomId");
            if (!raw.isBlank()) {
                try { kingdom = KingdomId.of(UUID.fromString(raw)); } catch (Exception ignored) {}
            }
        }
        KingdomState k = state.kingdoms().get(kingdom);
        if (k == null) return PlayerActionResponse.fail(PlayerActionType.JOIN_FACTION, "Unknown kingdom");
        if (hasSevereCrime(state, player, kingdom)) {
            return PlayerActionResponse.fail(PlayerActionType.JOIN_FACTION, "Unresolved crimes block membership");
        }
        double rep = state.playerReputation().reputation(player, kingdom);
        if (rep < 0.15) {
            return PlayerActionResponse.fail(PlayerActionType.JOIN_FACTION, "Reputation too low (need Friendly)");
        }
        FactionStanding current = state.playerReputation().standing(player, kingdom);
        if (current.atLeast(FactionStanding.CITIZEN)) {
            return PlayerActionResponse.fail(PlayerActionType.JOIN_FACTION, "Already a member");
        }
        playerSystems.setFactionMembership(state, player, kingdom, FactionStanding.CITIZEN);
        state.playerReputation().knowledge().discoverKingdom(
                player, kingdom, PlayerKnowledgeState.KnowledgeLevel.KNOWN);
        for (SettlementId sid : k.settlementIds()) {
            state.playerReputation().knowledge().discoverSettlement(
                    player, sid, PlayerKnowledgeState.KnowledgeLevel.KNOWN);
        }
        state.appendHistory(new HistoricalEvent(
                HistoricalEventId.deterministic(state.seed(), state.history().size()),
                CivilizationEventType.PLAYER_REPUTATION_CHANGED,
                ctx.time(),
                "Joined faction",
                player + " became a citizen of " + k.name(),
                Optional.empty(),
                Map.of("player", player.toString(), "kingdom", kingdom.toString())
        ));
        return PlayerActionResponse.ok(PlayerActionType.JOIN_FACTION,
                "You are now a citizen of " + k.name(),
                Map.of("standing", "CITIZEN", "kingdom", k.name(), "attitude",
                        state.playerReputation().attitudeLabel(player, kingdom)));
    }

    private PlayerActionResponse leaveFaction(
            CanonicalWorldState state,
            PlayerId player,
            PlayerActionRequest request,
            SimulationContext ctx
    ) {
        KingdomId kingdom = KingdomId.of(nz(request.kingdomId()));
        KingdomState k = state.kingdoms().get(kingdom);
        if (k == null) return PlayerActionResponse.fail(PlayerActionType.LEAVE_FACTION, "Unknown kingdom");
        FactionStanding standing = state.playerReputation().standing(player, kingdom);
        if (standing == FactionStanding.RULER) {
            return PlayerActionResponse.fail(PlayerActionType.LEAVE_FACTION, "Abdication not supported here");
        }
        if (!standing.atLeast(FactionStanding.CITIZEN)) {
            return PlayerActionResponse.fail(PlayerActionType.LEAVE_FACTION, "Not a member");
        }
        state.playerReputation().clearStanding(player, kingdom);
        state.playerReputation().adjust(player, kingdom, -0.2);
        state.appendHistory(new HistoricalEvent(
                HistoricalEventId.deterministic(state.seed(), state.history().size()),
                CivilizationEventType.PLAYER_REPUTATION_CHANGED,
                ctx.time(),
                "Left faction",
                player + " left " + k.name(),
                Optional.empty(),
                Map.of("player", player.toString(), "kingdom", kingdom.toString())
        ));
        return PlayerActionResponse.ok(PlayerActionType.LEAVE_FACTION, "You left " + k.name(),
                Map.of("standing", state.playerReputation().standing(player, kingdom).name()));
    }

    private PlayerActionResponse previewFoundRealm(
            CanonicalWorldState state,
            PlayerId player,
            PlayerActionRequest request
    ) {
        String name = request.meta("realmName");
        if (name.isBlank()) name = "New Realm";
        String cultureKey = CultureKeys.sanitize(request.meta("culture"));
        BlockPos2 center = BlockPos2.of(request.blockX(), request.blockZ());
        var req = PlayerSystemsEngine.FoundationRequirements.defaults();
        SettlementState near = nearestSettlement(state, center, 128);
        if (near != null && near.ownerKingdom().isPresent()) {
            KingdomId owner = near.ownerKingdom().get();
            if (state.playerReputation().reputation(player, owner) < 0
                    || hasSevereCrime(state, player, owner)) {
                return PlayerActionResponse.fail(PlayerActionType.PREVIEW_FOUND_REALM,
                        "Cannot found a realm while hostile to nearby kingdom");
            }
        }
        for (SettlementState s : state.settlements().values()) {
            if (s.center().distanceTo(center) < 48 && s.ownerKingdom().isPresent()) {
                return PlayerActionResponse.fail(PlayerActionType.PREVIEW_FOUND_REALM,
                        "Too close to claimed territory");
            }
        }
        var preview = playerSystems.previewFoundation(state, player, name, center, req);
        Map<String, String> data = new LinkedHashMap<>();
        data.put("eligible", String.valueOf(preview.eligible()));
        data.put("goldCost", String.valueOf(preview.goldCost()));
        data.put("createsNewSettlement", String.valueOf(preview.createsNewSettlement()));
        data.put("capitalName", preview.capitalName());
        data.put("culture", cultureKey);
        data.put("realmName", name);
        data.put("blockX", String.valueOf(center.x()));
        data.put("blockZ", String.valueOf(center.z()));
        if (!preview.eligible()) {
            return PlayerActionResponse.fail(PlayerActionType.PREVIEW_FOUND_REALM, preview.message());
        }
        return PlayerActionResponse.ok(PlayerActionType.PREVIEW_FOUND_REALM, preview.message(), data);
    }

    private PlayerActionResponse foundRealm(
            CanonicalWorldState state,
            PlayerId player,
            PlayerActionRequest request,
            SimulationContext ctx
    ) {
        if (!"true".equalsIgnoreCase(request.meta("serverVerified"))
                || !"true".equalsIgnoreCase(request.meta("paymentConsumed"))) {
            return PlayerActionResponse.fail(PlayerActionType.FOUND_REALM,
                    "Founding requires verified physical contribution");
        }
        String name = request.meta("realmName");
        if (name.isBlank()) name = "New Realm";
        if (name.length() > 48) name = name.substring(0, 48);
        String cultureKey = CultureKeys.sanitize(request.meta("culture"));
        CultureDefinition culture = cultures.get(cultureKey).orElse(cultures.get(CultureKeys.DEFAULT).orElseThrow());
        CultureId cultureId = culture.id();
        BlockPos2 center = BlockPos2.of(request.blockX(), request.blockZ());
        var req = PlayerSystemsEngine.FoundationRequirements.defaults();
        int expectedGold = (int) Math.ceil(req.minTreasuryContribution());
        int paid = request.metaInt("paymentGold", -1);
        if (paid != expectedGold) {
            return PlayerActionResponse.fail(PlayerActionType.FOUND_REALM,
                    "Founding cost is " + expectedGold + " gold ingots");
        }
        SettlementState near = nearestSettlement(state, center, 128);
        if (near != null && near.ownerKingdom().isPresent()) {
            KingdomId owner = near.ownerKingdom().get();
            if (state.playerReputation().reputation(player, owner) < 0
                    || hasSevereCrime(state, player, owner)) {
                return PlayerActionResponse.fail(PlayerActionType.FOUND_REALM,
                        "Cannot found a realm while hostile to nearby kingdom");
            }
        }
        for (SettlementState s : state.settlements().values()) {
            if (s.center().distanceTo(center) < 48 && s.ownerKingdom().isPresent()) {
                return PlayerActionResponse.fail(PlayerActionType.FOUND_REALM,
                        "Too close to claimed territory");
            }
        }
        var preview = playerSystems.previewFoundation(state, player, name, center, req);
        if (!preview.eligible()) {
            return PlayerActionResponse.fail(PlayerActionType.FOUND_REALM, preview.message());
        }
        var result = playerSystems.foundKingdom(state, player, name, center, cultureId, req, ctx);
        if (!result.success()) {
            return PlayerActionResponse.fail(PlayerActionType.FOUND_REALM, result.message());
        }
        Map<String, String> data = new LinkedHashMap<>();
        data.put("kingdomId", result.kingdomId().value().toString());
        data.put("message", result.message());
        data.put("culture", cultureKey);
        data.put("foundingStatus", "FOUNDING_CONSTRUCTION");
        data.put("realmName", name);
        data.put("paymentGold", String.valueOf(expectedGold));
        return PlayerActionResponse.ok(PlayerActionType.FOUND_REALM, result.message(), data);
    }

    private PlayerActionResponse setTax(
            CanonicalWorldState state, PlayerId player, PlayerActionRequest request, SimulationContext ctx
    ) {
        if (!isRuler(state, player)) {
            return PlayerActionResponse.fail(PlayerActionType.SET_TAX_POLICY, "Not a realm ruler");
        }
        double rate = Math.max(0, Math.min(0.4, request.metaDouble("value", 0.08)));
        playerSystems.setPolicyTaxRate(state, player, rate);
        return policyOk(PlayerActionType.SET_TAX_POLICY,
                "Tax rate set to " + String.format(Locale.ROOT, "%.3f", rate)
                        + " — revenue accrues over simulation time",
                "taxRate", rate, ctx, player);
    }

    private PlayerActionResponse setDefense(
            CanonicalWorldState state, PlayerId player, PlayerActionRequest request, SimulationContext ctx
    ) {
        if (!isRuler(state, player)) {
            return PlayerActionResponse.fail(PlayerActionType.SET_DEFENSE_POLICY, "Not a realm ruler");
        }
        double priority = Math.max(0, Math.min(1, request.metaDouble("value", 0.5)));
        playerSystems.setPolicyDefense(state, player, priority);
        return policyOk(PlayerActionType.SET_DEFENSE_POLICY,
                "Defense priority set — security budgets apply over time",
                "defense", priority, ctx, player);
    }

    private PlayerActionResponse setFood(
            CanonicalWorldState state, PlayerId player, PlayerActionRequest request, SimulationContext ctx
    ) {
        if (!isRuler(state, player)) {
            return PlayerActionResponse.fail(PlayerActionType.SET_FOOD_POLICY, "Not a realm ruler");
        }
        double target = Math.max(5, Math.min(200, request.metaDouble("value", 40)));
        playerSystems.setPolicyFoodReserves(state, player, target);
        return policyOk(PlayerActionType.SET_FOOD_POLICY,
                "Food reserve target set — markets react over time",
                "foodReserves", target, ctx, player);
    }

    private PlayerActionResponse setConstruction(
            CanonicalWorldState state, PlayerId player, PlayerActionRequest request, SimulationContext ctx
    ) {
        if (!isRuler(state, player)) {
            return PlayerActionResponse.fail(PlayerActionType.SET_CONSTRUCTION_POLICY, "Not a realm ruler");
        }
        double priority = Math.max(0, Math.min(1, request.metaDouble("value", 0.5)));
        playerSystems.setPolicyConstructionPriority(state, player, priority);
        return policyOk(PlayerActionType.SET_CONSTRUCTION_POLICY,
                "Construction priority set — planners fund works over time",
                "construction", priority, ctx, player);
    }

    private PlayerActionResponse setMigration(
            CanonicalWorldState state, PlayerId player, PlayerActionRequest request, SimulationContext ctx
    ) {
        if (!isRuler(state, player)) {
            return PlayerActionResponse.fail(PlayerActionType.SET_MIGRATION_POLICY, "Not a realm ruler");
        }
        boolean open = !"false".equalsIgnoreCase(request.meta("value"))
                && request.metaDouble("value", 1) >= 0.5;
        playerSystems.setPolicyMigrationOpenness(state, player, open);
        return policyOk(PlayerActionType.SET_MIGRATION_POLICY,
                open ? "Borders open to migrants" : "Borders tightened",
                "migrationOpen", open ? 1.0 : 0.0, ctx, player);
    }

    private PlayerActionResponse policyOk(
            PlayerActionType type, String message, String key, double value,
            SimulationContext ctx, PlayerId player
    ) {
        Map<String, String> data = new LinkedHashMap<>();
        data.put(key, String.format(Locale.ROOT, "%.3f", value));
        return PlayerActionResponse.ok(type, message, data);
    }

    private PlayerActionResponse diplomacyAction(
            CanonicalWorldState state,
            PlayerId player,
            PlayerActionRequest request,
            SimulationContext ctx
    ) {
        KingdomId ruled = state.playerReputation().ruledKingdom(player);
        if (ruled == null) {
            return PlayerActionResponse.fail(PlayerActionType.REQUEST_DIPLOMATIC_ACTION,
                    "Only realm rulers may enact diplomacy");
        }
        KingdomId other = KingdomId.of(nz(request.kingdomId()));
        if (isZeroKingdom(other) || other.equals(ruled)) {
            String raw = request.meta("targetKingdomId");
            if (!raw.isBlank()) {
                try { other = KingdomId.of(UUID.fromString(raw)); } catch (Exception ignored) {}
            }
        }
        KingdomState otherK = state.kingdoms().get(other);
        if (otherK == null) {
            return PlayerActionResponse.fail(PlayerActionType.REQUEST_DIPLOMATIC_ACTION, "Unknown target kingdom");
        }
        // Target must be known to the player (no raw UUID injection of unknowns).
        var knowledge = state.playerReputation().knowledge().kingdomKnowledge(player, other);
        if (knowledge == PlayerKnowledgeState.KnowledgeLevel.UNKNOWN
                && !other.equals(state.playerReputation().ruledKingdom(player))) {
            return PlayerActionResponse.fail(PlayerActionType.REQUEST_DIPLOMATIC_ACTION,
                    "Target kingdom unknown — discover it first");
        }
        String kind = request.meta("diplomacyType").toUpperCase(Locale.ROOT);
        if (kind.isBlank()) kind = request.meta("treaty").toUpperCase(Locale.ROOT);
        KingdomState self = state.kingdoms().get(ruled);

        DiplomacyProposalEvaluator.Evaluation eval =
                DiplomacyProposalEvaluator.evaluate(state, player, ruled, other, kind);
        if (eval.decision() == DiplomacyProposalEvaluator.Decision.INVALID) {
            return PlayerActionResponse.fail(PlayerActionType.REQUEST_DIPLOMATIC_ACTION, eval.reason());
        }
        if (eval.decision() == DiplomacyProposalEvaluator.Decision.REJECTED) {
            state.appendHistory(new HistoricalEvent(
                    HistoricalEventId.deterministic(state.seed(), state.history().size()),
                    CivilizationEventType.TREATY_SIGNED,
                    ctx.time(),
                    "Diplomacy rejected",
                    otherK.name() + " rejected " + kind + " from player realm",
                    Optional.empty(),
                    Map.of("a", ruled.toString(), "b", other.toString(), "kind", kind, "result", "REJECTED")
            ));
            return PlayerActionResponse.ok(PlayerActionType.REQUEST_DIPLOMATIC_ACTION,
                    eval.reason(),
                    Map.of("result", "REJECTED", "target", otherK.name(), "diplomacyType", kind));
        }

        // ACCEPTED — apply state change once.
        return switch (kind) {
            case "PEACE", "REQUEST_PEACE" -> {
                for (WarState war : state.wars().values()) {
                    if (!war.active()) continue;
                    if ((war.aggressor().equals(ruled) && war.defender().equals(other))
                            || (war.aggressor().equals(other) && war.defender().equals(ruled))) {
                        war.setActive(false);
                    }
                }
                state.diplomacy().setRelation(ruled, other, DiplomaticRelation.TENSE);
                state.diplomacy().setScore(ruled, other, Math.max(-15, state.diplomacy().score(ruled, other) + 10));
                state.appendHistory(new HistoricalEvent(
                        HistoricalEventId.deterministic(state.seed(), state.history().size()),
                        CivilizationEventType.TREATY_SIGNED,
                        ctx.time(),
                        "Peace accepted",
                        "Peace concluded with " + otherK.name(),
                        Optional.empty(),
                        Map.of("a", ruled.toString(), "b", other.toString(), "result", "ACCEPTED")
                ));
                yield PlayerActionResponse.ok(PlayerActionType.REQUEST_DIPLOMATIC_ACTION,
                        "Peace concluded with " + otherK.name(),
                        Map.of("result", "ACCEPTED", "relation", "TENSE", "target", otherK.name()));
            }
            case "TREATY", "TRADE", "ALLIANCE", "NON_AGGRESSION" -> {
                TreatyType treatyType = eval.treatyType() != null ? eval.treatyType() : switch (kind) {
                    case "ALLIANCE" -> TreatyType.ALLIANCE;
                    case "NON_AGGRESSION" -> TreatyType.NON_AGGRESSION;
                    default -> TreatyType.TRADE;
                };
                if (self != null && self.treasury() < 10) {
                    yield PlayerActionResponse.fail(PlayerActionType.REQUEST_DIPLOMATIC_ACTION,
                            "Treasury too low for diplomatic gifts");
                }
                if (self != null) self.setTreasury(self.treasury() - 10);
                TreatyId tid = TreatyId.deterministic(state.seed(), state.diplomacy().treaties().size() + 900);
                state.diplomacy().treaties().put(tid,
                        new DiplomacyState.TreatyRecord(tid, ruled, other, treatyType, ctx.time().dayIndex()));
                double boost = treatyType == TreatyType.ALLIANCE ? 25 : 12;
                state.diplomacy().setScore(ruled, other, state.diplomacy().score(ruled, other) + boost);
                state.diplomacy().setRelation(ruled, other,
                        DiplomacyState.categoricalFromScore(state.diplomacy().score(ruled, other)));
                state.appendHistory(new HistoricalEvent(
                        HistoricalEventId.deterministic(state.seed(), state.history().size()),
                        CivilizationEventType.TREATY_SIGNED,
                        ctx.time(),
                        "Treaty accepted",
                        treatyType.name() + " with " + otherK.name(),
                        Optional.empty(),
                        Map.of("a", ruled.toString(), "b", other.toString(), "type", treatyType.name(),
                                "result", "ACCEPTED")
                ));
                yield PlayerActionResponse.ok(PlayerActionType.REQUEST_DIPLOMATIC_ACTION,
                        treatyType.name() + " treaty with " + otherK.name(),
                        Map.of("result", "ACCEPTED", "treaty", treatyType.name(), "relation",
                                state.diplomacy().relation(ruled, other).name(), "target", otherK.name()));
            }
            case "BREAK", "BREAK_TREATY" -> {
                boolean broken = false;
                for (var entry : List.copyOf(state.diplomacy().treaties().entrySet())) {
                    var t = entry.getValue();
                    if (!t.active()) continue;
                    if ((t.a().equals(ruled) && t.b().equals(other))
                            || (t.a().equals(other) && t.b().equals(ruled))) {
                        state.diplomacy().treaties().put(entry.getKey(), t.withActive(false).withViolated(true));
                        broken = true;
                    }
                }
                if (!broken) {
                    yield PlayerActionResponse.fail(PlayerActionType.REQUEST_DIPLOMATIC_ACTION, "No active treaty");
                }
                state.diplomacy().setScore(ruled, other, state.diplomacy().score(ruled, other) - 20);
                state.diplomacy().setRelation(ruled, other, DiplomaticRelation.TENSE);
                yield PlayerActionResponse.ok(PlayerActionType.REQUEST_DIPLOMATIC_ACTION,
                        "Treaty broken with " + otherK.name(),
                        Map.of("result", "ACCEPTED", "relation", "TENSE", "target", otherK.name()));
            }
            case "IMPROVE" -> {
                if (self != null && self.treasury() < 5) {
                    yield PlayerActionResponse.fail(PlayerActionType.REQUEST_DIPLOMATIC_ACTION, "Need 5 treasury");
                }
                if (self != null) self.setTreasury(self.treasury() - 5);
                state.diplomacy().setScore(ruled, other, state.diplomacy().score(ruled, other) + 8);
                state.diplomacy().setRelation(ruled, other,
                        DiplomacyState.categoricalFromScore(state.diplomacy().score(ruled, other)));
                yield PlayerActionResponse.ok(PlayerActionType.REQUEST_DIPLOMATIC_ACTION,
                        "Relations improved with " + otherK.name(),
                        Map.of("result", "ACCEPTED", "relation",
                                state.diplomacy().relation(ruled, other).name(), "target", otherK.name()));
            }
            default -> PlayerActionResponse.fail(PlayerActionType.REQUEST_DIPLOMATIC_ACTION,
                    "Unsupported diplomacy type: " + kind);
        };
    }

    private PlayerActionResponse declareWarSupport(
            CanonicalWorldState state,
            PlayerId player,
            PlayerActionRequest request,
            SimulationContext ctx
    ) {
        KingdomId side = KingdomId.of(nz(request.kingdomId()));
        String raw = request.meta("sideKingdomId");
        if (!raw.isBlank()) {
            try { side = KingdomId.of(UUID.fromString(raw)); } catch (Exception ignored) {}
        }
        KingdomState k = state.kingdoms().get(side);
        if (k == null) {
            return PlayerActionResponse.fail(PlayerActionType.DECLARE_SUPPORT_IN_WAR, "Unknown kingdom");
        }
        FactionStanding standing = state.playerReputation().standing(player, side);
        KingdomId ruled = state.playerReputation().ruledKingdom(player);
        if (!standing.atLeast(FactionStanding.CITIZEN) && (ruled == null || !ruled.equals(side))) {
            return PlayerActionResponse.fail(PlayerActionType.DECLARE_SUPPORT_IN_WAR,
                    "Must be a citizen or ruler to pledge service");
        }
        WarState active = null;
        for (WarState war : state.wars().values()) {
            if (!war.active()) continue;
            if (war.aggressor().equals(side) || war.defender().equals(side)) {
                active = war;
                break;
            }
        }
        if (active == null) {
            return PlayerActionResponse.fail(PlayerActionType.DECLARE_SUPPORT_IN_WAR, "No active war");
        }
        active.participants().add(side);
        state.playerReputation().adjust(player, side, 0.1);
        state.playerReputation().setPolicy(player, "warSupport:" + side.value(), 1.0);
        Map<String, String> data = Map.of(
                "kingdom", k.name(),
                "warId", active.id().toString(),
                "side", side.value().toString()
        );
        state.appendHistory(new HistoricalEvent(
                HistoricalEventId.deterministic(state.seed(), state.history().size()),
                CivilizationEventType.PLAYER_REPUTATION_CHANGED,
                ctx.time(),
                "War pledge",
                player + " pledged support to " + k.name(),
                Optional.empty(),
                data
        ));
        return PlayerActionResponse.ok(PlayerActionType.DECLARE_SUPPORT_IN_WAR,
                "You pledged military support to " + k.name(), data);
    }

    private PlayerActionResponse queryFine(
            CanonicalWorldState state,
            PlayerId player,
            PlayerActionRequest request
    ) {
        KingdomId kingdom = KingdomId.of(nz(request.kingdomId()));
        SettlementId sid = SettlementId.of(nz(request.settlementId()));
        PlayerLegalRecord legal = state.playerReputation().legalRecord(player, kingdom);
        if (legal == null) {
            legal = state.playerReputation().legalRecordForSettlement(player, sid);
        }
        Map<String, String> data = new LinkedHashMap<>();
        if (legal == null || legal.outstandingFine() <= 0) {
            data.put("outstandingFine", "0");
            data.put("legalStatus", legal == null ? "CLEAR" : legal.status().name());
            data.put("fullPaymentOnly", "true");
            return PlayerActionResponse.ok(PlayerActionType.QUERY_FINE, "No outstanding fine", data);
        }
        int gold = (int) Math.ceil(legal.outstandingFine());
        data.put("outstandingFine", String.valueOf(gold));
        data.put("legalStatus", legal.status().name());
        data.put("fullPaymentOnly", "true");
        data.put("kingdomId", kingdom == null ? "" : kingdom.value().toString());
        return PlayerActionResponse.ok(PlayerActionType.QUERY_FINE,
                "Outstanding fine: " + gold + " gold ingots", data);
    }

    private PlayerActionResponse payFine(
            CanonicalWorldState state,
            PlayerId player,
            PlayerActionRequest request,
            SimulationContext ctx
    ) {
        if (!"true".equalsIgnoreCase(request.meta("serverVerified"))) {
            return PlayerActionResponse.fail(PlayerActionType.PAY_FINE, "Unverified payment");
        }
        KingdomId kingdom = KingdomId.of(nz(request.kingdomId()));
        PlayerLegalRecord legal = state.playerReputation().legalRecord(player, kingdom);
        if (legal == null || legal.outstandingFine() <= 0) {
            return PlayerActionResponse.fail(PlayerActionType.PAY_FINE, "No outstanding fine");
        }
        int required = (int) Math.ceil(legal.outstandingFine());
        int paid = request.metaInt("amount", -1);
        // Full payment only — partial payments are not silently accepted.
        if (paid != required) {
            return PlayerActionResponse.fail(PlayerActionType.PAY_FINE,
                    "Full payment required: " + required + " gold ingots");
        }
        legal.setOutstandingFine(0);
        legal.setStatus(PlayerLegalStatus.CLEAR);
        legal.openCrimeIds().clear();
        KingdomState k = state.kingdoms().get(kingdom);
        if (k != null) k.setTreasury(k.treasury() + paid);
        playerSystems.recordPlayerAction(state, player, kingdom, 0.05, ctx);
        return PlayerActionResponse.ok(PlayerActionType.PAY_FINE, "Fine paid in full",
                Map.of("remaining", "0", "legalStatus", "CLEAR", "paid", String.valueOf(paid)));
    }

    private PlayerActionResponse surrender(
            CanonicalWorldState state,
            PlayerId player,
            PlayerActionRequest request,
            SimulationContext ctx
    ) {
        KingdomId kingdom = KingdomId.of(nz(request.kingdomId()));
        SettlementId sid = SettlementId.of(nz(request.settlementId()));
        PlayerLegalRecord legal = state.playerReputation().ensureLegal(player, kingdom, sid);
        if (legal == null) {
            return PlayerActionResponse.fail(PlayerActionType.SURRENDER_TO_GUARDS, "No jurisdiction");
        }
        if (legal.status() == PlayerLegalStatus.CLEAR) {
            return PlayerActionResponse.ok(PlayerActionType.SURRENDER_TO_GUARDS, "Nothing to surrender for",
                    Map.of("legalStatus", "CLEAR"));
        }
        double fine = Math.max(5, legal.outstandingFine());
        if (legal.status() == PlayerLegalStatus.WANTED || legal.status() == PlayerLegalStatus.CONVICTED) {
            fine = Math.max(fine, fineForCrimes(state, legal));
        }
        legal.setOutstandingFine(fine);
        legal.setStatus(PlayerLegalStatus.FINE_OUTSTANDING);
        legal.setUpdatedDay(ctx.time().dayIndex());
        playerSystems.recordPlayerAction(state, player, kingdom, 0.02, ctx);
        return PlayerActionResponse.ok(PlayerActionType.SURRENDER_TO_GUARDS,
                "You surrendered. Fine outstanding: " + (int) fine,
                Map.of("fine", String.format(Locale.ROOT, "%.1f", fine),
                        "legalStatus", legal.status().name()));
    }

    private PlayerActionResponse discoverSettlement(
            CanonicalWorldState state,
            PlayerId player,
            PlayerActionRequest request
    ) {
        // Server-derived coordinates only — NeoForge must pass ServerPlayer.blockPosition().
        BlockPos2 pos = BlockPos2.of(request.blockX(), request.blockZ());
        SettlementState s = nearestSettlement(state, pos, 48);
        if (s == null) {
            return PlayerActionResponse.fail(PlayerActionType.DISCOVER_SETTLEMENT,
                    "No settlement nearby to observe");
        }
        state.playerReputation().knowledge().discoverSettlement(
                player, s.id(), PlayerKnowledgeState.KnowledgeLevel.OBSERVED);
        s.ownerKingdom().ifPresent(k -> state.playerReputation().knowledge().discoverKingdom(
                player, k, PlayerKnowledgeState.KnowledgeLevel.KNOWN));
        return PlayerActionResponse.ok(PlayerActionType.DISCOVER_SETTLEMENT, "Discovered " + s.name(),
                Map.of("settlementId", s.id().value().toString(), "name", s.name(),
                        "knowledge", "OBSERVED"));
    }

    private PlayerActionResponse queryTaskJournal(
            CanonicalWorldState state,
            PlayerId player,
            PlayerActionRequest request
    ) {
        List<String> entries = new ArrayList<>();
        int n = 0;
        for (EmergentTaskState task : state.emergentTasks().values()) {
            boolean relevant = task.assignedTo(player)
                    || task.status() == TaskStatus.DISCOVERED
                    || task.status() == TaskStatus.OPEN
                    || (task.acceptedBy() != null && task.acceptedBy().equals(player));
            if (!relevant) continue;
            if (task.status() == TaskStatus.OPEN && !task.assignedTo(player)
                    && task.status() != TaskStatus.DISCOVERED) {
                // Only show discovered/accepted/own tasks in journal
                continue;
            }
            if (task.status() == TaskStatus.OPEN) continue;
            SettlementState s = state.settlements().get(task.settlementId());
            String settlementName = s == null ? "" : s.name();
            Map<String, String> tags = task.problemTags();
            String objective = task.type().name();
            String resource = tags.getOrDefault("resource", "");
            String amount = tags.getOrDefault("amount", "");
            String wood = tags.getOrDefault("wood", "");
            String stone = tags.getOrDefault("stone", "");
            String campId = tags.getOrDefault("campId", "");
            String shipment = tags.getOrDefault("shipment", "");
            // id|status|type|title|desc|settlementId|settlementName|resource|amount|wood|stone|campId|shipment|accepted
            entries.add(String.join("|",
                    task.id().toString(),
                    task.status().name(),
                    task.type().name(),
                    sanitizePipe(task.title()),
                    sanitizePipe(task.description()),
                    task.settlementId().value().toString(),
                    sanitizePipe(settlementName),
                    resource, amount, wood, stone, campId, shipment,
                    task.assignedTo(player) ? "true" : "false",
                    sanitizePipe(task.progressNote())));
            if (++n >= 24) break;
        }
        Map<String, String> data = new LinkedHashMap<>();
        data.put("tasks", String.join(";", entries));
        data.put("count", String.valueOf(entries.size()));
        return PlayerActionResponse.ok(PlayerActionType.QUERY_TASK_JOURNAL,
                entries.isEmpty() ? "No tasks" : entries.size() + " tasks", data);
    }

    private static String sanitizePipe(String s) {
        if (s == null) return "";
        return s.replace('|', '/').replace(';', ',');
    }

    private PlayerActionResponse queryContext(
            CanonicalWorldState state,
            PlayerId player,
            PlayerActionRequest request
    ) {
        Map<String, String> data = new LinkedHashMap<>();
        KingdomId ruled = state.playerReputation().ruledKingdom(player);
        data.put("ruledKingdomId", ruled == null ? "" : ruled.value().toString());
        if (ruled != null) {
            KingdomState k = state.kingdoms().get(ruled);
            if (k != null) {
                data.put("ruledKingdomName", k.name());
                data.put("treasury", String.format(Locale.ROOT, "%.1f", k.treasury()));
                data.put("taxRate", String.format(Locale.ROOT, "%.3f", k.taxRate()));
                data.put("culture", CultureKeys.resolve(k.cultureId()));
                data.put("settlements", String.valueOf(k.settlementIds().size()));
            }
            data.put("policyTax", String.valueOf(state.playerReputation().policy(player, "taxRate", k == null ? 0.08 : k.taxRate())));
            data.put("policyDefense", String.valueOf(state.playerReputation().policy(player, "defense", 0.3)));
            data.put("policyFood", String.valueOf(state.playerReputation().policy(player, "foodReserves", 40)));
            data.put("policyConstruction", String.valueOf(state.playerReputation().policy(player, "construction", 0.3)));
            data.put("policyMigration", String.valueOf(state.playerReputation().policy(player, "migrationOpen", 1)));
        }

        // Auto-observe settlements near server-derived player position.
        if (request.blockX() != 0 || request.blockZ() != 0) {
            SettlementState near = nearestSettlement(state, BlockPos2.of(request.blockX(), request.blockZ()), 48);
            if (near != null) {
                state.playerReputation().knowledge().discoverSettlement(
                        player, near.id(), PlayerKnowledgeState.KnowledgeLevel.OBSERVED);
                near.ownerKingdom().ifPresent(kid ->
                        state.playerReputation().knowledge().discoverKingdom(
                                player, kid, PlayerKnowledgeState.KnowledgeLevel.KNOWN));
            }
        }

        List<String> taskLines = new ArrayList<>();
        for (EmergentTaskState task : state.emergentTasks().values()) {
            if (task.assignedTo(player) || task.status() == TaskStatus.DISCOVERED
                    || (task.acceptedBy() != null && task.acceptedBy().equals(player))) {
                SettlementState s = state.settlements().get(task.settlementId());
                taskLines.add(task.id() + "|" + task.status().name() + "|" + sanitizePipe(task.title())
                        + "|" + task.settlementId().value()
                        + "|" + sanitizePipe(task.description())
                        + "|" + task.type().name()
                        + "|" + task.problemTags().getOrDefault("resource", "")
                        + "|" + task.problemTags().getOrDefault("amount", "")
                        + "|" + task.problemTags().getOrDefault("wood", "")
                        + "|" + task.problemTags().getOrDefault("stone", "")
                        + "|" + task.problemTags().getOrDefault("campId", "")
                        + "|" + task.problemTags().getOrDefault("shipment", "")
                        + "|" + (s == null ? "" : sanitizePipe(s.name())));
            }
            if (taskLines.size() >= 16) break;
        }
        data.put("tasks", String.join(";", taskLines));

        List<String> culturesList = new ArrayList<>();
        for (CultureDefinition c : cultures.surfaceCultures()) {
            culturesList.add(c.key() + ":" + c.displayName());
        }
        data.put("cultures", String.join(",", culturesList));

        List<String> kingdoms = new ArrayList<>();
        List<String> dispositionRows = new ArrayList<>();
        for (var e : state.playerReputation().knowledge().kingdoms().getOrDefault(player, Map.of()).entrySet()) {
            KingdomState k = state.kingdoms().get(e.getKey());
            if (k == null) continue;
            String standing = state.playerReputation().standing(player, e.getKey()).name();
            String attitude = state.playerReputation().attitudeLabel(player, e.getKey());
            String war = "PEACE";
            for (WarState w : state.wars().values()) {
                if (w.active() && (w.aggressor().equals(e.getKey()) || w.defender().equals(e.getKey()))) {
                    war = "WAR";
                    break;
                }
            }
            String relation = state.diplomacy().relation(
                    ruled == null ? e.getKey() : ruled, e.getKey()).name();
            kingdoms.add(e.getKey().value() + "|" + k.name() + "|" + CultureKeys.resolve(k.cultureId())
                    + "|" + k.governmentType().name() + "|" + standing + "|" + attitude + "|" + war
                    + "|" + String.format(Locale.ROOT, "%.1f", k.treasury())
                    + "|" + relation + "|" + e.getValue().name());
            var disp = FactionDispositionResolver.playerTowardKingdom(state, player, e.getKey());
            PlayerLegalRecord legal = state.playerReputation().legalRecord(player, e.getKey());
            boolean wanted = legal != null && legal.isWantedOrWorse();
            boolean citizen = state.playerReputation().standing(player, e.getKey())
                    .atLeast(FactionStanding.CITIZEN);
            dispositionRows.add(e.getKey().value() + "|" + disp.name()
                    + "|" + (legal == null ? "CLEAR" : legal.status().name())
                    + "|" + wanted + "|" + citizen);
            if (kingdoms.size() >= 24) break;
        }
        // Always include ruled kingdom disposition
        if (ruled != null) {
            boolean found = dispositionRows.stream().anyMatch(r -> r.startsWith(ruled.value().toString()));
            if (!found) {
                dispositionRows.add(ruled.value() + "|ALLIED|CLEAR|false|true");
            }
        }
        // Include legal jurisdictions even if kingdom not in knowledge map
        for (var e : state.playerReputation().legalByKingdom().getOrDefault(player, Map.of()).entrySet()) {
            boolean found = dispositionRows.stream().anyMatch(r -> r.startsWith(e.getKey().value().toString()));
            if (found) continue;
            var disp = FactionDispositionResolver.playerTowardKingdom(state, player, e.getKey());
            boolean wanted = e.getValue().isWantedOrWorse();
            dispositionRows.add(e.getKey().value() + "|" + disp.name()
                    + "|" + e.getValue().status().name() + "|" + wanted + "|false");
        }
        data.put("knownKingdoms", String.join(";", kingdoms));
        data.put("dispositionByKingdom", String.join(";", dispositionRows));

        List<String> legalSettlements = new ArrayList<>();
        for (var e : state.playerReputation().legalBySettlement().getOrDefault(player, Map.of()).entrySet()) {
            legalSettlements.add(e.getKey().value() + "|" + e.getValue().status().name());
            if (legalSettlements.size() >= 32) break;
        }
        data.put("legalBySettlement", String.join(";", legalSettlements));

        List<String> settlements = new ArrayList<>();
        for (var e : state.playerReputation().knowledge().settlements().getOrDefault(player, Map.of()).entrySet()) {
            SettlementState s = state.settlements().get(e.getKey());
            if (s == null) continue;
            settlements.add(s.id().value() + "|" + s.name() + "|" + s.tier().name()
                    + "|" + s.housingUnits() + "|" + String.format(Locale.ROOT, "%.2f", s.hunger())
                    + "|" + String.format(Locale.ROOT, "%.2f", s.security())
                    + "|" + String.format(Locale.ROOT, "%.2f", s.unrest())
                    + "|" + s.ownerKingdom().map(id -> id.value().toString()).orElse("")
                    + "|" + e.getValue().name()
                    + "|" + s.center().x() + "|" + s.center().z());
            if (settlements.size() >= 32) break;
        }
        data.put("knownSettlements", String.join(";", settlements));

        var camps = state.playerReputation().knowledge().knownCamps().getOrDefault(player, java.util.Set.of());
        data.put("knownCamps", camps.stream().map(UUID::toString).reduce((a, b) -> a + "," + b).orElse(""));
        var markers = state.playerReputation().knowledge().knownTaskMarkers().getOrDefault(player, java.util.Set.of());
        data.put("knownTaskMarkers", markers.stream().map(UUID::toString).reduce((a, b) -> a + "," + b).orElse(""));

        List<String> wars = new ArrayList<>();
        for (WarState w : state.wars().values()) {
            if (!w.active()) continue;
            // Only expose wars involving known kingdoms or player's realm
            boolean known = ruled != null && (w.aggressor().equals(ruled) || w.defender().equals(ruled));
            if (!known) {
                var kn = state.playerReputation().knowledge().kingdoms().getOrDefault(player, Map.of());
                known = kn.containsKey(w.aggressor()) || kn.containsKey(w.defender());
            }
            if (!known) continue;
            KingdomState a = state.kingdoms().get(w.aggressor());
            KingdomState d = state.kingdoms().get(w.defender());
            wars.add(w.id().value() + "|"
                    + (a == null ? "?" : a.name()) + "|"
                    + (d == null ? "?" : d.name())
                    + "|" + w.aggressor().value() + "|" + w.defender().value());
            if (wars.size() >= 12) break;
        }
        data.put("activeWars", String.join(";", wars));

        // Founding cost for UI
        var freq = PlayerSystemsEngine.FoundationRequirements.defaults();
        data.put("foundingGoldCost", String.valueOf((int) Math.ceil(freq.minTreasuryContribution())));

        List<String> history = new ArrayList<>();
        int h = 0;
        for (var event : state.history()) {
            history.add(event.title() == null ? event.type().name() : event.title());
            if (++h >= 12) break;
        }
        data.put("historyRecent", String.join(" | ", history));

        return new PlayerActionResponse(
                true, "ok", "Player context",
                PlayerActionType.QUERY_PLAYER_CONTEXT, new UUID(0, 0),
                0, ruled == null ? "NEUTRAL" : "RULER",
                List.of(), List.of(), data);
    }

    // --- Crime API used by NeoForge physical bridge / outcomes ---

    public void recordPlayerCrime(
            CanonicalWorldState state,
            PlayerId player,
            CrimeType type,
            SettlementId jurisdiction,
            CitizenId victimOrNull,
            SimulationContext ctx
    ) {
        if (isZero(player) || jurisdiction == null) return;
        SettlementState s = state.settlements().get(jurisdiction);
        KingdomId kingdom = s == null ? null : s.ownerKingdom().orElse(null);
        CrimeId id = CrimeId.deterministic(state.seed(), state.crimes().size() + 50_000L);
        CitizenId suspectProxy = CitizenId.of(player.value()); // player UUID as suspect identity key
        CrimeState crime = new CrimeState(
                id, type, suspectProxy,
                victimOrNull == null ? suspectProxy : victimOrNull,
                jurisdiction, ctx.time().dayIndex());
        crime.setPlayerOffender(player);
        crime.setEvidence(0.7);
        crime.setStatus(type == CrimeType.MURDER || type == CrimeType.ASSAULT
                ? CrimeStatus.INVESTIGATING : CrimeStatus.REPORTED);
        if (type == CrimeType.MURDER || type == CrimeType.BANDITRY) {
            crime.setStatus(CrimeStatus.CONVICTED);
            crime.setVerdict(CrimeVerdict.GUILTY);
            crime.setSentenceDays(type == CrimeType.MURDER ? 120 : 45);
        }
        state.crimes().put(id, crime);
        PlayerLegalRecord legal = state.playerReputation().ensureLegal(player, kingdom, jurisdiction);
        if (legal != null) {
            legal.openCrimeIds().add(id);
            legal.setUpdatedDay(ctx.time().dayIndex());
            if (type == CrimeType.MURDER || type == CrimeType.BANDITRY || type == CrimeType.ASSAULT) {
                legal.setStatus(PlayerLegalStatus.WANTED);
                legal.setOutstandingFine(fineForType(type));
            } else {
                legal.setStatus(PlayerLegalStatus.SUSPECTED);
                legal.setOutstandingFine(Math.max(legal.outstandingFine(), fineForType(type)));
            }
        }
        if (kingdom != null) {
            double delta = switch (type) {
                case MURDER -> -0.45;
                case ASSAULT -> -0.25;
                case BANDITRY -> -0.35;
                case THEFT -> -0.15;
                default -> -0.1;
            };
            playerSystems.recordPlayerAction(state, player, kingdom, delta, ctx);
        }
        state.appendHistory(new HistoricalEvent(
                HistoricalEventId.deterministic(state.seed(), state.history().size()),
                CivilizationEventType.PLAYER_REPUTATION_CHANGED,
                ctx.time(),
                "Crime",
                type.name() + " by player",
                Optional.ofNullable(s).map(SettlementState::center),
                Map.of("player", player.toString(), "crime", type.name())
        ));
    }

    private static double fineForType(CrimeType type) {
        return switch (type) {
            case THEFT, SMUGGLING -> 8;
            case ASSAULT -> 20;
            case BANDITRY -> 35;
            case POLITICAL -> 40;
            case MURDER -> 80;
        };
    }

    private static double fineForCrimes(CanonicalWorldState state, PlayerLegalRecord legal) {
        double sum = 0;
        for (CrimeId id : legal.openCrimeIds()) {
            CrimeState c = state.crimes().get(id);
            if (c != null) sum += fineForType(c.type());
        }
        return Math.max(legal.outstandingFine(), sum);
    }

    private boolean hasSevereCrime(CanonicalWorldState state, PlayerId player, KingdomId kingdom) {
        if (kingdom == null) return false;
        PlayerLegalRecord legal = state.playerReputation().legalRecord(player, kingdom);
        return legal != null && (legal.status() == PlayerLegalStatus.WANTED
                || legal.status() == PlayerLegalStatus.CONVICTED);
    }

    private String legalStatusLabel(
            CanonicalWorldState state, PlayerId player, KingdomId kingdom, SettlementId settlement
    ) {
        PlayerLegalRecord legal = kingdom == null ? null : state.playerReputation().legalRecord(player, kingdom);
        if (legal == null) {
            legal = state.playerReputation().legalRecordForSettlement(player, settlement);
        }
        return legal == null ? PlayerLegalStatus.CLEAR.name() : legal.status().name();
    }

    private boolean isRuler(CanonicalWorldState state, PlayerId player) {
        return state.playerReputation().ruledKingdom(player) != null;
    }

    private static SettlementState nearestSettlement(CanonicalWorldState state, BlockPos2 center, int radius) {
        SettlementState best = null;
        double bestD = Double.MAX_VALUE;
        for (SettlementState s : state.settlements().values()) {
            double d = s.center().distanceTo(center);
            if (d < bestD && d <= radius) {
                best = s;
                bestD = d;
            }
        }
        return best;
    }

    private static ResourceType parseResource(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return ResourceType.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (Exception e) {
            return null;
        }
    }

    private static UUID nz(UUID id) {
        return id == null ? new UUID(0, 0) : id;
    }

    private static boolean isZero(PlayerId player) {
        return player == null
                || (player.value().getMostSignificantBits() == 0
                && player.value().getLeastSignificantBits() == 0);
    }

    private static boolean isZeroKingdom(KingdomId id) {
        return id == null
                || (id.value().getMostSignificantBits() == 0
                && id.value().getLeastSignificantBits() == 0);
    }

    public PlayerSystemsEngine playerSystems() { return playerSystems; }
    public EmergentTaskEngine tasks() { return tasks; }
}
