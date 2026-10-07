package com.livingmods.worldgen.planner;

import com.livingmods.common.culture.CultureDefinition;
import com.livingmods.common.culture.CultureRegistry;
import com.livingmods.common.geo.BlockPos2;
import com.livingmods.common.geo.BoundingBox2;
import com.livingmods.common.id.KingdomId;
import com.livingmods.common.id.SettlementId;
import com.livingmods.common.model.GovernmentType;
import com.livingmods.common.model.SettlementRole;
import com.livingmods.common.model.SettlementTier;
import com.livingmods.common.util.DeterministicRandom;
import com.livingmods.common.util.Hashing;
import com.livingmods.worldgen.plan.PlannedKingdom;
import com.livingmods.worldgen.plan.PlannedSettlement;
import com.livingmods.worldgen.terrain.TerrainAnalyzer;
import com.livingmods.worldgen.terrain.TerrainSample;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Underground theocratic civilization — placed away from surface kingdom capitals.
 */
public final class WizardTreesPlanner {
    private final CultureRegistry cultures;
    private final TerrainAnalyzer terrain;

    public record Result(PlannedKingdom kingdom, List<PlannedSettlement> settlements) {}

    public WizardTreesPlanner(CultureRegistry cultures, TerrainAnalyzer terrain) {
        this.cultures = cultures;
        this.terrain = terrain;
    }

    public Result plan(long seed, List<PlannedKingdom> existing, List<PlannedSettlement> existingSettlements) {
        long wizSeed = Hashing.mix(seed, 0x57495A415244L);
        DeterministicRandom random = new DeterministicRandom(wizSeed);
        CultureDefinition culture = cultures.wizardTrees();

        BlockPos2 center = findUndergroundCapital(seed, existing, random);
        KingdomId kingdomId = KingdomId.deterministic(seed, 99_999L);
        SettlementId capitalId = SettlementId.deterministic(seed, 99_000L);
        String name = nameRealm(culture, random);

        List<BlockPos2> territory = buildCavernPolygon(center, random);
        List<SettlementId> settlementIds = new ArrayList<>();
        List<PlannedSettlement> settlements = new ArrayList<>();

        PlannedSettlement capital = settlement(
                capitalId, name + " Spire", SettlementTier.CAPITAL, SettlementRole.WIZARD_TREES,
                center, kingdomId, culture, true, 1200, random
        );
        settlements.add(capital);
        settlementIds.add(capitalId);

        int satelliteCount = 4 + random.nextInt(3);
        for (int i = 0; i < satelliteCount; i++) {
            BlockPos2 site = findSatellite(center, i, random);
            SettlementId id = SettlementId.deterministic(seed, 99_100L + i);
            SettlementTier tier = i == 0 ? SettlementTier.CITY : SettlementTier.VILLAGE;
            String sname = nameSettlement(culture, random.fork(i));
            PlannedSettlement s = settlement(
                    id, sname, tier, SettlementRole.UNDERGROUND, site, kingdomId, culture, false,
                    tier.typicalPopulation(), random.fork("sat-" + i)
            );
            settlements.add(s);
            settlementIds.add(id);
        }

        PlannedKingdom kingdom = new PlannedKingdom(
                kingdomId,
                name,
                culture.id(),
                culture.key(),
                GovernmentType.THEOCRACY,
                capitalId,
                center,
                List.copyOf(settlementIds),
                territory,
                true,
                culture.religionKey()
        );
        return new Result(kingdom, settlements);
    }

    private BlockPos2 findUndergroundCapital(long seed, List<PlannedKingdom> existing, DeterministicRandom random) {
        BlockPos2 best = null;
        double bestScore = Double.NEGATIVE_INFINITY;
        for (int attempt = 0; attempt < 60; attempt++) {
            int x = random.nextInt(-6000, 6000);
            int z = random.nextInt(-6000, 6000);
            double minSurface = Double.MAX_VALUE;
            for (PlannedKingdom k : existing) {
                if (!k.underground()) {
                    minSurface = Math.min(minSurface, k.capitalCenter().distanceTo(BlockPos2.of(x, z)));
                }
            }
            if (minSurface < 1800) continue;

            TerrainSample sample = terrain.sample(x, z);
            if (sample.water()) continue;
            double score = (1.0 - sample.slope()) + sample.moisture() * 0.5 + minSurface * 0.0002;
            if (score > bestScore) {
                bestScore = score;
                best = BlockPos2.of(x, z);
            }
        }
        if (best == null) {
            best = BlockPos2.of(4096, -4096);
        }
        return best;
    }

    private BlockPos2 findSatellite(BlockPos2 center, int ordinal, DeterministicRandom random) {
        double angle = random.nextDouble() * Math.PI * 2;
        int dist = 200 + ordinal * 80 + random.nextInt(120);
        return BlockPos2.of(
                center.x() + (int) (Math.cos(angle) * dist),
                center.z() + (int) (Math.sin(angle) * dist)
        );
    }

    private List<BlockPos2> buildCavernPolygon(BlockPos2 center, DeterministicRandom random) {
        List<BlockPos2> poly = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            double angle = (Math.PI * 2 * i) / 12;
            int radius = 400 + random.nextInt(200);
            poly.add(BlockPos2.of(
                    center.x() + (int) (Math.cos(angle) * radius),
                    center.z() + (int) (Math.sin(angle) * radius)
            ));
        }
        return poly;
    }

    private PlannedSettlement settlement(
            SettlementId id, String name, SettlementTier tier, SettlementRole role,
            BlockPos2 center, KingdomId owner, CultureDefinition culture,
            boolean capital, int population, DeterministicRandom random
    ) {
        int radius = tier.footprintRadius();
        return new PlannedSettlement(
                id, name, tier, role, center, BoundingBox2.around(center, radius),
                Optional.of(owner), culture.id(), culture.key(), capital, true, true,
                population, List.of(), List.of(), List.of(), List.of(), List.of(), List.of()
        );
    }

    private String nameRealm(CultureDefinition culture, DeterministicRandom random) {
        String prefix = random.pick(culture.naming().settlementPrefixes());
        String suffix = random.pick(culture.naming().settlementSuffixes());
        return prefix + Character.toUpperCase(suffix.charAt(0)) + suffix.substring(1);
    }

    private String nameSettlement(CultureDefinition culture, DeterministicRandom random) {
        String prefix = random.pick(culture.naming().settlementPrefixes());
        String suffix = random.pick(culture.naming().settlementSuffixes());
        return prefix + "-" + suffix;
    }
}
