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
import com.livingmods.simulation.physical.DynamicUrbanPlanner;
import com.livingmods.simulation.physical.PhysicalIntent;
import com.livingmods.simulation.state.CitizenState;
import com.livingmods.simulation.state.SettlementState;
import com.livingmods.simulation.state.StockpileState;
import com.livingmods.simulation.tick.RegionalWork;
import com.livingmods.simulation.tick.SimulationContext;

import java.util.EnumMap;
import java.util.LinkedHashMap;
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

        // Player realm construction policy raises planning priority (does not invent deficit).
        double constructionPolicy = playerConstructionPolicy(state, settlement);
        int priorityBoost = constructionPolicy >= 0.7 ? -1 : constructionPolicy <= 0.2 ? 1 : 0;

        // Housing shortage → house construction demand.
        if (occupancy > 0.85 || settlement.developmentDeficit() > 0.5 || constructionPolicy >= 0.6) {
            maybeOfferBuilding(state, settlement, BuildingRole.HOUSE, PhysicalIntentType.CONSTRUCT_BUILDING,
                    Math.max(1, PRIORITY_NORMAL + priorityBoost), labour, ctx);
        }
        if (occupancy > 1.05 && settlement.tier().ordinal() >= SettlementTier.TOWN.ordinal()) {
            maybeOfferBuilding(state, settlement, BuildingRole.TOWNHOUSE, PhysicalIntentType.CONSTRUCT_BUILDING,
                    PRIORITY_NORMAL, labour, ctx);
        }
        if (settlement.tier().ordinal() >= SettlementTier.CITY.ordinal() && occupancy > 1.15) {
            maybeOfferBuilding(state, settlement, BuildingRole.MANOR, PhysicalIntentType.CONSTRUCT_BUILDING,
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

        // Security / fortification — distinct intent set, not a single generic building.
        if (settlement.security() < 0.35 || settlement.unrest() > 0.5) {
            maybeOfferBuilding(state, settlement, BuildingRole.GUARDHOUSE, PhysicalIntentType.CONSTRUCT_BUILDING,
                    PRIORITY_DEFENSE, labour, ctx);
        }
        if (settlement.tier().ordinal() >= SettlementTier.TOWN.ordinal() && settlement.security() < 0.55) {
            maybeOfferFortification(state, settlement, labour, ctx);
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
        String culture = CultureResolver.forSettlement(state, settlement);
        DynamicUrbanPlanner planner = new DynamicUrbanPlanner(state.seed());
        Optional<DynamicUrbanPlanner.PlotCandidate> plotOpt = planner.chooseBuildingPlot(
                state, settlement, role, culture, physical.intents().size() + role.ordinal());
        if (plotOpt.isEmpty()) {
            // Bounded replan: try expansion edge once, then block demand.
            DynamicSettlementGeometry geo = physical.settlementGeometry().get(settlement.id());
            if (geo != null) {
                geo.setBoundary(geo.boundary().expand(8));
                plotOpt = planner.chooseBuildingPlot(
                        state, settlement, role, culture, physical.intents().size() + 7_000L + role.ordinal());
            }
            if (plotOpt.isEmpty()) {
                refundReservation(stock, costs);
                settlement.setDevelopmentDeficit(Math.max(settlement.developmentDeficit(), 0.9));
                return;
            }
        }
        DynamicUrbanPlanner.PlotCandidate plot = plotOpt.get();
        BlockPos2 plotPos = plot.center();
        BoundingBox2 footprint = plot.footprint();
        PhysicalIntentId intentId = PhysicalIntentId.deterministic(
                state.seed(),
                physical.intents().size() + 90_000L + role.ordinal() * 17L);

        Map<String, String> provenance = new LinkedHashMap<>();
        provenance.put("demandTag", tag);
        provenance.put("cause", occupancyCause(state, settlement));
        provenance.put("role", role.name());
        provenance.put("entranceFacing", plot.entranceFacing());
        provenance.put("accessX", String.valueOf(plot.accessRoadPoint().x()));
        provenance.put("accessZ", String.valueOf(plot.accessRoadPoint().z()));
        provenance.put("reservationState", "RESERVED");
        if (plot.blueprint() != null) {
            provenance.put("floors", String.valueOf(plot.blueprint().interior().floorCount()));
            provenance.put("template", plot.blueprint().building().paletteKey() == null
                    ? "default" : plot.blueprint().building().paletteKey());
        }

        PhysicalIntent intent = new PhysicalIntent(
                intentId,
                type,
                "construction",
                structureId.value(),
                Optional.of(settlement.id()),
                settlement.ownerKingdom(),
                Optional.of(structureId),
                Optional.of(role),
                plotPos,
                footprint,
                1,
                PhysicalIntentStatus.PLANNED,
                ctx.time(),
                priority,
                provenance,
                costs,
                culture
        );
        seedChunkSlices(intent, footprint);
        // Access path stub if building is distant from circulation.
        if (plot.accessRoadPoint().distanceTo(plotPos) > 6) {
            PhysicalIntentId pathId = PhysicalIntentId.deterministic(
                    state.seed(), physical.intents().size() + 91_000L + role.ordinal());
            PhysicalIntent access = new PhysicalIntent(
                    pathId,
                    PhysicalIntentType.EXTEND_ROAD,
                    "construction",
                    settlement.id().value(),
                    Optional.of(settlement.id()),
                    settlement.ownerKingdom(),
                    Optional.empty(),
                    Optional.empty(),
                    plot.accessRoadPoint(),
                    BoundingBox2.of(
                            Math.min(plot.accessRoadPoint().x(), plotPos.x()) - 1,
                            Math.min(plot.accessRoadPoint().z(), plotPos.z()) - 1,
                            Math.max(plot.accessRoadPoint().x(), plotPos.x()) + 1,
                            Math.max(plot.accessRoadPoint().z(), plotPos.z()) + 1
                    ),
                    1,
                    PhysicalIntentStatus.READY,
                    ctx.time(),
                    priority,
                    Map.of("cause", "building_access", "reservationState", "NONE"),
                    Map.of(),
                    culture
            );
            DynamicUrbanPlanner.RoadRoute route = planner.planRoad(
                    state, settlement, plot.accessRoadPoint(), plotPos, culture);
            encodeRoute(access, route);
            seedChunkSlices(access, access.footprint());
            physical.putIntent(access);
            intent.addDependency(pathId);
        }
        intent.transitionTo(PhysicalIntentStatus.READY, ctx.time().absoluteTicks(), null);
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
        String culture = CultureResolver.forSettlement(state, settlement);
        DynamicUrbanPlanner.WallPlan wallPlan = new DynamicUrbanPlanner(state.seed())
                .planWall(settlement, geo, culture);
        BoundingBox2 boundary = wallPlan.boundary();
        PhysicalIntentId intentId = PhysicalIntentId.deterministic(state.seed(), physical.intents().size() + 120_000L);
        Map<String, String> provenance = new LinkedHashMap<>();
        provenance.put("cause", "security");
        provenance.put("role", BuildingRole.WALL_SEGMENT.name());
        provenance.put("reservationState", "RESERVED");
        provenance.put("perimeterPoints", String.valueOf(wallPlan.perimeter().size()));
        provenance.put("gateCount", String.valueOf(wallPlan.gatePositions().size()));
        encodePoints(provenance, "wall", wallPlan.perimeter());
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
                provenance,
                new EnumMap<>(costs),
                culture
        );
        seedChunkSlices(intent, intent.footprint());
        physical.putIntent(intent);

        // Every major road/wall crossing needs an explicit gate intent dependent on the wall.
        int gateOrdinal = 0;
        for (BlockPos2 gate : wallPlan.gatePositions()) {
            PhysicalIntentId gateId = PhysicalIntentId.deterministic(
                    state.seed(), physical.intents().size() + 121_000L + gateOrdinal++);
            String orientation = gateOrientation(boundary, gate);
            PhysicalIntent gateIntent = new PhysicalIntent(
                    gateId,
                    PhysicalIntentType.BUILD_GATE,
                    "construction",
                    settlement.id().value(),
                    Optional.of(settlement.id()),
                    settlement.ownerKingdom(),
                    Optional.empty(),
                    Optional.of(BuildingRole.GATEHOUSE),
                    gate,
                    BoundingBox2.around(gate, 3),
                    1,
                    PhysicalIntentStatus.READY,
                    ctx.time(),
                    PRIORITY_DEFENSE,
                    Map.of(
                            "cause", "wall_crossing",
                            "orientation", orientation,
                            "reservationState", "NONE",
                            "parentWall", intentId.value().toString()
                    ),
                    Map.of(),
                    culture
            );
            gateIntent.addDependency(intentId);
            seedChunkSlices(gateIntent, gateIntent.footprint());
            physical.putIntent(gateIntent);
        }
    }

    private static void maybeOfferFortification(
            CanonicalWorldState state,
            SettlementState settlement,
            double labour,
            SimulationContext ctx
    ) {
        DynamicPhysicalState physical = state.dynamicPhysical();
        if (physical.hasOpenIntent(settlement.id(), PhysicalIntentType.CREATE_FORTIFICATION)) {
            return;
        }
        if (labour < 3.5) return;
        Map<ResourceType, Double> costs = Map.of(
                ResourceType.STONE, 35.0,
                ResourceType.WOOD, 18.0,
                ResourceType.IRON, 6.0
        );
        StockpileState stock = state.stockpiles().get(settlement.id());
        if (stock == null || !reserveResources(stock, costs)) {
            return;
        }
        String culture = CultureResolver.forSettlement(state, settlement);
        DynamicUrbanPlanner planner = new DynamicUrbanPlanner(state.seed());
        Optional<DynamicUrbanPlanner.PlotCandidate> plot = planner.chooseBuildingPlot(
                state, settlement, BuildingRole.BARRACKS, culture, physical.intents().size() + 130_000L);
        BlockPos2 center = plot.map(DynamicUrbanPlanner.PlotCandidate::center)
                .orElse(BlockPos2.of(settlement.center().x() + 20, settlement.center().z() - 16));
        BoundingBox2 keep = BoundingBox2.around(center, 8);
        StructureId structureId = StructureId.deterministic(state.seed(), physical.intents().size() + 131_000L);
        PhysicalIntentId intentId = PhysicalIntentId.deterministic(state.seed(), physical.intents().size() + 132_000L);
        PhysicalIntent fort = new PhysicalIntent(
                intentId,
                PhysicalIntentType.CREATE_FORTIFICATION,
                "construction",
                structureId.value(),
                Optional.of(settlement.id()),
                settlement.ownerKingdom(),
                Optional.of(structureId),
                Optional.of(BuildingRole.BARRACKS),
                center,
                keep,
                1,
                PhysicalIntentStatus.READY,
                ctx.time(),
                PRIORITY_DEFENSE,
                Map.of(
                        "cause", "security",
                        "components", "guardhouse,wall,gatehouse,tower,keep",
                        "reservationState", "RESERVED"
                ),
                new EnumMap<>(costs),
                culture
        );
        seedChunkSlices(fort, keep);
        physical.putIntent(fort);

        // Child tower intent.
        PhysicalIntentId towerId = PhysicalIntentId.deterministic(state.seed(), physical.intents().size() + 133_000L);
        PhysicalIntent tower = new PhysicalIntent(
                towerId,
                PhysicalIntentType.CONSTRUCT_BUILDING,
                "construction",
                StructureId.deterministic(state.seed(), physical.intents().size() + 134_000L).value(),
                Optional.of(settlement.id()),
                settlement.ownerKingdom(),
                Optional.of(StructureId.deterministic(state.seed(), physical.intents().size() + 134_000L)),
                Optional.of(BuildingRole.TOWER),
                BlockPos2.of(center.x() + 10, center.z()),
                BoundingBox2.around(BlockPos2.of(center.x() + 10, center.z()), 2),
                1,
                PhysicalIntentStatus.READY,
                ctx.time(),
                PRIORITY_DEFENSE,
                Map.of("cause", "fortification_tower", "reservationState", "NONE"),
                Map.of(),
                culture
        );
        tower.addDependency(intentId);
        seedChunkSlices(tower, tower.footprint());
        physical.putIntent(tower);
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
        String culture = CultureResolver.forSettlement(state, settlement);
        DynamicUrbanPlanner planner = new DynamicUrbanPlanner(state.seed());

        // EXPAND_SETTLEMENT: coherent urban geometry extension (not one structure).
        BlockPos2 direction = BlockPos2.of(
                settlement.center().x() + (gen % 2 == 0 ? 32 : -32),
                settlement.center().z() + (gen % 3 == 0 ? 32 : -24)
        );
        DynamicUrbanPlanner.TerrainMetrics terrain = planner.sampleTerrain(BoundingBox2.around(direction, 20));
        if (!terrain.suitable(20)) {
            // Alternate expansion direction once.
            direction = BlockPos2.of(
                    settlement.center().x() + (gen % 2 == 0 ? -36 : 36),
                    settlement.center().z() + (gen % 3 == 0 ? -28 : 28)
            );
            terrain = planner.sampleTerrain(BoundingBox2.around(direction, 20));
            if (!terrain.suitable(20)) {
                refundReservation(stock, costs);
                return;
            }
        }
        BlockPos2 anchor = direction;
        geo.expansionAnchors().add(anchor);
        geo.bumpExpansionGeneration();
        BoundingBox2 expanded = geo.boundary().expand(16);
        geo.setBoundary(expanded);

        PhysicalIntentId expandId = PhysicalIntentId.deterministic(state.seed(), physical.intents().size() + 149_000L);
        PhysicalIntent expand = new PhysicalIntent(
                expandId,
                PhysicalIntentType.EXPAND_SETTLEMENT,
                "construction",
                settlement.id().value(),
                Optional.of(settlement.id()),
                settlement.ownerKingdom(),
                Optional.empty(),
                Optional.empty(),
                anchor,
                expanded,
                gen,
                PhysicalIntentStatus.READY,
                ctx.time(),
                PRIORITY_NORMAL,
                Map.of(
                        "cause", "population_growth",
                        "generation", String.valueOf(gen),
                        "reservationState", "RESERVED",
                        "planning", "boundary_extension"
                ),
                new EnumMap<>(costs),
                culture
        );
        // Planning-meta: no block slices required for the expand intent itself.
        physical.putIntent(expand);

        DynamicUrbanPlanner.DistrictPlan districtPlan = planner.planDistrict(
                state, settlement, anchor, culture, gen);
        PhysicalIntentId districtId = PhysicalIntentId.deterministic(state.seed(), physical.intents().size() + 150_000L);
        Map<String, String> districtMeta = new LinkedHashMap<>();
        districtMeta.put("cause", "population_growth");
        districtMeta.put("generation", String.valueOf(gen));
        districtMeta.put("reservationState", "NONE");
        districtMeta.put("lotCount", String.valueOf(districtPlan.lots().size()));
        districtMeta.put("planning", "district");
        encodePoints(districtMeta, "street", districtPlan.secondaryStreets());
        PhysicalIntent district = new PhysicalIntent(
                districtId,
                PhysicalIntentType.CREATE_DISTRICT,
                "construction",
                settlement.id().value(),
                Optional.of(settlement.id()),
                settlement.ownerKingdom(),
                Optional.empty(),
                Optional.empty(),
                anchor,
                districtPlan.boundary(),
                gen,
                PhysicalIntentStatus.READY,
                ctx.time(),
                PRIORITY_NORMAL,
                districtMeta,
                Map.of(),
                culture
        );
        district.addDependency(expandId);
        physical.putIntent(district);

        // Road from existing network → junction → district (depends on district plan).
        DynamicUrbanPlanner.RoadRoute route = planner.planRoad(
                state, settlement, districtPlan.primaryConnection(), anchor, culture);
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
                routeBoundingBox(route),
                gen,
                PhysicalIntentStatus.READY,
                ctx.time(),
                PRIORITY_NORMAL,
                Map.of(
                        "cause", "district_access",
                        "from", "network",
                        "to", "anchor",
                        "connected", String.valueOf(route.connectedToExisting()),
                        "reservationState", "NONE"
                ),
                Map.of(ResourceType.STONE, 6.0, ResourceType.WOOD, 4.0),
                culture
        );
        encodeRoute(road, route);
        road.addDependency(districtId);
        seedChunkSlices(road, road.footprint());
        physical.putIntent(road);

        // Bridge intents for water crossings on the route.
        int bridgeOrdinal = 0;
        for (BoundingBox2 span : route.bridgeSpans()) {
            PhysicalIntentId bridgeId = PhysicalIntentId.deterministic(
                    state.seed(), physical.intents().size() + 152_000L + bridgeOrdinal++);
            PhysicalIntent bridge = new PhysicalIntent(
                    bridgeId,
                    PhysicalIntentType.BUILD_BRIDGE,
                    "construction",
                    settlement.id().value(),
                    Optional.of(settlement.id()),
                    settlement.ownerKingdom(),
                    Optional.empty(),
                    Optional.empty(),
                    span.center(),
                    span,
                    gen,
                    PhysicalIntentStatus.READY,
                    ctx.time(),
                    PRIORITY_NORMAL,
                    Map.of("cause", "road_crossing", "reservationState", "NONE"),
                    Map.of(ResourceType.WOOD, 8.0, ResourceType.STONE, 4.0),
                    culture
            );
            bridge.addDependency(roadId);
            seedChunkSlices(bridge, span);
            physical.putIntent(bridge);
        }

        // Child building intents on district lots (depend on road).
        int lotOrdinal = 0;
        for (BoundingBox2 lot : districtPlan.lots()) {
            if (lotOrdinal >= 4) break;
            BuildingRole role = lotOrdinal == 0 ? BuildingRole.HOUSE
                    : lotOrdinal == 1 ? BuildingRole.WORKSHOP : BuildingRole.HOUSE;
            StructureId structureId = StructureId.deterministic(
                    state.seed(), physical.intents().size() + 160_000L + lotOrdinal);
            PhysicalIntentId buildingId = PhysicalIntentId.deterministic(
                    state.seed(), physical.intents().size() + 161_000L + lotOrdinal);
            PhysicalIntent building = new PhysicalIntent(
                    buildingId,
                    PhysicalIntentType.CONSTRUCT_BUILDING,
                    "construction",
                    structureId.value(),
                    Optional.of(settlement.id()),
                    settlement.ownerKingdom(),
                    Optional.of(structureId),
                    Optional.of(role),
                    lot.center(),
                    lot,
                    gen,
                    PhysicalIntentStatus.READY,
                    ctx.time(),
                    PRIORITY_NORMAL,
                    Map.of(
                            "cause", "district_lot",
                            "role", role.name(),
                            "reservationState", "NONE",
                            "entranceFacing", "south"
                    ),
                    Map.of(ResourceType.WOOD, 8.0, ResourceType.STONE, 4.0),
                    culture
            );
            building.addDependency(roadId);
            seedChunkSlices(building, lot);
            physical.putIntent(building);
            lotOrdinal++;
        }

        // Mark district planning intent materialized once children are emitted.
        district.transitionTo(PhysicalIntentStatus.MATERIALIZING, ctx.time().absoluteTicks(), null);
        district.transitionTo(PhysicalIntentStatus.MATERIALIZED, ctx.time().absoluteTicks(), "plan_committed");
        expand.transitionTo(PhysicalIntentStatus.MATERIALIZING, ctx.time().absoluteTicks(), null);
        expand.transitionTo(PhysicalIntentStatus.MATERIALIZED, ctx.time().absoluteTicks(), "plan_committed");

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
                String reservation = intent.provenance().getOrDefault("reservationState", "");
                if ("RESERVED".equals(reservation) || intent.reservedCosts().isEmpty()) {
                    intent.transitionTo(PhysicalIntentStatus.READY, ctx.time().absoluteTicks(), null);
                } else {
                    StockpileState stock = intent.settlementId()
                            .map(state.stockpiles()::get)
                            .orElse(null);
                    if (stock != null && reserveResources(stock, intent.reservedCosts())) {
                        intent.provenance().put("reservationState", "RESERVED");
                        intent.transitionTo(PhysicalIntentStatus.READY, ctx.time().absoluteTicks(), null);
                    } else {
                        intent.transitionTo(PhysicalIntentStatus.BLOCKED, ctx.time().absoluteTicks(), "awaiting_resources");
                    }
                }
            } else if (intent.status() == PhysicalIntentStatus.BLOCKED) {
                if ("dependency_pending".equals(intent.failureReason())
                        || intent.failureReason().startsWith("dependency_")) {
                    DynamicPhysicalState.DependencyResult deps = physical.evaluateDependencies(intent);
                    if (deps.satisfied()) {
                        intent.transitionTo(PhysicalIntentStatus.READY, ctx.time().absoluteTicks(), null);
                    } else if (deps.failed()) {
                        intent.transitionTo(PhysicalIntentStatus.FAILED_TERMINAL,
                                ctx.time().absoluteTicks(), deps.reason());
                    }
                    continue;
                }
                String reservation = intent.provenance().getOrDefault("reservationState", "");
                if ("RESERVED".equals(reservation)) {
                    intent.transitionTo(PhysicalIntentStatus.READY, ctx.time().absoluteTicks(), null);
                    continue;
                }
                StockpileState stock = intent.settlementId()
                        .map(state.stockpiles()::get)
                        .orElse(null);
                if (stock != null && !"RESERVED".equals(reservation)
                        && reserveResources(stock, intent.reservedCosts())) {
                    intent.provenance().put("reservationState", "RESERVED");
                    intent.transitionTo(PhysicalIntentStatus.READY, ctx.time().absoluteTicks(), null);
                }
            } else if (intent.status() == PhysicalIntentStatus.READY
                    || intent.status() == PhysicalIntentStatus.FAILED_RETRYABLE) {
                DynamicPhysicalState.DependencyResult deps = physical.evaluateDependencies(intent);
                if (deps.failed()) {
                    intent.transitionTo(PhysicalIntentStatus.FAILED_TERMINAL,
                            ctx.time().absoluteTicks(), deps.reason());
                    refundIfReserved(state, intent, 1.0);
                } else if (!deps.satisfied()) {
                    intent.transitionTo(PhysicalIntentStatus.BLOCKED,
                            ctx.time().absoluteTicks(), deps.reason());
                } else if (intent.status() == PhysicalIntentStatus.FAILED_RETRYABLE
                        && intent.retryCount() < PhysicalIntent.MAX_RETRIES) {
                    intent.transitionTo(PhysicalIntentStatus.READY, ctx.time().absoluteTicks(), "retry");
                }
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

    private static void refundReservation(StockpileState stock, Map<ResourceType, Double> costs) {
        if (stock == null || costs == null) return;
        for (Map.Entry<ResourceType, Double> e : costs.entrySet()) {
            stock.add(e.getKey(), e.getValue());
        }
    }

    private static void refundIfReserved(CanonicalWorldState state, PhysicalIntent intent, double fraction) {
        if (!"RESERVED".equals(intent.provenance().get("reservationState"))) {
            return;
        }
        StockpileState stock = intent.settlementId().map(state.stockpiles()::get).orElse(null);
        if (stock == null) return;
        for (Map.Entry<ResourceType, Double> e : intent.reservedCosts().entrySet()) {
            stock.add(e.getKey(), e.getValue() * fraction);
        }
        intent.provenance().put("reservationState", "RELEASED");
    }

    private static void encodeRoute(PhysicalIntent intent, DynamicUrbanPlanner.RoadRoute route) {
        StringBuilder xs = new StringBuilder();
        StringBuilder zs = new StringBuilder();
        int n = Math.min(route.path().size(), DynamicUrbanPlanner.MAX_ROUTE_POINTS);
        for (int i = 0; i < n; i++) {
            if (i > 0) {
                xs.append(',');
                zs.append(',');
            }
            xs.append(route.path().get(i).x());
            zs.append(route.path().get(i).z());
        }
        intent.provenance().put("routeX", xs.toString());
        intent.provenance().put("routeZ", zs.toString());
        intent.provenance().put("routePoints", String.valueOf(n));
        intent.provenance().put("bridgeSpans", String.valueOf(route.bridgeSpans().size()));
    }

    private static void encodePoints(Map<String, String> meta, String prefix, java.util.List<BlockPos2> points) {
        StringBuilder xs = new StringBuilder();
        StringBuilder zs = new StringBuilder();
        int n = Math.min(points.size(), 64);
        for (int i = 0; i < n; i++) {
            if (i > 0) {
                xs.append(',');
                zs.append(',');
            }
            xs.append(points.get(i).x());
            zs.append(points.get(i).z());
        }
        meta.put(prefix + "X", xs.toString());
        meta.put(prefix + "Z", zs.toString());
        meta.put(prefix + "Count", String.valueOf(n));
    }

    private static BoundingBox2 routeBoundingBox(DynamicUrbanPlanner.RoadRoute route) {
        if (route.path().isEmpty()) {
            return BoundingBox2.around(BlockPos2.of(0, 0), 2);
        }
        int minX = Integer.MAX_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxZ = Integer.MIN_VALUE;
        for (BlockPos2 p : route.path()) {
            minX = Math.min(minX, p.x());
            minZ = Math.min(minZ, p.z());
            maxX = Math.max(maxX, p.x());
            maxZ = Math.max(maxZ, p.z());
        }
        return BoundingBox2.of(minX - 2, minZ - 2, maxX + 2, maxZ + 2);
    }

    private static String gateOrientation(BoundingBox2 boundary, BlockPos2 gate) {
        if (gate.z() == boundary.minZ() || gate.z() == boundary.maxZ()) {
            return "north_south";
        }
        return "east_west";
    }

    /** Construction policy for player-ruled kingdoms — durable input, not instant deficit bump. */
    private static double playerConstructionPolicy(CanonicalWorldState state, SettlementState settlement) {
        if (settlement.ownerKingdom().isEmpty()) return 0.3;
        var kingdomId = settlement.ownerKingdom().get();
        for (var e : state.playerReputation().ruledKingdoms().entrySet()) {
            if (kingdomId.equals(e.getValue())) {
                return state.playerReputation().policy(e.getKey(), "construction", 0.3);
            }
        }
        return 0.3;
    }
}
