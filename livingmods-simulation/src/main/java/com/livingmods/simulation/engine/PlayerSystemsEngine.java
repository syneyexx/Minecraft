package com.livingmods.simulation.engine;

import com.livingmods.common.event.CivilizationEventType;
import com.livingmods.common.event.HistoricalEvent;
import com.livingmods.common.geo.BlockPos2;
import com.livingmods.common.id.CitizenId;
import com.livingmods.common.id.CultureId;
import com.livingmods.common.id.HistoricalEventId;
import com.livingmods.common.id.KingdomId;
import com.livingmods.common.id.PlayerId;
import com.livingmods.common.id.SettlementId;
import com.livingmods.common.model.FactionStanding;
import com.livingmods.common.model.GovernmentType;
import com.livingmods.common.model.Profession;
import com.livingmods.common.model.ResourceType;
import com.livingmods.common.model.SettlementRole;
import com.livingmods.common.model.SettlementTier;
import com.livingmods.simulation.CanonicalWorldState;
import com.livingmods.simulation.state.CitizenState;
import com.livingmods.simulation.state.KingdomState;
import com.livingmods.simulation.state.MarketState;
import com.livingmods.simulation.state.SettlementState;
import com.livingmods.simulation.state.StockpileState;
import com.livingmods.simulation.tick.RegionalWork;
import com.livingmods.simulation.tick.SimulationContext;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Player reputation, faction standing, and player-founded kingdom rulership.
 * Player sets high-level policy; NPC administration continues routine.
 */
public final class PlayerSystemsEngine implements SimulationSubsystem {
    public record FoundationRequirements(
            int minReputation,
            int minSettlementHousing,
            double minTreasuryContribution,
            boolean requireTrustedStanding
    ) {
        public static FoundationRequirements defaults() {
            return new FoundationRequirements(0, 4, 25.0, false);
        }
    }

    public record FoundationResult(boolean success, String message, KingdomId kingdomId) {}

    @Override
    public String name() { return "player_systems"; }

    @Override
    public void phase1Regional(CanonicalWorldState state, RegionalWork work, SimulationContext ctx) {}

    @Override
    public void phase2Global(CanonicalWorldState state, SimulationContext ctx) {
        if (!ctx.schedule().runGovernment()) return;
        for (var entry : state.playerReputation().reputationByPlayer().entrySet()) {
            for (Map.Entry<KingdomId, Double> rep : entry.getValue().entrySet()) {
                double v = rep.getValue() * 0.995;
                entry.getValue().put(rep.getKey(), v);
            }
        }
        // Player realms: NPC admin continues tax collection / legitimacy / policy-driven budgets.
        for (Map.Entry<PlayerId, KingdomId> ruled : state.playerReputation().ruledKingdoms().entrySet()) {
            PlayerId player = ruled.getKey();
            KingdomState kingdom = state.kingdoms().get(ruled.getValue());
            if (kingdom == null) continue;
            kingdom.setLegitimacy(Math.min(100, kingdom.legitimacy() + 0.05));
            double defense = state.playerReputation().policy(player, "defense", 0.3);
            double foodTarget = state.playerReputation().policy(player, "foodReserves", 40);
            // Routine treasury drip from settlements (tax policy input).
            for (SettlementId sid : kingdom.settlementIds()) {
                StockpileState stock = state.stockpiles().get(sid);
                SettlementState s = state.settlements().get(sid);
                if (stock != null && stock.get(ResourceType.GRAIN) > 10) {
                    kingdom.setTreasury(kingdom.treasury() + kingdom.taxRate() * 0.5);
                }
                // Tax pressure → gradual unrest (not instant button effect).
                if (s != null && kingdom.taxRate() > 0.15) {
                    s.setUnrest(Math.min(1.0, s.unrest() + (kingdom.taxRate() - 0.15) * 0.01));
                } else if (s != null && kingdom.taxRate() < 0.06) {
                    s.setUnrest(Math.max(0, s.unrest() - 0.005));
                }
                // Defense budget: spend treasury over time, slowly raise security.
                if (s != null && defense > 0.2 && kingdom.treasury() >= defense * 0.5) {
                    kingdom.setTreasury(kingdom.treasury() - defense * 0.5);
                    s.setSecurity(Math.min(1.0, s.security() + defense * 0.01));
                }
                // Food reserve policy: markets import pressure when below target.
                MarketState market = state.markets().get(sid);
                if (stock != null && market != null && stock.get(ResourceType.GRAIN) < foodTarget) {
                    market.setCrisisSeverity(Math.min(1.0,
                            market.crisisSeverity() + 0.01 * Math.min(1.0, (foodTarget - stock.get(ResourceType.GRAIN)) / foodTarget)));
                }
            }
        }
    }

    public void recordPlayerAction(
            CanonicalWorldState state,
            PlayerId player,
            KingdomId kingdom,
            double reputationDelta,
            SimulationContext ctx
    ) {
        // Zero UUID is not a legitimate Minecraft player — skip reputation changes.
        if (player == null || (player.value().getMostSignificantBits() == 0L
                && player.value().getLeastSignificantBits() == 0L)) {
            return;
        }
        state.playerReputation().adjust(player, kingdom, reputationDelta);
        KingdomState k = state.kingdoms().get(kingdom);
        if (k != null) {
            for (SettlementId sid : k.settlementIds()) {
                state.playerReputation().adjustSettlement(player, sid, reputationDelta * 0.5);
            }
        }
        state.appendHistory(new HistoricalEvent(
                HistoricalEventId.deterministic(state.seed(), state.history().size()),
                CivilizationEventType.PLAYER_REPUTATION_CHANGED,
                ctx.time(),
                "Player action",
                "Reputation adjusted for " + (k != null ? k.name() : kingdom),
                Optional.empty(),
                Map.of("player", player.toString(), "delta", String.valueOf(reputationDelta))
        ));
    }

    public FactionStanding factionStanding(CanonicalWorldState state, PlayerId player, KingdomId kingdom) {
        return state.playerReputation().standing(player, kingdom);
    }

    public void setFactionMembership(
            CanonicalWorldState state,
            PlayerId player,
            KingdomId kingdom,
            FactionStanding standing
    ) {
        state.playerReputation().setStanding(player, kingdom, standing);
        if (standing.atLeast(FactionStanding.CITIZEN)) {
            state.playerReputation().adjust(player, kingdom, 0.05);
        }
    }

    public record FoundationPreview(
            boolean eligible,
            String message,
            int goldCost,
            boolean createsNewSettlement,
            String capitalName
    ) {}

    /**
     * Validate founding without mutating state. Call before payment + commit.
     */
    public FoundationPreview previewFoundation(
            CanonicalWorldState state,
            PlayerId player,
            String realmName,
            BlockPos2 center,
            FoundationRequirements requirements
    ) {
        if (state.playerReputation().ruledKingdom(player) != null) {
            return new FoundationPreview(false, "You already rule a realm.", 0, false, "");
        }
        FoundationRequirements req = requirements == null ? FoundationRequirements.defaults() : requirements;
        int goldCost = (int) Math.ceil(req.minTreasuryContribution());
        SettlementState capital = findClaimableSettlement(state, center, player, req);
        boolean createsNew = capital == null;
        if (capital != null) {
            if (capital.housingUnits() < req.minSettlementHousing()) {
                return new FoundationPreview(false,
                        "Capital needs at least " + req.minSettlementHousing() + " housing units.",
                        goldCost, false, capital.name());
            }
            if (req.requireTrustedStanding() && capital.ownerKingdom().isPresent()) {
                FactionStanding standing = state.playerReputation().standing(player, capital.ownerKingdom().get());
                if (!standing.atLeast(FactionStanding.TRUSTED)) {
                    return new FoundationPreview(false,
                            "Trusted standing required to claim this settlement.", goldCost, false, capital.name());
                }
            }
            if (req.minReputation() > 0 && capital.ownerKingdom().isPresent()) {
                double rep = state.playerReputation().reputation(player, capital.ownerKingdom().get());
                if (rep < req.minReputation() / 100.0) {
                    return new FoundationPreview(false, "Reputation too low to found here.",
                            goldCost, false, capital.name());
                }
            }
            return new FoundationPreview(true, "Ready to claim " + capital.name(),
                    goldCost, false, capital.name());
        }
        // New settlement path — spacing validated by caller for hostile neighbors.
        return new FoundationPreview(true,
                "Ready to found " + (realmName == null || realmName.isBlank() ? "a new realm" : realmName),
                goldCost, true, (realmName == null ? "New" : realmName) + " Hold");
    }

    /**
     * Atomic foundation commit. Must only be called after preview + verified payment.
     * Validates again before any mutation; never leaves partial realm state on failure.
     */
    public FoundationResult foundKingdom(
            CanonicalWorldState state,
            PlayerId player,
            String realmName,
            BlockPos2 center,
            CultureId cultureId,
            FoundationRequirements requirements,
            SimulationContext ctx
    ) {
        FoundationRequirements req = requirements == null ? FoundationRequirements.defaults() : requirements;
        FoundationPreview preview = previewFoundation(state, player, realmName, center, req);
        if (!preview.eligible()) {
            return new FoundationResult(false, preview.message(), null);
        }

        SettlementState capital = findClaimableSettlement(state, center, player, req);
        boolean createdSettlement = false;
        SettlementId createdId = null;
        if (capital == null) {
            capital = createCapitalSettlement(state, realmName, center, cultureId, ctx);
            createdSettlement = true;
            createdId = capital.id();
        }

        // Re-validate after potential create (housing already seeded at 6).
        if (capital.housingUnits() < req.minSettlementHousing()) {
            if (createdSettlement) rollbackCreatedSettlement(state, createdId);
            return new FoundationResult(false,
                    "Capital needs at least " + req.minSettlementHousing() + " housing units.", null);
        }

        KingdomId kingdomId = KingdomId.deterministic(state.seed(), state.kingdoms().size() + 500);
        CitizenId stewardId = ensureSteward(state, capital, cultureId, ctx);

        // Treasury comes only from verified player contribution — never fabricated.
        double treasury = req.minTreasuryContribution();
        KingdomState kingdom = new KingdomState(
                kingdomId,
                realmName == null || realmName.isBlank() ? "Player Realm" : realmName,
                cultureId,
                GovernmentType.PLAYER_REALM,
                capital.id(),
                stewardId,
                new ArrayList<>(List.of(capital.id())),
                treasury,
                0.08,
                60.0
        );
        kingdom.setReligionKey("solar_cult");
        state.kingdoms().put(kingdomId, kingdom);

        SettlementState owned = new SettlementState(
                capital.id(),
                capital.name(),
                capital.tier(),
                capital.role(),
                capital.center(),
                Optional.of(kingdomId),
                true,
                stewardId,
                capital.legitimacy(),
                capital.developmentDeficit(),
                capital.physicalCapacity(),
                capital.housingUnits(),
                capital.employedSlots()
        );
        owned.setUnrest(capital.unrest());
        owned.setSecurity(capital.security());
        owned.setHunger(capital.hunger());
        state.putSettlement(owned);

        state.playerReputation().setRuledKingdom(player, kingdomId);
        state.playerReputation().adjust(player, kingdomId, 1.0);
        state.playerReputation().setStanding(player, kingdomId, FactionStanding.RULER);

        for (KingdomId other : state.kingdoms().keySet()) {
            if (other.equals(kingdomId)) continue;
            state.diplomacy().setRelation(kingdomId, other,
                    com.livingmods.common.model.DiplomaticRelation.NEUTRAL);
        }

        state.stockpiles().computeIfAbsent(owned.id(), StockpileState::new);
        state.markets().computeIfAbsent(owned.id(), MarketState::new);
        StockpileState stock = state.stockpiles().get(owned.id());
        stock.add(ResourceType.GRAIN, 30);
        stock.add(ResourceType.WOOD, 20);
        stock.add(ResourceType.STONE, 15);

        seedPlayerRealmPhysicalIntents(state, owned, player, cultureId, ctx);

        state.appendHistory(new HistoricalEvent(
                HistoricalEventId.deterministic(state.seed(), state.history().size()),
                CivilizationEventType.SETTLEMENT_FOUNDED,
                ctx.time(),
                "Realm founded",
                realmName + " rises under player rule",
                Optional.of(center),
                Map.of("kingdom", kingdomId.toString(), "player", player.toString())
        ));

        return new FoundationResult(true, "Founded " + kingdom.name(), kingdomId);
    }

    private static void rollbackCreatedSettlement(CanonicalWorldState state, SettlementId id) {
        if (id == null) return;
        state.settlements().remove(id);
        state.stockpiles().remove(id);
        state.markets().remove(id);
    }

    /**
     * Policies are durable inputs only — no instant unrest/security/deficit farming.
     * EconomyEngine reads kingdom.taxRate(); other engines read playerReputation.policy(...).
     */
    public void setPolicyTaxRate(CanonicalWorldState state, PlayerId player, double taxRate) {
        KingdomId id = state.playerReputation().ruledKingdom(player);
        if (id == null) return;
        KingdomState k = state.kingdoms().get(id);
        if (k != null) {
            k.setTaxRate(Math.max(0.0, Math.min(0.4, taxRate)));
        }
        state.playerReputation().setPolicy(player, "taxRate", Math.max(0.0, Math.min(0.4, taxRate)));
    }

    public void setPolicyDefense(CanonicalWorldState state, PlayerId player, double defensePriority) {
        KingdomId id = state.playerReputation().ruledKingdom(player);
        if (id == null) return;
        state.playerReputation().setPolicy(player, "defense", Math.max(0.0, Math.min(1.0, defensePriority)));
    }

    public void setPolicyFoodReserves(CanonicalWorldState state, PlayerId player, double reserveTarget) {
        KingdomId id = state.playerReputation().ruledKingdom(player);
        if (id == null) return;
        state.playerReputation().setPolicy(player, "foodReserves", Math.max(5.0, Math.min(200.0, reserveTarget)));
    }

    public void setPolicyConstructionPriority(CanonicalWorldState state, PlayerId player, double priority) {
        KingdomId id = state.playerReputation().ruledKingdom(player);
        if (id == null) return;
        state.playerReputation().setPolicy(player, "construction", Math.max(0.0, Math.min(1.0, priority)));
    }

    public void setPolicyMigrationOpenness(CanonicalWorldState state, PlayerId player, boolean open) {
        KingdomId id = state.playerReputation().ruledKingdom(player);
        if (id == null) return;
        state.playerReputation().setPolicy(player, "migrationOpen", open ? 1.0 : 0.0);
    }

    private static void seedPlayerRealmPhysicalIntents(
            CanonicalWorldState state,
            SettlementState capital,
            PlayerId player,
            CultureId cultureId,
            SimulationContext ctx
    ) {
        String culture = com.livingmods.common.culture.CultureKeys.resolve(cultureId);
        var physical = state.dynamicPhysical();
        var planner = new com.livingmods.simulation.physical.DynamicUrbanPlanner(state.seed());
        var founding = planner.planPlayerSettlement(state, capital, culture);

        physical.putSettlementGeometry(new com.livingmods.simulation.physical.DynamicSettlementGeometry(
                capital.id(),
                founding.center(),
                founding.boundary(),
                true
        ));
        var geo = physical.settlementGeometry().get(capital.id());
        geo.roadAnchors().addAll(founding.roadGraph());
        geo.expansionAnchors().addAll(founding.expansionAnchors());

        // FOUND_PLAYER_SETTLEMENT is planning-meta: orchestrates footprint, does not place one giant building.
        var zoneId = com.livingmods.common.id.PhysicalIntentId.deterministic(
                state.seed(), physical.intents().size() + 401_000L);
        var zone = new com.livingmods.simulation.physical.PhysicalIntent(
                zoneId,
                com.livingmods.common.model.PhysicalIntentType.FOUND_PLAYER_SETTLEMENT,
                "player_realm",
                capital.id().value(),
                Optional.of(capital.id()),
                capital.ownerKingdom(),
                Optional.empty(),
                Optional.empty(),
                founding.center(),
                founding.boundary(),
                1,
                com.livingmods.common.model.PhysicalIntentStatus.READY,
                ctx.time(),
                2,
                Map.of(
                        "cause", "player_foundation",
                        "player", player.toString(),
                        "culture", culture,
                        "planning", "founding_footprint"
                ),
                Map.of(),
                culture
        );
        physical.putIntent(zone);

        // Initial road graph from civic core.
        var roadRoute = planner.planRoad(state, capital, founding.civicCore(),
                founding.roadGraph().size() > 1 ? founding.roadGraph().get(1) : founding.center(), culture);
        var roadId = com.livingmods.common.id.PhysicalIntentId.deterministic(
                state.seed(), physical.intents().size() + 400_000L);
        var road = new com.livingmods.simulation.physical.PhysicalIntent(
                roadId,
                com.livingmods.common.model.PhysicalIntentType.EXTEND_ROAD,
                "player_realm",
                capital.id().value(),
                Optional.of(capital.id()),
                capital.ownerKingdom(),
                Optional.empty(),
                Optional.empty(),
                founding.civicCore(),
                com.livingmods.common.geo.BoundingBox2.of(
                        founding.center().x() - 2, founding.center().z() - 2,
                        founding.center().x() + 24, founding.center().z() + 2),
                1,
                com.livingmods.common.model.PhysicalIntentStatus.READY,
                ctx.time(),
                3,
                Map.of("cause", "player_foundation", "player", player.toString(), "reservationState", "NONE"),
                Map.of(ResourceType.STONE, 8.0, ResourceType.WOOD, 4.0),
                culture
        );
        road.addDependency(zoneId);
        StringBuilder rx = new StringBuilder();
        StringBuilder rz = new StringBuilder();
        for (int i = 0; i < roadRoute.path().size(); i++) {
            if (i > 0) { rx.append(','); rz.append(','); }
            rx.append(roadRoute.path().get(i).x());
            rz.append(roadRoute.path().get(i).z());
        }
        road.provenance().put("routeX", rx.toString());
        road.provenance().put("routeZ", rz.toString());
        road.provenance().put("routePoints", String.valueOf(roadRoute.path().size()));
        seedChunks(road);
        physical.putIntent(road);

        String[] causes = {"civic_center", "starter_housing", "starter_housing", "storage", "food_production", "steward_guard"};
        for (int i = 0; i < founding.starterBuildings().size(); i++) {
            var plot = founding.starterBuildings().get(i);
            var role = i == 0 ? com.livingmods.common.model.BuildingRole.MANOR
                    : i == 3 ? com.livingmods.common.model.BuildingRole.WAREHOUSE
                    : i == 4 ? com.livingmods.common.model.BuildingRole.FARMHOUSE
                    : i == 5 ? com.livingmods.common.model.BuildingRole.GUARDHOUSE
                    : com.livingmods.common.model.BuildingRole.HOUSE;
            seedFoundingBuilding(state, capital, role, plot.center(), plot.footprint(),
                    causes[Math.min(i, causes.length - 1)], culture, zoneId, roadId, plot.entranceFacing(), ctx);
        }

        zone.transitionTo(com.livingmods.common.model.PhysicalIntentStatus.MATERIALIZING,
                ctx.time().absoluteTicks(), null);
        zone.transitionTo(com.livingmods.common.model.PhysicalIntentStatus.MATERIALIZED,
                ctx.time().absoluteTicks(), "plan_committed");
    }

    private static void seedFoundingBuilding(
            CanonicalWorldState state,
            SettlementState capital,
            com.livingmods.common.model.BuildingRole role,
            BlockPos2 plot,
            com.livingmods.common.geo.BoundingBox2 footprint,
            String cause,
            String culture,
            com.livingmods.common.id.PhysicalIntentId zoneId,
            com.livingmods.common.id.PhysicalIntentId roadId,
            String entranceFacing,
            SimulationContext ctx
    ) {
        var physical = state.dynamicPhysical();
        var structureId = com.livingmods.common.id.StructureId.deterministic(
                state.seed(), physical.structures().size() + physical.intents().size() + 410_000L);
        var intentId = com.livingmods.common.id.PhysicalIntentId.deterministic(
                state.seed(), physical.intents().size() + 420_000L + role.ordinal());
        var intent = new com.livingmods.simulation.physical.PhysicalIntent(
                intentId,
                com.livingmods.common.model.PhysicalIntentType.CONSTRUCT_BUILDING,
                "player_realm",
                structureId.value(),
                Optional.of(capital.id()),
                capital.ownerKingdom(),
                Optional.of(structureId),
                Optional.of(role),
                plot,
                footprint,
                1,
                com.livingmods.common.model.PhysicalIntentStatus.READY,
                ctx.time(),
                2,
                Map.of(
                        "cause", cause,
                        "role", role.name(),
                        "entranceFacing", entranceFacing == null ? "south" : entranceFacing,
                        "reservationState", "NONE"
                ),
                Map.of(ResourceType.WOOD, 10.0, ResourceType.STONE, 8.0),
                culture
        );
        intent.addDependency(zoneId);
        intent.addDependency(roadId);
        seedChunks(intent);
        physical.putIntent(intent);
    }

    private static void seedChunks(com.livingmods.simulation.physical.PhysicalIntent intent) {
        var fp = intent.footprint();
        for (int cx = fp.minX() >> 4; cx <= fp.maxX() >> 4; cx++) {
            for (int cz = fp.minZ() >> 4; cz <= fp.maxZ() >> 4; cz++) {
                intent.markChunkPending(cx, cz);
            }
        }
    }

    private static SettlementState findClaimableSettlement(
            CanonicalWorldState state,
            BlockPos2 center,
            PlayerId player,
            FoundationRequirements req
    ) {
        SettlementState best = null;
        double bestDist = Double.MAX_VALUE;
        for (SettlementState s : state.settlements().values()) {
            if (s.ownerKingdom().isPresent()) continue;
            double d = s.center().distanceTo(center);
            if (d < bestDist && d < 256) {
                best = s;
                bestDist = d;
            }
        }
        return best;
    }

    private static SettlementState createCapitalSettlement(
            CanonicalWorldState state,
            String realmName,
            BlockPos2 center,
            CultureId cultureId,
            SimulationContext ctx
    ) {
        SettlementId id = SettlementId.deterministic(state.seed(), state.settlements().size() + 8000);
        SettlementState settlement = new SettlementState(
                id,
                (realmName == null ? "New" : realmName) + " Hold",
                SettlementTier.VILLAGE,
                SettlementRole.FARMING,
                center,
                Optional.empty(),
                true,
                null,
                65.0,
                1.0,
                40.0,
                6,
                4
        );
        state.putSettlement(settlement);
        state.stockpiles().put(id, new StockpileState(id));
        state.markets().put(id, new MarketState(id));
        return settlement;
    }

    private static CitizenId ensureSteward(
            CanonicalWorldState state,
            SettlementState capital,
            CultureId cultureId,
            SimulationContext ctx
    ) {
        for (CitizenId cid : state.citizensInSettlement(capital.id())) {
            CitizenState c = state.citizens().get(cid);
            if (c != null && c.alive() && c.isAdult(ctx.time())) {
                c.setProfession(Profession.GOVERNMENT_OFFICIAL);
                c.setRuler(true);
                capital.setRulerId(c.id());
                return c.id();
            }
        }
        CitizenId id = CitizenId.deterministic(state.seed(), state.citizens().size() + 7000);
        var hh = state.households().values().stream()
                .filter(h -> h.settlementId().equals(capital.id()))
                .findFirst()
                .orElse(null);
        com.livingmods.common.id.HouseholdId hhId = hh != null ? hh.id()
                : com.livingmods.common.id.HouseholdId.deterministic(state.seed(), state.households().size() + 7000);
        if (hh == null) {
            state.putHousehold(new com.livingmods.simulation.state.HouseholdState(hhId, capital.id(), 1, 12, 2));
        }
        CitizenState steward = new CitizenState(
                id, "Steward", "Keep", false, ctx.time().plusDays(-30 * 365L),
                cultureId, capital.id(), hhId, Profession.GOVERNMENT_OFFICIAL,
                90, 20, true, true
        );
        state.putCitizen(steward);
        capital.setRulerId(id);
        return id;
    }

    public static PlayerId demoPlayer() {
        return PlayerId.of(UUID.fromString("00000000-0000-4000-8000-000000000001"));
    }
}
