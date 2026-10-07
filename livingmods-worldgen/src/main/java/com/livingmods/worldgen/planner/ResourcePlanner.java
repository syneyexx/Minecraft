package com.livingmods.worldgen.planner;

import com.livingmods.common.geo.BlockPos2;
import com.livingmods.common.id.KingdomId;
import com.livingmods.common.model.ResourceType;
import com.livingmods.common.model.SettlementRole;
import com.livingmods.common.util.DeterministicRandom;
import com.livingmods.common.util.Hashing;
import com.livingmods.worldgen.plan.PlannedBanditCamp;
import com.livingmods.worldgen.plan.PlannedKingdom;
import com.livingmods.worldgen.plan.PlannedResourceSite;
import com.livingmods.worldgen.plan.PlannedRoad;
import com.livingmods.worldgen.plan.PlannedSettlement;
import com.livingmods.worldgen.terrain.TerrainAnalyzer;
import com.livingmods.worldgen.terrain.TerrainSample;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

public final class ResourcePlanner {
    private final TerrainAnalyzer terrain;

    public ResourcePlanner(TerrainAnalyzer terrain) {
        this.terrain = terrain;
    }

    public List<PlannedResourceSite> plan(long seed, List<PlannedKingdom> kingdoms,
                                          List<PlannedSettlement> settlements) {
        long resSeed = Hashing.mix(seed, 0x5245534F52L);
        List<PlannedResourceSite> sites = new ArrayList<>();
        Set<Long> used = new HashSet<>();
        int ordinal = 0;

        for (PlannedKingdom kingdom : kingdoms) {
            if (kingdom.underground()) continue;
            DeterministicRandom random = new DeterministicRandom(Hashing.mix(resSeed, kingdom.id().hashCode()));
            int siteCount = 6 + random.nextInt(6);
            BlockPos2 capital = kingdom.capitalCenter();

            for (int i = 0; i < siteCount; i++) {
                BlockPos2 center = findResourceSite(capital, kingdom, random, used);
                if (center == null) continue;
                ResourceType type = pickResource(random, terrain.sample(center));
                double richness = 0.4 + random.nextDouble() * 0.6;
                Optional<KingdomId> claim = random.chance(0.7) ? Optional.of(kingdom.id()) : Optional.empty();
                sites.add(new PlannedResourceSite(center, type, richness, claim));
                used.add(center.packed());
                ordinal++;
            }
        }

        // Frontier deposits between kingdoms
        DeterministicRandom frontierRandom = new DeterministicRandom(Hashing.mix(resSeed, 0x46524F4E54L));
        for (int i = 0; i < 24; i++) {
            int x = frontierRandom.nextInt(-5000, 5000);
            int z = frontierRandom.nextInt(-5000, 5000);
            BlockPos2 p = BlockPos2.of(x, z);
            if (used.contains(p.packed())) continue;
            TerrainSample s = terrain.sample(p);
            if (!s.buildable() && !s.water()) continue;
            ResourceType type = pickResource(frontierRandom, s);
            sites.add(new PlannedResourceSite(p, type, 0.3 + frontierRandom.nextDouble() * 0.4, Optional.empty()));
            used.add(p.packed());
        }
        return sites;
    }

    public List<PlannedBanditCamp> planBanditCamps(long seed, List<PlannedKingdom> kingdoms,
                                                 List<PlannedSettlement> settlements,
                                                 List<PlannedRoad> roads) {
        long campSeed = Hashing.mix(seed, 0x42414E4449L);
        List<PlannedBanditCamp> camps = new ArrayList<>();
        Set<Long> used = new HashSet<>();

        for (int i = 0; i < settlements.size(); i++) {
            PlannedSettlement s = settlements.get(i);
            if (s.role() == SettlementRole.MILITARY || s.capital()) continue;

            DeterministicRandom random = new DeterministicRandom(Hashing.mix(campSeed, s.id().hashCode()));
            if (!random.chance(0.08)) continue;

            double roadDist = distanceToNearestRoad(s.center(), roads);
            double control = s.ownerKingdom().isPresent() ? 0.6 : 0.2;
            if (roadDist < 80 && random.chance(control)) continue;

            BlockPos2 camp = offsetCamp(s.center(), random);
            if (used.contains(camp.packed())) continue;
            TerrainSample sample = terrain.sample(camp);
            if (sample.water() || sample.slope() > 0.5) continue;

            int size = 3 + random.nextInt(8);
            String reason = roadDist > 200 ? "remote frontier" : "weak patrol along trade route";
            camps.add(new PlannedBanditCamp(camp, size, reason));
            used.add(camp.packed());
        }
        return camps;
    }

    private BlockPos2 findResourceSite(BlockPos2 capital, PlannedKingdom kingdom,
                                     DeterministicRandom random, Set<Long> used) {
        for (int attempt = 0; attempt < 30; attempt++) {
            double angle = random.nextDouble() * Math.PI * 2;
            int dist = 400 + random.nextInt(1600);
            int x = capital.x() + (int) (Math.cos(angle) * dist);
            int z = capital.z() + (int) (Math.sin(angle) * dist);
            BlockPos2 p = BlockPos2.of(x, z);
            if (used.contains(p.packed())) continue;
            TerrainSample s = terrain.sample(p);
            if (s.water()) continue;
            if (!s.buildable() && s.slope() > 0.55) continue;
            return p;
        }
        return null;
    }

    private ResourceType pickResource(DeterministicRandom random, TerrainSample s) {
        if (s.goodFarmland()) return random.chance(0.6) ? ResourceType.GRAIN : ResourceType.VEGETABLES;
        if (s.coastal() || s.river()) return random.chance(0.5) ? ResourceType.FISH : ResourceType.WATER;
        if (s.biomeHint().contains("forest")) return ResourceType.WOOD;
        if (s.elevation() > 95) return random.chance(0.5) ? ResourceType.IRON : ResourceType.COAL;
        if (s.moisture() < 0.3) return ResourceType.STONE;
        return random.pick(List.of(ResourceType.WOOD, ResourceType.STONE, ResourceType.IRON, ResourceType.GRAIN));
    }

    private double distanceToNearestRoad(BlockPos2 center, List<PlannedRoad> roads) {
        double best = Double.MAX_VALUE;
        for (PlannedRoad road : roads) {
            for (BlockPos2 p : road.path()) {
                best = Math.min(best, center.distanceTo(p));
            }
        }
        return best == Double.MAX_VALUE ? 500 : best;
    }

    private BlockPos2 offsetCamp(BlockPos2 center, DeterministicRandom random) {
        double angle = random.nextDouble() * Math.PI * 2;
        int dist = 120 + random.nextInt(200);
        return BlockPos2.of(
                center.x() + (int) (Math.cos(angle) * dist),
                center.z() + (int) (Math.sin(angle) * dist)
        );
    }
}
