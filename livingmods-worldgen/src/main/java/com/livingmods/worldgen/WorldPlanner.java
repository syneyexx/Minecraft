package com.livingmods.worldgen;

import com.livingmods.common.config.LivingModsConfig;
import com.livingmods.common.culture.CultureRegistry;
import com.livingmods.common.geo.RegionCoord;
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
import com.livingmods.worldgen.validation.WorldPlanValidator;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Deterministic civilization world planning pipeline.
 * Does NOT depend on sidecar availability or chunk generation order.
 */
public final class WorldPlanner {
    public enum Phase {
        TERRAIN_ANALYSIS,
        KINGDOM_PLANNING,
        SETTLEMENT_PLANNING,
        ROAD_PLANNING,
        URBAN_LAYOUT,
        RESOURCE_AND_RUINS,
        FINALIZATION
    }

    public record Progress(Phase phase, double fraction, String detail) {}

    private final LivingModsConfig config;
    private final CultureRegistry cultures;

    public WorldPlanner(LivingModsConfig config) {
        this(config, new CultureRegistry());
    }

    public WorldPlanner(LivingModsConfig config, CultureRegistry cultures) {
        this.config = config;
        this.cultures = cultures;
    }

    public WorldPlan plan(long seed) {
        return plan(seed, p -> {});
    }

    public WorldPlan plan(long seed, Consumer<Progress> progress) {
        TerrainAnalyzer terrain = new TerrainAnalyzer(seed);
        progress.accept(new Progress(Phase.TERRAIN_ANALYSIS, 0.05, "Sampling macro regions"));

        List<RegionTerrainSummary> regionSummaries = sampleMacroRegions(terrain);
        progress.accept(new Progress(Phase.TERRAIN_ANALYSIS, 0.15, "Regions analyzed: " + regionSummaries.size()));

        KingdomPlanner kingdomPlanner = new KingdomPlanner(config, cultures, terrain);
        List<PlannedKingdom> kingdoms = new ArrayList<>(kingdomPlanner.plan(seed, regionSummaries));
        progress.accept(new Progress(Phase.KINGDOM_PLANNING, 0.30, "Kingdoms: " + kingdoms.size()));

        SettlementPlanner settlementPlanner = new SettlementPlanner(config, cultures, terrain);
        List<PlannedSettlement> settlements = new ArrayList<>(settlementPlanner.plan(seed, kingdoms));
        progress.accept(new Progress(Phase.SETTLEMENT_PLANNING, 0.50, "Settlements: " + settlements.size()));

        if (config.enableWizardTrees()) {
            WizardTreesPlanner wizard = new WizardTreesPlanner(cultures, terrain);
            WizardTreesPlanner.Result wiz = wizard.plan(seed, kingdoms, settlements);
            kingdoms.add(wiz.kingdom());
            settlements.addAll(wiz.settlements());
        }

        RoadPlanner roadPlanner = new RoadPlanner(terrain);
        List<PlannedRoad> roads = roadPlanner.plan(seed, kingdoms, settlements);
        progress.accept(new Progress(Phase.ROAD_PLANNING, 0.65, "Roads: " + roads.size()));

        UrbanPlanner urbanPlanner = new UrbanPlanner(cultures, terrain);
        settlements = new ArrayList<>(urbanPlanner.planUrbanLayouts(seed, settlements));
        progress.accept(new Progress(Phase.URBAN_LAYOUT, 0.80, "Urban layouts complete"));

        ResourcePlanner resourcePlanner = new ResourcePlanner(terrain);
        List<PlannedResourceSite> resources = resourcePlanner.plan(seed, kingdoms, settlements);
        List<PlannedBanditCamp> camps = resourcePlanner.planBanditCamps(seed, kingdoms, settlements, roads);
        RuinPlanner ruinPlanner = new RuinPlanner(terrain, cultures);
        List<PlannedRuin> ruins = ruinPlanner.plan(seed, kingdoms, settlements);
        progress.accept(new Progress(Phase.RESOURCE_AND_RUINS, 0.90, "Resources/ruins placed"));

        long hash = Hashing.mix(seed, settlements.size());
        hash = Hashing.mix(hash, kingdoms.size());
        hash = Hashing.mix(hash, roads.size());
        for (PlannedSettlement s : settlements) {
            hash = Hashing.mix(hash, s.center().packed());
        }

        WorldPlan plan = new WorldPlan(seed, kingdoms, settlements, roads, ruins, resources, camps, hash);
        new WorldPlanValidator().validateOrThrow(plan);
        progress.accept(new Progress(Phase.FINALIZATION, 1.0, "Plan hash=" + Long.toHexString(hash)));
        return plan;
    }

    private List<RegionTerrainSummary> sampleMacroRegions(TerrainAnalyzer terrain) {
        int regionSize = config.planningRegionSizeChunks();
        int radius = 24;
        List<RegionTerrainSummary> summaries = new ArrayList<>();
        for (int rx = -radius; rx <= radius; rx++) {
            for (int rz = -radius; rz <= radius; rz++) {
                summaries.add(terrain.summarizeRegion(RegionCoord.of(rx, rz), regionSize));
            }
        }
        return summaries;
    }
}
