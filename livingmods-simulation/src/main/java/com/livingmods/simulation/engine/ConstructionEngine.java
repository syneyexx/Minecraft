package com.livingmods.simulation.engine;

import com.livingmods.common.event.CivilizationEventType;
import com.livingmods.common.event.HistoricalEvent;
import com.livingmods.common.geo.BlockPos2;
import com.livingmods.common.geo.BoundingBox2;
import com.livingmods.common.id.HistoricalEventId;
import com.livingmods.common.id.PhysicalIntentId;
import com.livingmods.common.id.SettlementId;
import com.livingmods.common.id.StructureId;
import com.livingmods.common.model.BuildingRole;
import com.livingmods.common.model.PhysicalIntentStatus;
import com.livingmods.common.model.PhysicalIntentType;
import com.livingmods.common.model.Profession;
import com.livingmods.common.model.ResourceType;
import com.livingmods.common.model.SettlementTier;
import com.livingmods.simulation.CanonicalWorldState;
import com.livingmods.simulation.physical.DynamicPhysicalState;
import com.livingmods.simulation.physical.DynamicSettlementGeometry;
import com.livingmods.simulation.physical.PhysicalIntent;
import com.livingmods.simulation.state.CitizenState;
import com.livingmods.simulation.state.SettlementState;
import com.livingmods.simulation.state.StockpileState;
import com.livingmods.simulation.tick.RegionalWork;
import com.livingmods.simulation.tick.SimulationContext;

import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;

/**
 * Construction planning authority: canonical demand → resource reservation → physical intent.
 * Does not invent Minecraft blocks; realization happens via Physical Reconciliation.
 */
public final class ConstructionEngine implements SimulationSubsystem {
    private static final int PRIORITY_CRITICAL = 3;
    private static final int PRIORITY_NORMAL = 4;
    private static final int PRIORITY_DEFENSE = 3;

    @Override
    public String name() { return "construction"; }

    @Override
    public void phase1Regional(CanonicalWorldState state, RegionalWork work, SimulationContext ctx) {
        if (!ctx.schedule().runGovernment()) return;

        for (SettlementState s : state.settlements().values()) {
            if (!s.region().equals(work.region())) continue;
            work.enqueueCommit(() -> planSettlementConstruction(state, s, ctx));
        }
    }

    @Override
    public void phase2Global(CanonicalWorldState state, SimulationContext ctx) {
        if (!ctx.schedule().runGovernment()) return;
        advanceIntentLifecycle(state, ctx);
    }

    private static void planSettlementConstruction(
            CanonicalWorldState state,
            SettlementState settlement,
            SimulationContext ctx
    ) {
        DynamicPhysicalState physical = state.dynamicPhysical();
        ensureSettlementGeometry(physical, settlement);

        int population = state.citizensInSettlement(settlement.id()).size();
        int dynamicHousing = physical.activeHousingUnits(settlement.id());
        int housing = Math.max(settlement.housingUnits(), dynamicHousing);
        int occupied = Math.max(1, population);
        double occupancy = occupied / (double) Math.max(1, housing);
        double labour = labourCapacity(state, settlement);

        // Housing shortage → house construction demand.
        if (occupancy > 0.85 || settlement.developmentDeficit() > 0.5) {
            maybeOfferBuilding(state, settlement, BuildingRole.HOUSE, PhysicalIntentType.CONSTRUCT_BUILDING,
                    PRIORITY_NORMAL, labour, ctx);
        }
        if (occupancy > 1.05 && settlement.tier().ordinal() >= SettlementTier.TOWN.ordinal()) {
            maybeOfferBuilding(state, settlement, BuildingRole.TOWNHOUSE, PhysicalIntentType.CONSTRUCT_BUILDING,
                    PRIORITY_NORMAL, labour, ctx);
        }
        if (settlement.tier().ordinal() >= SettlementTier.CITY.ordinal() && occupancy > 1.15) {
            maybeOfferBuilding(state, settlement, BuildingRole.MANOR, PhysicalIntentType.EXPAND_SETTLEMENT,
                    PRIORITY_NORMAL, labour, ctx);
        }

        // Profession / economy demand.
        if (settlement.employedSlots() < population * 0.55) {
            maybeOfferBuilding(state, settlement, BuildingRole.WORKSHOP, PhysicalIntentType.CONSTRUCT_BUILDING,
                    PRIORITY_NORMAL, labour, ctx);
        }
        StockpileState stock = state.stockpiles().get(settlement.id());
        if (stock != null && stock.get(ResourceType.GRAIN) > 80
                && !physical.hasOpenIntent(settlement.id(), PhysicalIntentType.CONSTRUCT_BUILDING)) {
            // Warehouse when surplus goods pile up.
            if (stock.get(ResourceType.WOOD) > 25) {
                maybeOfferBuilding(state, settlement, BuildingRole.WAREHOUSE, PhysicalIntentType.CONSTRUCT_BUILDING,
                        PRIORITY_NORMAL, labour, ctx);
            }
        }

        // Market / civic growth with tier.
        if (settlement.tier().ordinal() >= SettlementTier.VILLAGE.ordinal()
                && settlement.developmentDeficit() > 0.8) {
            maybeOfferBuilding(state, settlement, BuildingRole.MARKET_STALL, PhysicalIntentType.CONSTRUCT_BUILDING,
                    PRIORITY_NORMAL, labour, ctx);
        }
        if (settlement.tier().ordinal() >= SettlementTier.TOWN.ordinal()) {
            maybeOfferBuilding(state, settlement, BuildingRole.TEMPLE, PhysicalIntentType.CONSTRUCT_BUILDING,
                    PRIORITY_NORMAL, labour * 0.8, ctx);
            maybeOfferBuilding(state, settlement, BuildingRole.SCHOOL, PhysicalIntentType.CONSTRUCT_BUILDING,
                    PRIORITY_NORMAL, labour * 0.8, ctx);
        }
        if (settlement.hunger() > 0.35 || (stock != null && stock.get(ResourceType.MEDICINE) < 3)) {
            maybeOfferBuilding(state, settlement, BuildingRole.CLINIC, PhysicalIntentType.CONSTRUCT_BUILDING,
                    PRIORITY_CRITICAL, labour, ctx);
        }

        // Security / fortification.
        if (settlement.security() < 0.35 || settlement.unrest() > 0.5
                || settlement.tier().ordinal() >= SettlementTier.TOWN.ordinal()) {
            maybeOfferBuilding(state, settlement, BuildingRole.GUARDHOUSE, PhysicalIntentType.CREATE_FORTIFICATION,
                    PRIORITY_DEFENSE, labour, ctx);
        }
        if (settlement.tier().ordinal() >= SettlementTier.TOWN.ordinal() && settlement.security() < 0.55) {
            maybeOfferWall(state, settlement, labour, ctx);
        }

        // War damage → repair demand.
        for (var rec : physical.structures().values()) {
            if (!rec.settlementId().equals(settlement.id())) continue;
            if (rec.integrity() < 0.7 && rec.status().contributesCapacity()) {
                maybeOfferRepair(state, settlement, rec.structureId(), rec.role(), labour, ctx);
            }
        }

        // Expansion when housing pressure + labour available.
        if (occupancy > 1.1 && labour > 2.0
                && !physical.hasOpenIntent(settlement.id(), PhysicalIntentType.EXPAND_SETTLEMENT)
                && !physical.hasOpenIntent(settlement.id(), PhysicalIntentType.CREATE_DISTRICT)) {
            offerExpansion(state, settlement, ctx);
        }
    }

    private static void maybeOfferBuilding(
            CanonicalWorldState state,
            SettlementState settlement,
            BuildingRole role,
            PhysicalIntentType type,
            int priority,
            double labour,
            SimulationContext ctx
    ) {
        DynamicPhysicalState physical = state.dynamicPhysical();
        String tag = "role:" + role.name();
        for (PhysicalIntent existing : physical.intents().values()) {
            if (existing.status().isActive()
                    && existing.settlementId().isPresent()
                    && existing.settlementId().get().equals(settlement.id())
                    && existing.buildingRole().isPresent()
                    && existing.buildingRole().get() == role) {
                return;
            }
            if (existing.status().isActive()
                    && tag.equals(existing.provenance().get("demandTag"))) {
                return;
            }
        }
        if (labour < labourRequired(role)) {
            settlement.setDevelopmentDeficit(settlement.developmentDeficit() + 0.05);
            return;
        }

        Map<ResourceType, Double> costs = constructionCosts(role);
        StockpileState stock = state.stockpiles().get(settlement.id());
        if (stock == null || !reserveResources(stock, costs)) {
            settlement.setDevelopmentDeficit(Math.max(settlement.developmentDeficit(), 0.75));
            return;
        }

        StructureId structureId = StructureId.deterministic(
                state.seed(),
                state.dynamicPhysical().structures().size()
                        + state.dynamicPhysical().intents().size() + 50_000L + role.ordinal());
        BlockPos2 plot = nextPlot(state, settlement, role);
        BoundingBox2 footprint = footprintFor(role, plot);
        PhysicalIntentId intentId = PhysicalIntentId.deterministic(
                state.seed(),
                physical.intents().size() + 90_000L + role.ordinal() * 17L);

        PhysicalIntent intent = new PhysicalIntent(
                intentId,
                type,
                "construction",
                structureId.value(),
                Optional.of(settlement.id()),
                settlement.ownerKingdom(),
                Optional.of(structureId),
                Optional.of(role),
                plot,
                footprint,
                1,
                PhysicalIntentStatus.PLANNED,
                ctx.time(),
                priority,
                Map.of(
                        "demandTag", tag,
                        "cause", occupancyCause(state, settlement),
                        "role", role.name()
                ),
                costs,
                cultureHint(settlement)
        );
        seedChunkSlices(intent, footprint);
        if (resourcesFullyReserved(costs)) {
            intent.transitionTo(PhysicalIntentStatus.READY, ctx.time().absoluteTicks(), null);
        } else {
            intent.transitionTo(PhysicalIntentStatus.BLOCKED, ctx.time().absoluteTicks(), "awaiting_resources");
        }
        physical.putIntent(intent);
        settlement.setDevelopmentDeficit(Math.max(0, settlement.developmentDeficit() - 0.15));
    }

    private static void maybeOfferWall(
            CanonicalWorldState state,
            SettlementState settlement,
            double labour,
            SimulationContext ctx
    ) {
        DynamicPhysicalState physical = state.dynamicPhysical();
        if (physical.hasOpenIntent(settlement.id(), PhysicalIntentType.BUILD_WALL)) {
            return;
        }
        if (labour < 3.0) return;
        Map<ResourceType, Double> costs = Map.of(
                ResourceType.STONE, 40.0,
                ResourceType.WOOD, 15.0,
                ResourceType.IRON, 5.0
        );
        StockpileState stock = state.stockpiles().get(settlement.id());
        if (stock == null || !reserveResources(stock, costs)) {
            return;
        }
        DynamicSettlementGeometry geo = physical.settlementGeometry().get(settlement.id());
        BoundingBox2 boundary = geo != null ? geo.boundary() : BoundingBox2.around(settlement.center(), 48);
        PhysicalIntentId intentId = PhysicalIntentId.deterministic(state.seed(), physical.intents().size() + 120_000L);
        PhysicalIntent intent = new PhysicalIntent(
                intentId,
                PhysicalIntentType.BUILD_WALL,
                "construction",
                settlement.id().value(),
                Optional.of(settlement.id()),
                settlement.ownerKingdom(),
                Optional.empty(),
                Optional.of(BuildingRole.WALL_SEGMENT),
                boundary.center(),
                boundary.expand(4),
                1,
                PhysicalIntentStatus.READY,
                ctx.time(),
                PRIORITY_DEFENSE,
                Map.of("cause", "security", "role", BuildingRole.WALL_SEGMENT.name()),
                new EnumMap<>(costs),
                cultureHint(settlement)
        );
        seedChunkSlices(intent, intent.footprint());
        physical.putIntent(intent);
    }

    private static void maybeOfferRepair(
            CanonicalWorldState state,
            SettlementState settlement,
            StructureId structureId,
            BuildingRole role,
            double labour,
            SimulationContext ctx
    ) {
        DynamicPhysicalState physical = state.dynamicPhysical();
        if (physical.hasOpenIntent(settlement.id(), PhysicalIntentType.REPAIR_STRUCTURE)) {
            // Allow one repair intent per settlement per cadence; avoid spam.
            for (PhysicalIntent existing : physical.intents().values()) {
                if (existing.type() == PhysicalIntentType.REPAIR_STRUCTURE
                        && existing.status().isActive()
                        && existing.structureId().isPresent()
                        && existing.structureId().get().equals(structureId)) {
                    return;
                }
            }
        }
        if (labour < 1.0) return;
        Map<ResourceType, Double> costs = Map.of(
                ResourceType.WOOD, 8.0,
                ResourceType.STONE, 8.0,
                ResourceType.TOOLS, 1.0
        );
        StockpileState stock = state.stockpiles().get(settlement.id());
        if (stock == null || !reserveResources(stock, costs)) {
            return;
        }
        var rec = physical.structures().get(structureId);
        if (rec == null) return;
        PhysicalIntentId intentId = PhysicalIntentId.deterministic(
                state.seed(), physical.intents().size() + structureId.hashCode());
        PhysicalIntent intent = new PhysicalIntent(
                intentId,
                PhysicalIntentType.REPAIR_STRUCTURE,
                "construction",
                structureId.value(),
                Optional.of(settlement.id()),
                settlement.ownerKingdom(),
                Optional.of(structureId),
                Optional.of(role),
                rec.footprint().center(),
                rec.footprint(),
                rec.physicalRevision() + 1,
                PhysicalIntentStatus.READY,
                ctx.time(),
                PRIORITY_CRITICAL,
                Map.of("cause", "war_damage", "role", role.name()),
                new EnumMap<>(costs),
                rec.cultureKey()
        );
        seedChunkSlices(intent, rec.footprint());
        physical.putIntent(intent);
    }

    private static void offerExpansion(
            CanonicalWorldState state,
            SettlementState settlement,
            SimulationContext ctx
    ) {
        DynamicPhysicalState physical = state.dynamicPhysical();
        DynamicSettlementGeometry geo = physical.settlementGeometry().get(settlement.id());
        if (geo == null) return;
        Map<ResourceType, Double> costs = Map.of(
                ResourceType.WOOD, 20.0,
                ResourceType.STONE, 12.0,
                ResourceType.TOOLS, 2.0
        );
        StockpileState stock = state.stockpiles().get(settlement.id());
        if (stock == null || !reserveResources(stock, costs)) {
            return;
        }
        int gen = geo.expansionGeneration() + 1;
        BlockPos2 anchor = BlockPos2.of(
                settlement.center().x() + (gen % 2 == 0 ? 32 : -32),
                settlement.center().z() + (gen % 3 == 0 ? 32 : -24)
        );
        geo.expansionAnchors().add(anchor);
        geo.bumpExpansionGeneration();
        BoundingBox2 expanded = geo.boundary().expand(16);
        geo.setBoundary(expanded);

        PhysicalIntentId intentId = PhysicalIntentId.deterministic(state.seed(), physical.intents().size() + 150_000L);
        PhysicalIntent intent = new PhysicalIntent(
                intentId,
                PhysicalIntentType.CREATE_DISTRICT,
                "construction",
                settlement.id().value(),
                Optional.of(settlement.id()),
                settlement.ownerKingdom(),
                Optional.empty(),
                Optional.empty(),
                anchor,
                BoundingBox2.around(anchor, 20),
                gen,
                PhysicalIntentStatus.READY,
                ctx.time(),
                PRIORITY_NORMAL,
                Map.of("cause", "population_growth", "generation", String.valueOf(gen)),
                new EnumMap<>(costs),
                cultureHint(settlement)
        );
        seedChunkSlices(intent, intent.footprint());
        physical.putIntent(intent);

        // Accompanying road stub.
        PhysicalIntentId roadId = PhysicalIntentId.deterministic(state.seed(), physical.intents().size() + 151_000L);
        PhysicalIntent road = new PhysicalIntent(
                roadId,
                PhysicalIntentType.EXTEND_ROAD,
                "construction",
                settlement.id().value(),
                Optional.of(settlement.id()),
                settlement.ownerKingdom(),
                Optional.empty(),
                Optional.empty(),
                settlement.center(),
                BoundingBox2.of(
                        Math.min(settlement.center().x(), anchor.x()) - 2,
                        Math.min(settlement.center().z(), anchor.z()) - 2,
                        Math.max(settlement.center().x(), anchor.x()) + 2,
                        Math.max(settlement.center().z(), anchor.z()) + 2
                ),
                gen,
                PhysicalIntentStatus.READY,
                ctx.time(),
                PRIORITY_NORMAL,
                Map.of("cause", "district_access", "from", "center", "to", "anchor"),
                Map.of(ResourceType.STONE, 6.0, ResourceType.WOOD, 4.0),
                cultureHint(settlement)
        );
        road.addDependency(intentId);
        seedChunkSlices(road, road.footprint());
        physical.putIntent(road);

        state.appendHistory(new HistoricalEvent(
                HistoricalEventId.deterministic(state.seed(), state.history().size()),
                CivilizationEventType.SETTLEMENT_DEVELOPMENT_CHANGED,
                ctx.time(),
                "Settlement expansion",
                settlement.name() + " plans a new district",
                Optional.of(anchor),
                Map.of("settlement", settlement.id().toString(), "generation", String.valueOf(gen))
        ));
    }

    private static void advanceIntentLifecycle(CanonicalWorldState state, SimulationContext ctx) {
        DynamicPhysicalState physical = state.dynamicPhysical();
        for (PhysicalIntent intent : physical.intents().values()) {
            if (intent.status() == PhysicalIntentStatus.PLANNED) {
                StockpileState stock = intent.settlementId()
                        .map(state.stockpiles()::get)
                        .orElse(null);
                if (stock != null && resourcesAvailable(stock, intent.reservedCosts())) {
                    reserveResources(stock, intent.reservedCosts());
                    intent.transitionTo(PhysicalIntentStatus.READY, ctx.time().absoluteTicks(), null);
                } else {
                    intent.transitionTo(PhysicalIntentStatus.BLOCKED, ctx.time().absoluteTicks(), "awaiting_resources");
                }
            } else if (intent.status() == PhysicalIntentStatus.BLOCKED) {
                StockpileState stock = intent.settlementId()
                        .map(state.stockpiles()::get)
                        .orElse(null);
                if (stock != null && resourcesAvailable(stock, intent.reservedCosts())) {
                    reserveResources(stock, intent.reservedCosts());
                    intent.transitionTo(PhysicalIntentStatus.READY, ctx.time().absoluteTicks(), null);
                }
            } else if (intent.status() == PhysicalIntentStatus.FAILED_RETRYABLE
                    && intent.retryCount() < PhysicalIntent.MAX_RETRIES) {
                intent.transitionTo(PhysicalIntentStatus.READY, ctx.time().absoluteTicks(), "retry");
            }
        }
    }

    private static void ensureSettlementGeometry(DynamicPhysicalState physical, SettlementState settlement) {
        if (physical.settlementGeometry().containsKey(settlement.id())) {
            return;
        }
        int radius = switch (settlement.tier()) {
            case CAMP -> 16;
            case HAMLET -> 24;
            case VILLAGE -> 40;
            case TOWN -> 64;
            case CITY -> 96;
            case METROPOLIS, CAPITAL -> 128;
        };
        physical.putSettlementGeometry(new DynamicSettlementGeometry(
                settlement.id(),
                settlement.center(),
                BoundingBox2.around(settlement.center(), radius),
                false
        ));
    }

    private static double labourCapacity(CanonicalWorldState state, SettlementState settlement) {
        double builders = 0;
        double craftsmen = 0;
        double adults = 0;
        for (var cid : state.citizensInSettlement(settlement.id())) {
            CitizenState c = state.citizens().get(cid);
            if (c == null || !c.alive()) continue;
            adults++;
            if (c.profession() == Profession.BUILDER) builders++;
            if (c.profession() == Profession.ARTISAN || c.profession() == Profession.BLACKSMITH) craftsmen++;
        }
        double warPenalty = settlement.unrest() * 0.4 + (1.0 - settlement.security()) * 0.2;
        double diseasePenalty = settlement.hunger() * 0.3;
        double base = builders * 2.0 + craftsmen * 1.2 + adults * 0.05;
        return Math.max(0, (base + settlement.physicalCapacity() * 0.02) * (1.0 - warPenalty - diseasePenalty));
    }

    private static double labourRequired(BuildingRole role) {
        return switch (role) {
            case HOUSE, FARMHOUSE, MARKET_STALL, WELL -> 1.0;
            case TOWNHOUSE, SHOP, WORKSHOP, WAREHOUSE, GUARDHOUSE, CLINIC -> 2.0;
            case TEMPLE, SCHOOL, BARRACKS, MARKET_HALL, TOWER -> 3.0;
            case MANOR, GATEHOUSE, WALL_SEGMENT -> 4.0;
            default -> 2.5;
        };
    }

    private static Map<ResourceType, Double> constructionCosts(BuildingRole role) {
        EnumMap<ResourceType, Double> costs = new EnumMap<>(ResourceType.class);
        switch (role) {
            case HOUSE, FARMHOUSE -> {
                costs.put(ResourceType.WOOD, 12.0);
                costs.put(ResourceType.STONE, 6.0);
            }
            case TOWNHOUSE, SHOP -> {
                costs.put(ResourceType.WOOD, 18.0);
                costs.put(ResourceType.STONE, 12.0);
                costs.put(ResourceType.IRON, 2.0);
            }
            case WORKSHOP, SMITHY, WAREHOUSE -> {
                costs.put(ResourceType.WOOD, 16.0);
                costs.put(ResourceType.STONE, 14.0);
                costs.put(ResourceType.IRON, 4.0);
                costs.put(ResourceType.TOOLS, 2.0);
            }
            case TEMPLE, SCHOOL, CLINIC, BARRACKS -> {
                costs.put(ResourceType.WOOD, 20.0);
                costs.put(ResourceType.STONE, 25.0);
                costs.put(ResourceType.IRON, 3.0);
            }
            case GUARDHOUSE, TOWER, GATEHOUSE, WALL_SEGMENT -> {
                costs.put(ResourceType.STONE, 30.0);
                costs.put(ResourceType.WOOD, 10.0);
                costs.put(ResourceType.IRON, 5.0);
            }
            case MANOR, PALACE -> {
                costs.put(ResourceType.WOOD, 30.0);
                costs.put(ResourceType.STONE, 40.0);
                costs.put(ResourceType.IRON, 8.0);
                costs.put(ResourceType.TOOLS, 3.0);
            }
            default -> {
                costs.put(ResourceType.WOOD, 10.0);
                costs.put(ResourceType.STONE, 8.0);
            }
        }
        return costs;
    }

    private static boolean reserveResources(StockpileState stock, Map<ResourceType, Double> costs) {
        if (!resourcesAvailable(stock, costs)) {
            return false;
        }
        for (Map.Entry<ResourceType, Double> e : costs.entrySet()) {
            stock.add(e.getKey(), -e.getValue());
        }
        return true;
    }

    private static boolean resourcesAvailable(StockpileState stock, Map<ResourceType, Double> costs) {
        for (Map.Entry<ResourceType, Double> e : costs.entrySet()) {
            if (stock.get(e.getKey()) + 1e-6 < e.getValue()) {
                return false;
            }
        }
        return true;
    }

    private static boolean resourcesFullyReserved(Map<ResourceType, Double> costs) {
        return costs != null && !costs.isEmpty();
    }

    private static BlockPos2 nextPlot(CanonicalWorldState state, SettlementState settlement, BuildingRole role) {
        DynamicSettlementGeometry geo = state.dynamicPhysical().settlementGeometry().get(settlement.id());
        long h = settlement.id().value().getMostSignificantBits()
                ^ ((long) role.ordinal() << 8)
                ^ state.dynamicPhysical().intents().size();
        int ring = 18 + (int) (Math.abs(h) % 28);
        int angle = (int) (Math.abs(h >> 8) % 360);
        double rad = Math.toRadians(angle);
        int x = settlement.center().x() + (int) Math.round(Math.cos(rad) * ring);
        int z = settlement.center().z() + (int) Math.round(Math.sin(rad) * ring);
        if (geo != null && !geo.boundary().contains(x, z)) {
            // Prefer inside expanded boundary; push toward edge instead of far outside.
            BoundingBox2 b = geo.boundary();
            x = Math.max(b.minX() + 4, Math.min(b.maxX() - 4, x));
            z = Math.max(b.minZ() + 4, Math.min(b.maxZ() - 4, z));
        }
        return BlockPos2.of(x, z);
    }

    private static BoundingBox2 footprintFor(BuildingRole role, BlockPos2 plot) {
        int half = switch (role) {
            case HOUSE, MARKET_STALL, WELL -> 3;
            case TOWNHOUSE, SHOP, WORKSHOP, GUARDHOUSE, CLINIC -> 4;
            case WAREHOUSE, TEMPLE, SCHOOL, BARRACKS, SMITHY -> 5;
            case MANOR, MARKET_HALL, GATEHOUSE -> 6;
            case WALL_SEGMENT, TOWER -> 2;
            default -> 4;
        };
        return BoundingBox2.around(plot, half);
    }

    private static void seedChunkSlices(PhysicalIntent intent, BoundingBox2 footprint) {
        int minCx = footprint.minX() >> 4;
        int maxCx = footprint.maxX() >> 4;
        int minCz = footprint.minZ() >> 4;
        int maxCz = footprint.maxZ() >> 4;
        for (int cx = minCx; cx <= maxCx; cx++) {
            for (int cz = minCz; cz <= maxCz; cz++) {
                intent.markChunkPending(cx, cz);
            }
        }
    }

    private static String occupancyCause(CanonicalWorldState state, SettlementState settlement) {
        int pop = state.citizensInSettlement(settlement.id()).size();
        if (pop > settlement.housingUnits()) return "housing_shortage";
        if (settlement.developmentDeficit() > 0.5) return "development_deficit";
        return "growth";
    }

    private static String cultureHint(SettlementState settlement) {
        return settlement.ownerKingdom().map(k -> k.value().toString()).orElse("avalon");
    }
}
