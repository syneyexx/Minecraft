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
        data.put("settlementId", task.settlementId().value().toString());
        data.put("status", task.status().name());
        data.put("description", task.description());
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
     * Buy: NeoForge must pre-verify payment items and inventory space, then set meta
     * serverVerified=true, paymentConsumed=true, amount.
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
        double total = unitPrice * amount;
        // Payment already consumed on NeoForge side in GOLD_INGOT; debit stockpile gold credit.
        stock.add(resource, -amount);
        stock.add(CURRENCY, total);
        SettlementState settlement = state.settlements().get(sid);
        if (settlement != null && settlement.ownerKingdom().isPresent()) {
            KingdomState k = state.kingdoms().get(settlement.ownerKingdom().get());
            if (k != null) k.setTreasury(k.treasury() + total * 0.2);
        }
        playerSystems.recordPlayerAction(state, player, kingdom, 0.02, ctx);
        Map<String, String> data = new LinkedHashMap<>();
        data.put("resource", resource.name());
        data.put("amount", String.valueOf(amount));
        data.put("unitPrice", String.format(Locale.ROOT, "%.3f", unitPrice));
        data.put("total", String.format(Locale.ROOT, "%.3f", total));
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
        double unitPrice = market.price(resource) * 0.7 / priceMult; // sell below buy; reputation helps
        double total = unitPrice * amount;
        // Settlement liquidity: needs treasury/gold or accepts goods into stockpile.
        KingdomState k = state.kingdoms().get(kingdom);
        if (k != null && k.treasury() < total * 0.1 && stock.get(CURRENCY) < total) {
            return PlayerActionResponse.fail(PlayerActionType.SELL_RESOURCE, "Market cannot pay");
        }
        stock.add(resource, amount);
        stock.add(CURRENCY, -Math.min(stock.get(CURRENCY), total));
        if (k != null) k.setTreasury(Math.max(0, k.treasury() - total * 0.1));
        playerSystems.recordPlayerAction(state, player, kingdom, 0.03, ctx);
        Map<String, String> data = new LinkedHashMap<>();
        data.put("resource", resource.name());
        data.put("amount", String.valueOf(amount));
        data.put("unitPrice", String.format(Locale.ROOT, "%.3f", unitPrice));
        data.put("total", String.format(Locale.ROOT, "%.3f", total));
        data.put("paymentGold", String.valueOf((int) Math.ceil(total)));
        return PlayerActionResponse.ok(PlayerActionType.SELL_RESOURCE, "Sold " + amount + " " + resource, data);
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

    private PlayerActionResponse foundRealm(
            CanonicalWorldState state,
            PlayerId player,
            PlayerActionRequest request,
            SimulationContext ctx
    ) {
        String name = request.meta("realmName");
        if (name.isBlank()) name = "New Realm";
        if (name.length() > 48) name = name.substring(0, 48);
        String cultureKey = CultureKeys.sanitize(request.meta("culture"));
        CultureDefinition culture = cultures.get(cultureKey).orElse(cultures.get(CultureKeys.DEFAULT).orElseThrow());
        CultureId cultureId = culture.id();
        BlockPos2 center = BlockPos2.of(request.blockX(), request.blockZ());
        var req = PlayerSystemsEngine.FoundationRequirements.defaults();
        // Enforce reputation if claiming near a kingdom settlement
        SettlementState near = nearestSettlement(state, center, 128);
        if (near != null && near.ownerKingdom().isPresent()) {
            KingdomId owner = near.ownerKingdom().get();
            if (state.playerReputation().reputation(player, owner) < 0
                    || hasSevereCrime(state, player, owner)) {
                return PlayerActionResponse.fail(PlayerActionType.FOUND_REALM,
                        "Cannot found a realm while hostile to nearby kingdom");
            }
        }
        // Spacing / ownership conflict
        for (SettlementState s : state.settlements().values()) {
            if (s.center().distanceTo(center) < 48 && s.ownerKingdom().isPresent()) {
                return PlayerActionResponse.fail(PlayerActionType.FOUND_REALM,
                        "Too close to claimed territory");
            }
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
        return PlayerActionResponse.ok(PlayerActionType.FOUND_REALM, result.message(), data);
    }

    private PlayerActionResponse setTax(
            CanonicalWorldState state, PlayerId player, PlayerActionRequest request, SimulationContext ctx
    ) {
        if (!isRuler(state, player)) {
            return PlayerActionResponse.fail(PlayerActionType.SET_TAX_POLICY, "Not a realm ruler");
        }
        double rate = Math.max(0, Math.min(0.4, request.metaDouble("value", 0.08)));
        // Treasury consequence: lowering tax reduces immediate treasury yield expectation via policy store.
        playerSystems.setPolicyTaxRate(state, player, rate);
        state.playerReputation().setPolicy(player, "taxRate", rate);
        KingdomId id = state.playerReputation().ruledKingdom(player);
        KingdomState k = state.kingdoms().get(id);
        if (k != null && rate < 0.05) {
            k.setTreasury(Math.max(0, k.treasury() - 2)); // short-term revenue drop
        }
        return policyOk(PlayerActionType.SET_TAX_POLICY, "Tax rate set to " + rate, "taxRate", rate, ctx, player);
    }

    private PlayerActionResponse setDefense(
            CanonicalWorldState state, PlayerId player, PlayerActionRequest request, SimulationContext ctx
    ) {
        if (!isRuler(state, player)) {
            return PlayerActionResponse.fail(PlayerActionType.SET_DEFENSE_POLICY, "Not a realm ruler");
        }
        double priority = Math.max(0, Math.min(1, request.metaDouble("value", 0.5)));
        KingdomId id = state.playerReputation().ruledKingdom(player);
        KingdomState k = state.kingdoms().get(id);
        if (k != null) {
            double cost = priority * 5.0;
            if (k.treasury() < cost) {
                return PlayerActionResponse.fail(PlayerActionType.SET_DEFENSE_POLICY, "Insufficient treasury");
            }
            k.setTreasury(k.treasury() - cost);
        }
        playerSystems.setPolicyDefense(state, player, priority);
        state.playerReputation().setPolicy(player, "defense", priority);
        return policyOk(PlayerActionType.SET_DEFENSE_POLICY, "Defense priority updated", "defense", priority, ctx, player);
    }

    private PlayerActionResponse setFood(
            CanonicalWorldState state, PlayerId player, PlayerActionRequest request, SimulationContext ctx
    ) {
        if (!isRuler(state, player)) {
            return PlayerActionResponse.fail(PlayerActionType.SET_FOOD_POLICY, "Not a realm ruler");
        }
        double target = Math.max(5, Math.min(200, request.metaDouble("value", 40)));
        playerSystems.setPolicyFoodReserves(state, player, target);
        state.playerReputation().setPolicy(player, "foodReserves", target);
        return policyOk(PlayerActionType.SET_FOOD_POLICY, "Food reserve target set", "foodReserves", target, ctx, player);
    }

    private PlayerActionResponse setConstruction(
            CanonicalWorldState state, PlayerId player, PlayerActionRequest request, SimulationContext ctx
    ) {
        if (!isRuler(state, player)) {
            return PlayerActionResponse.fail(PlayerActionType.SET_CONSTRUCTION_POLICY, "Not a realm ruler");
        }
        double priority = Math.max(0, Math.min(1, request.metaDouble("value", 0.5)));
        KingdomId id = state.playerReputation().ruledKingdom(player);
        KingdomState k = state.kingdoms().get(id);
        if (k != null) {
            double cost = priority * 3.0;
            if (k.treasury() < cost * 0.5) {
                return PlayerActionResponse.fail(PlayerActionType.SET_CONSTRUCTION_POLICY,
                        "Treasury too low for construction drive");
            }
            k.setTreasury(Math.max(0, k.treasury() - cost * 0.5));
        }
        playerSystems.setPolicyConstructionPriority(state, player, priority);
        state.playerReputation().setPolicy(player, "construction", priority);
        return policyOk(PlayerActionType.SET_CONSTRUCTION_POLICY, "Construction priority updated",
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
        state.playerReputation().setPolicy(player, "migrationOpen", open ? 1.0 : 0.0);
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
        String kind = request.meta("diplomacyType").toUpperCase(Locale.ROOT);
        if (kind.isBlank()) kind = request.meta("treaty").toUpperCase(Locale.ROOT);
        KingdomState self = state.kingdoms().get(ruled);
        DiplomaticRelation current = state.diplomacy().relation(ruled, other);
        return switch (kind) {
            case "PEACE", "REQUEST_PEACE" -> {
                if (current != DiplomaticRelation.AT_WAR && current != DiplomaticRelation.HOSTILE) {
                    yield PlayerActionResponse.fail(PlayerActionType.REQUEST_DIPLOMATIC_ACTION,
                            "Not at war with " + otherK.name());
                }
                for (WarState war : state.wars().values()) {
                    if (!war.active()) continue;
                    if ((war.aggressor().equals(ruled) && war.defender().equals(other))
                            || (war.aggressor().equals(other) && war.defender().equals(ruled))) {
                        war.setActive(false);
                    }
                }
                state.diplomacy().setRelation(ruled, other, DiplomaticRelation.TENSE);
                state.diplomacy().setScore(ruled, other, -15);
                yield PlayerActionResponse.ok(PlayerActionType.REQUEST_DIPLOMATIC_ACTION,
                        "Peace concluded with " + otherK.name(),
                        Map.of("relation", "TENSE", "target", otherK.name()));
            }
            case "TREATY", "TRADE", "ALLIANCE", "NON_AGGRESSION" -> {
                TreatyType treatyType = switch (kind) {
                    case "ALLIANCE" -> TreatyType.ALLIANCE;
                    case "NON_AGGRESSION" -> TreatyType.NON_AGGRESSION;
                    default -> TreatyType.TRADE;
                };
                if (current == DiplomaticRelation.AT_WAR) {
                    yield PlayerActionResponse.fail(PlayerActionType.REQUEST_DIPLOMATIC_ACTION,
                            "Cannot treat while at war — seek peace first");
                }
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
                        "Treaty signed",
                        treatyType.name() + " with " + otherK.name(),
                        Optional.empty(),
                        Map.of("a", ruled.toString(), "b", other.toString(), "type", treatyType.name())
                ));
                yield PlayerActionResponse.ok(PlayerActionType.REQUEST_DIPLOMATIC_ACTION,
                        treatyType.name() + " treaty with " + otherK.name(),
                        Map.of("treaty", treatyType.name(), "relation",
                                state.diplomacy().relation(ruled, other).name()));
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
                        "Treaty broken with " + otherK.name(), Map.of("relation", "TENSE"));
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
                        Map.of("relation", state.diplomacy().relation(ruled, other).name()));
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
        double paid = request.metaDouble("amount", legal.outstandingFine());
        legal.setOutstandingFine(Math.max(0, legal.outstandingFine() - paid));
        if (legal.outstandingFine() <= 0) {
            legal.setStatus(PlayerLegalStatus.CLEAR);
            legal.openCrimeIds().clear();
        } else {
            legal.setStatus(PlayerLegalStatus.FINE_OUTSTANDING);
        }
        KingdomState k = state.kingdoms().get(kingdom);
        if (k != null) k.setTreasury(k.treasury() + paid);
        playerSystems.recordPlayerAction(state, player, kingdom, 0.05, ctx);
        return PlayerActionResponse.ok(PlayerActionType.PAY_FINE, "Fine paid",
                Map.of("remaining", String.format(Locale.ROOT, "%.1f", legal.outstandingFine()),
                        "legalStatus", legal.status().name()));
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
        SettlementId sid = SettlementId.of(nz(request.settlementId()));
        SettlementState s = state.settlements().get(sid);
        if (s == null) {
            // Discover nearest by coordinates
            s = nearestSettlement(state, BlockPos2.of(request.blockX(), request.blockZ()), 96);
            if (s == null) {
                return PlayerActionResponse.fail(PlayerActionType.DISCOVER_SETTLEMENT, "No settlement nearby");
            }
            sid = s.id();
        }
        state.playerReputation().knowledge().discoverSettlement(
                player, sid, PlayerKnowledgeState.KnowledgeLevel.OBSERVED);
        s.ownerKingdom().ifPresent(k -> state.playerReputation().knowledge().discoverKingdom(
                player, k, PlayerKnowledgeState.KnowledgeLevel.KNOWN));
        return PlayerActionResponse.ok(PlayerActionType.DISCOVER_SETTLEMENT, "Discovered " + s.name(),
                Map.of("settlementId", sid.value().toString(), "name", s.name()));
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
        List<String> taskLines = new ArrayList<>();
        for (EmergentTaskState task : state.emergentTasks().values()) {
            if (task.assignedTo(player) || task.status() == TaskStatus.DISCOVERED) {
                taskLines.add(task.id() + "|" + task.status().name() + "|" + task.title()
                        + "|" + task.settlementId().value());
            }
            if (taskLines.size() >= 16) break;
        }
        data.put("tasks", String.join(";", taskLines));
        List<String> culturesList = new ArrayList<>();
        for (CultureDefinition c : cultures.surfaceCultures()) {
            culturesList.add(c.key() + ":" + c.displayName());
        }
        data.put("cultures", String.join(",", culturesList));

        // Known kingdoms / settlements for dashboard
        List<String> kingdoms = new ArrayList<>();
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
            kingdoms.add(e.getKey().value() + "|" + k.name() + "|" + CultureKeys.resolve(k.cultureId())
                    + "|" + k.governmentType().name() + "|" + standing + "|" + attitude + "|" + war
                    + "|" + String.format(Locale.ROOT, "%.1f", k.treasury()));
            if (kingdoms.size() >= 24) break;
        }
        // If player knows nothing yet, include nearby plan kingdoms as RUMORED via coordinates? keep empty.
        data.put("knownKingdoms", String.join(";", kingdoms));

        List<String> settlements = new ArrayList<>();
        for (var e : state.playerReputation().knowledge().settlements().getOrDefault(player, Map.of()).entrySet()) {
            SettlementState s = state.settlements().get(e.getKey());
            if (s == null) continue;
            settlements.add(s.id().value() + "|" + s.name() + "|" + s.tier().name()
                    + "|" + s.housingUnits() + "|" + String.format(Locale.ROOT, "%.2f", s.hunger())
                    + "|" + String.format(Locale.ROOT, "%.2f", s.security())
                    + "|" + String.format(Locale.ROOT, "%.2f", s.unrest())
                    + "|" + s.ownerKingdom().map(id -> id.value().toString()).orElse("")
                    + "|" + e.getValue().name());
            if (settlements.size() >= 32) break;
        }
        data.put("knownSettlements", String.join(";", settlements));

        List<String> wars = new ArrayList<>();
        for (WarState w : state.wars().values()) {
            if (!w.active()) continue;
            KingdomState a = state.kingdoms().get(w.aggressor());
            KingdomState d = state.kingdoms().get(w.defender());
            wars.add(w.id().value() + "|"
                    + (a == null ? "?" : a.name()) + "|"
                    + (d == null ? "?" : d.name()));
            if (wars.size() >= 12) break;
        }
        data.put("activeWars", String.join(";", wars));

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
