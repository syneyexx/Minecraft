package com.livingmods.worldgen.planner;

import com.livingmods.common.culture.CultureDefinition;
import com.livingmods.common.culture.CultureRegistry;
import com.livingmods.common.geo.BlockPos2;
import com.livingmods.common.geo.BoundingBox2;
import com.livingmods.common.model.BuildingRole;
import com.livingmods.common.util.DeterministicRandom;
import com.livingmods.common.util.Hashing;
import com.livingmods.worldgen.plan.PlannedKingdom;
import com.livingmods.worldgen.plan.PlannedRuin;
import com.livingmods.worldgen.plan.PlannedSettlement;
import com.livingmods.worldgen.terrain.TerrainAnalyzer;
import com.livingmods.worldgen.terrain.TerrainSample;

import java.util.ArrayList;
import java.util.List;

/**
 * Places historically motivated ruins (fallen keeps, abandoned shrines) — not random rubble.
 */
public final class RuinPlanner {
    private final TerrainAnalyzer terrain;
    private final CultureRegistry cultures;

    public RuinPlanner(TerrainAnalyzer terrain, CultureRegistry cultures) {
        this.terrain = terrain;
        this.cultures = cultures;
    }

    public List<PlannedRuin> plan(long seed, List<PlannedKingdom> kingdoms, List<PlannedSettlement> settlements) {
        long ruinSeed = Hashing.mix(seed, 0x5255494E4CL);
        List<PlannedRuin> ruins = new ArrayList<>();
        int ordinal = 0;

        for (PlannedKingdom kingdom : kingdoms) {
            if (kingdom.underground()) continue;
            CultureDefinition culture = cultures.get(kingdom.cultureKey()).orElse(null);
            if (culture == null) continue;

            DeterministicRandom random = new DeterministicRandom(Hashing.mix(ruinSeed, kingdom.id().hashCode()));
            int count = 2 + random.nextInt(4);
            BlockPos2 capital = kingdom.capitalCenter();

            for (int i = 0; i < count; i++) {
                BlockPos2 site = findRuinSite(capital, random, i);
                if (site == null) continue;
                RuinTemplate template = pickTemplate(random, culture);
                BoundingBox2 bounds = BoundingBox2.around(site, template.radius);
                int decaySeed = (int) (Hashing.mix(ruinSeed, ordinal) & 0x7fffffff);
                ruins.add(new PlannedRuin(
                        bounds,
                        template.role,
                        culture.key(),
                        decaySeed,
                        template.note
                ));
                ordinal++;
            }
        }

        // Ancient sites unrelated to current polities
        DeterministicRandom ancient = new DeterministicRandom(Hashing.mix(ruinSeed, 0x414E4349L));
        for (int i = 0; i < 8; i++) {
            int x = ancient.nextInt(-4500, 4500);
            int z = ancient.nextInt(-4500, 4500);
            TerrainSample s = terrain.sample(x, z);
            if (!s.buildable() || s.water()) continue;
            String cultureKey = cultures.surfaceCultures().get(ancient.nextInt(cultures.surfaceCultures().size())).key();
            BuildingRole role = ancient.chance(0.5) ? BuildingRole.TEMPLE : BuildingRole.CASTLE_KEEP;
            ruins.add(new PlannedRuin(
                    BoundingBox2.around(BlockPos2.of(x, z), 16 + ancient.nextInt(12)),
                    role,
                    cultureKey,
                    (int) (Hashing.mix(ruinSeed, i + 1000) & 0x7fffffff),
                    "pre-dynastic collapse"
            ));
        }
        return ruins;
    }

    private BlockPos2 findRuinSite(BlockPos2 capital, DeterministicRandom random, int index) {
        for (int attempt = 0; attempt < 25; attempt++) {
            double angle = random.nextDouble() * Math.PI * 2;
            int dist = 600 + index * 200 + random.nextInt(900);
            int x = capital.x() + (int) (Math.cos(angle) * dist);
            int z = capital.z() + (int) (Math.sin(angle) * dist);
            TerrainSample s = terrain.sample(x, z);
            if (s.defensive() || s.river()) {
                return BlockPos2.of(x, z);
            }
        }
        return null;
    }

    private RuinTemplate pickTemplate(DeterministicRandom random, CultureDefinition culture) {
        if (random.chance(0.4)) {
            return new RuinTemplate(BuildingRole.CASTLE_KEEP, 22,
                    "Border fortress abandoned after " + random.pick(culture.naming().rulerTitles()) + " succession war");
        }
        if (random.chance(0.5)) {
            return new RuinTemplate(BuildingRole.TEMPLE, 18,
                    "Sanctuary to " + culture.religionKey() + " left to weather");
        }
        return new RuinTemplate(BuildingRole.MANOR, 14,
                "Estate of the " + random.pick(culture.naming().familyNames()) + " line, deserted");
    }

    private record RuinTemplate(BuildingRole role, int radius, String note) {}
}
