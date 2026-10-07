package com.livingmods.worldgen.planner;

import com.livingmods.common.config.LivingModsConfig;
import com.livingmods.common.culture.CultureDefinition;
import com.livingmods.common.culture.CultureRegistry;
import com.livingmods.common.geo.BlockPos2;
import com.livingmods.common.geo.RegionCoord;
import com.livingmods.common.id.KingdomId;
import com.livingmods.common.id.SettlementId;
import com.livingmods.common.util.DeterministicRandom;
import com.livingmods.common.util.Hashing;
import com.livingmods.worldgen.plan.PlannedKingdom;
import com.livingmods.worldgen.terrain.RegionTerrainSummary;
import com.livingmods.worldgen.terrain.TerrainAnalyzer;
import com.livingmods.worldgen.terrain.TerrainSample;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class KingdomPlanner {
    private final LivingModsConfig config;
    private final CultureRegistry cultures;
    private final TerrainAnalyzer terrain;

    public KingdomPlanner(LivingModsConfig config, CultureRegistry cultures, TerrainAnalyzer terrain) {
        this.config = config;
        this.cultures = cultures;
        this.terrain = terrain;
    }

    public List<PlannedKingdom> plan(long seed, List<RegionTerrainSummary> regions) {
        DeterministicRandom random = new DeterministicRandom(Hashing.mix(seed, 0x4B494E47L));
        List<RegionTerrainSummary> ranked = new ArrayList<>(regions);
        ranked.sort(Comparator.comparingDouble(RegionTerrainSummary::kingdomSuitability).reversed());

        List<CultureDefinition> surface = new ArrayList<>(cultures.surfaceCultures());
        DeterministicRandom cultureRandom = random.fork("cultures");
        for (int i = surface.size() - 1; i > 0; i--) {
            int j = cultureRandom.nextInt(i + 1);
            CultureDefinition tmp = surface.get(i);
            surface.set(i, surface.get(j));
            surface.set(j, tmp);
        }

        int count = Math.min(config.surfaceKingdomCount(), surface.size());
        List<PlannedKingdom> kingdoms = new ArrayList<>();
        Set<Long> usedCenters = new HashSet<>();
        int minSeparation = config.planningRegionSizeChunks() * 16 * 3;

        int cultureIndex = 0;
        for (RegionTerrainSummary summary : ranked) {
            if (kingdoms.size() >= count) {
                break;
            }
            if (summary.buildableFraction() < 0.25) {
                continue;
            }

            BlockPos2 center = findCapitalSite(seed, summary.region(), cultureIndex);
            if (center == null) {
                continue;
            }

            boolean tooClose = false;
            for (PlannedKingdom existing : kingdoms) {
                if (center.distanceTo(existing.capitalCenter()) < minSeparation) {
                    tooClose = true;
                    break;
                }
            }
            if (tooClose) {
                continue;
            }
            if (!usedCenters.add(center.packed())) {
                continue;
            }

            CultureDefinition culture = surface.get(cultureIndex % surface.size());
            cultureIndex++;

            KingdomId id = KingdomId.deterministic(seed, kingdoms.size());
            SettlementId capitalId = SettlementId.deterministic(seed, 10_000L + kingdoms.size());
            String name = nameKingdom(culture, random.fork(kingdoms.size()));
            List<BlockPos2> territory = buildTerritoryPolygon(center, random.fork("territory-" + kingdoms.size()));

            kingdoms.add(new PlannedKingdom(
                    id,
                    name,
                    culture.id(),
                    culture.key(),
                    culture.preferredGovernment(),
                    capitalId,
                    center,
                    List.of(capitalId),
                    territory,
                    false,
                    culture.religionKey()
            ));
        }
        return kingdoms;
    }

    private BlockPos2 findCapitalSite(long seed, RegionCoord region, int ordinal) {
        DeterministicRandom random = new DeterministicRandom(Hashing.mix(seed, Hashing.mix(0xCA917A11L, ordinal)));
        BlockPos2 origin = region.blockOrigin(config.planningRegionSizeChunks());
        int size = config.planningRegionSizeChunks() * 16;
        BlockPos2 best = null;
        double bestScore = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < 40; i++) {
            int x = origin.x() + random.nextInt(size);
            int z = origin.z() + random.nextInt(size);
            TerrainSample sample = terrain.sample(x, z);
            if (!sample.buildable()) {
                continue;
            }
            double score = capitalScore(sample);
            if (score > bestScore) {
                bestScore = score;
                best = BlockPos2.of(x, z);
            }
        }
        return best;
    }

    private double capitalScore(TerrainSample s) {
        double score = 0;
        if (s.goodFarmland()) {
            score += 2.0;
        }
        if (s.defensive()) {
            score += 1.5;
        }
        if (s.coastal()) {
            score += 1.0;
        }
        if (s.river()) {
            score += 1.2;
        }
        score += (1.0 - s.slope()) * 2.0;
        score += s.moisture() * 0.5;
        return score;
    }

    private List<BlockPos2> buildTerritoryPolygon(BlockPos2 center, DeterministicRandom random) {
        List<BlockPos2> poly = new ArrayList<>();
        int rays = 16;
        for (int i = 0; i < rays; i++) {
            double angle = (Math.PI * 2 * i) / rays;
            int radius = 1800 + random.nextInt(900);
            int x = center.x() + (int) (Math.cos(angle) * radius);
            int z = center.z() + (int) (Math.sin(angle) * radius);
            TerrainSample s = terrain.sample(x, z);
            if (s.elevation() > 105) {
                radius = (int) (radius * 0.7);
                x = center.x() + (int) (Math.cos(angle) * radius);
                z = center.z() + (int) (Math.sin(angle) * radius);
            }
            if (s.river() || s.water()) {
                radius = (int) (radius * 0.85);
                x = center.x() + (int) (Math.cos(angle) * radius);
                z = center.z() + (int) (Math.sin(angle) * radius);
            }
            poly.add(BlockPos2.of(x, z));
        }
        return poly;
    }

    private String nameKingdom(CultureDefinition culture, DeterministicRandom random) {
        String prefix = random.pick(culture.naming().settlementPrefixes());
        String suffix = random.pick(culture.naming().settlementSuffixes());
        return prefix + Character.toUpperCase(suffix.charAt(0)) + suffix.substring(1);
    }
}
