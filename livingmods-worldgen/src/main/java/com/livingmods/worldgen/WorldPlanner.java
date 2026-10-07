package com.livingmods.worldgen;

import com.livingmods.common.config.LivingModsConfig;
import com.livingmods.common.culture.CultureRegistry;
import com.livingmods.common.geo.RegionCoord;
import com.livingmods.common.id.KingdomId;
import com.livingmods.common.util.Hashing;
import com.livingmods.worldgen.plan.PlannedBanditCamp;
import com.livingmods.worldgen.plan.PlannedKingdom;
import com.livingmods.worldgen.plan.PlannedResourceSite;
import com.livingmods.worldgen.plan.PlannedRoad;
import com.livingmods.worldgen.plan.PlannedRuin;
import com.livingmods.worldgen.plan.PlannedSettlement;
import com.livingmods.worldgen.plan.WorldPlan;
import com.livingmods.worldgen.planner.KingdomPlanner;
import com.livingmods.worldgen.planner.ResourcePlanner;
import com.livingmods.worldgen.planner.RoadPlanner;
import com.livingmods.worldgen.planner.RuinPlanner;
import com.livingmods.worldgen.planner.SettlementPlanner;
import com.livingmods.worldgen.planner.UrbanPlanner;
import com.livingmods.worldgen.planner.WizardTreesPlanner;
import com.livingmods.worldgen.terrain.RegionTerrainSummary;
import com.livingmods.worldgen.terrain.TerrainAnalyzer;
import com.livingmods.worldgen.terrain.TerrainProvider;
import com.livingmods.worldgen.territory.TerritoryMap;
import com.livingmods.worldgen.validation.WorldPlanValidator;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Deterministic civilization world planning pipeline.
 * Does NOT depend on sidecar availability or chunk generation order.
 * <p>
 * Planning is bounded to the Core Realm Zone ({@link LivingModsConfig#civilizationRadiusBlocks()}).
 * Inject a real {@link TerrainProvider} (MinecraftTerrainProvider) for production worlds;
 * pure tools may omit it and use synthetic {@link TerrainAnalyzer}.
 */
public final class WorldPlanner {
    public enum Phase {
        TERRAIN_ANALYSIS,
        KINGDOM_PLANNING,
        SETTLEMENT_PLANNING,
        TERRITORY_MAPPING,
        ROAD_PLANNING,
        URBAN_LAYOUT,
        RESOURCE_AND_RUINS,
        FINALIZATION
    }

    public record Progress(Phase phase, double fraction, String detail) {}

    private final LivingModsConfig config;
    private final CultureRegistry cultures;
    private final TerrainProvider terrainOverride;

    public WorldPlanner(LivingModsConfig config) {
        this(config, new CultureRegistry(), null);
    }

    public WorldPlanner(LivingModsConfig config, CultureRegistry cultures) {
        this(config, cultures, null);
    }

    public WorldPlanner(LivingModsConfig config, TerrainProvider terrain) {
        this(config, new CultureRegistry(), terrain);
    }

    public WorldPlanner(LivingModsConfig config, CultureRegistry cultures, TerrainProvider terrain) {
        this.config = config;
        this.cultures = cultures;
        this.terrainOverride = terrain;
    }

    public WorldPlan plan(long seed) {
        return plan(seed, p -> {});
    }

    public WorldPlan plan(long seed, Consumer<Progress> progress) {
        TerrainProvider terrain = terrainOverride != null ? terrainOverride : new TerrainAnalyzer(seed);
        progress.accept(new Progress(Phase.TERRAIN_ANALYSIS, 0.05, "Sampling Core Realm Zone regions"));

        List<RegionTerrainSummary> regionSummaries = sampleMacroRegions(terrain);
        progress.accept(new Progress(Phase.TERRAIN_ANALYSIS, 0.15, "Regions analyzed: " + regionSummaries.size()));

        KingdomPlanner kingdomPlanner = new KingdomPlanner(config, cultures, terrain);
        List<PlannedKingdom> kingdoms = new ArrayList<>(kingdomPlanner.plan(seed, regionSummaries));
        progress.accept(new Progress(Phase.KINGDOM_PLANNING, 0.28, "Kingdoms: " + kingdoms.size()));

        SettlementPlanner settlementPlanner = new SettlementPlanner(config, cultures, terrain);
        // First pass without territory raster (capitals only known); refined after territory map.
        List<PlannedSettlement> settlements = new ArrayList<>(settlementPlanner.plan(seed, kingdoms, null));
        progress.accept(new Progress(Phase.SETTLEMENT_PLANNING, 0.42, "Settlements: " + settlements.size()));

        if (config.enableWizardTrees()) {
            WizardTreesPlanner wizard = new WizardTreesPlanner(config, cultures, terrain);
            WizardTreesPlanner.Result wiz = wizard.plan(seed, kingdoms, settlements);
            kingdoms.add(wiz.kingdom());
            settlements.addAll(wiz.settlements());
        }

        TerritoryMap territories = TerritoryMap.build(
                terrain, kingdoms, settlements,
                config.civilizationRadiusBlocks(),
                TerritoryMap.DEFAULT_CELL_SIZE
        );
        // Attach territory polygons + adjacency graph onto kingdoms.
        for (int i = 0; i < kingdoms.size(); i++) {
            PlannedKingdom k = kingdoms.get(i);
            if (k.underground()) continue;
            List<KingdomId> adjacent = territories.adjacentKingdoms(k.id());
            kingdoms.set(i, k.withTerritory(
                    territories.territoryPolygonApprox(k.id(), 16),
                    adjacent
            ));
        }
        progress.accept(new Progress(Phase.TERRITORY_MAPPING, 0.50,
                "Territory cells=" + (territories.widthCells() * territories.heightCells())
                        + " adjacencies wired"));

        RoadPlanner roadPlanner = new RoadPlanner(terrain);
        List<PlannedRoad> roads = roadPlanner.plan(seed, kingdoms, settlements);
        progress.accept(new Progress(Phase.ROAD_PLANNING, 0.62, "Roads: " + roads.size()));

        UrbanPlanner urbanPlanner = new UrbanPlanner(config, cultures, terrain);
        settlements = new ArrayList<>(urbanPlanner.planUrbanLayouts(seed, settlements, roads));
        progress.accept(new Progress(Phase.URBAN_LAYOUT, 0.78, "Urban layouts complete"));

        // Snap external road endpoints to gates / primary streets after urban layout.
        roads = roadPlanner.connectRoadsToUrbanFabric(seed, roads, settlements);

        ResourcePlanner resourcePlanner = new ResourcePlanner(config, terrain);
        List<PlannedResourceSite> resources = resourcePlanner.plan(seed, kingdoms, settlements);
        List<PlannedBanditCamp> camps = resourcePlanner.planBanditCamps(seed, kingdoms, settlements, roads);
        RuinPlanner ruinPlanner = new RuinPlanner(config, terrain, cultures);
        List<PlannedRuin> ruins = ruinPlanner.plan(seed, kingdoms, settlements);
        progress.accept(new Progress(Phase.RESOURCE_AND_RUINS, 0.90, "Resources/ruins placed"));

        long hash = Hashing.mix(seed, settlements.size());
        hash = Hashing.mix(hash, kingdoms.size());
        hash = Hashing.mix(hash, roads.size());
        hash = Hashing.mix(hash, territories.widthCells() * 31L + territories.heightCells());
        for (PlannedSettlement s : settlements) {
            hash = Hashing.mix(hash, s.center().packed());
        }
        for (PlannedKingdom k : kingdoms) {
            hash = Hashing.mix(hash, k.adjacentKingdomIds().size());
        }

        WorldPlan plan = new WorldPlan(
                seed, kingdoms, settlements, roads, ruins, resources, camps, territories, hash
        );
        new WorldPlanValidator().validateOrThrow(plan);
        progress.accept(new Progress(Phase.FINALIZATION, 1.0, "Plan hash=" + Long.toHexString(hash)));
        return plan;
    }

    /**
     * Lazy/bounded macro planning: sample planning regions that intersect the Core Realm Zone only.
     * Does not imply infinite full-world planning.
     */
    private List<RegionTerrainSummary> sampleMacroRegions(TerrainProvider terrain) {
        int regionSize = config.planningRegionSizeChunks();
        int regionBlocks = regionSize * 16;
        int half = config.coreRealmHalfExtent();
        int radiusRegions = (half / regionBlocks) + 1;
        List<RegionTerrainSummary> summaries = new ArrayList<>();
        for (int rx = -radiusRegions; rx <= radiusRegions; rx++) {
            for (int rz = -radiusRegions; rz <= radiusRegions; rz++) {
                int cx = rx * regionBlocks + regionBlocks / 2;
                int cz = rz * regionBlocks + regionBlocks / 2;
                if (Math.hypot(cx, cz) > config.civilizationRadiusBlocks() + regionBlocks) {
                    continue;
                }
                summaries.add(terrain.summarizeRegion(RegionCoord.of(rx, rz), regionSize));
            }
        }
        return summaries;
    }
}
