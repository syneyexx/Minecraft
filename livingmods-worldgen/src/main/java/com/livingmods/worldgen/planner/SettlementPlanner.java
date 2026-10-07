package com.livingmods.worldgen.planner;

import com.livingmods.common.config.LivingModsConfig;
import com.livingmods.common.culture.CultureDefinition;
import com.livingmods.common.culture.CultureRegistry;
import com.livingmods.common.geo.BlockPos2;
import com.livingmods.common.geo.BoundingBox2;
import com.livingmods.common.id.KingdomId;
import com.livingmods.common.id.SettlementId;
import com.livingmods.common.model.SettlementRole;
import com.livingmods.common.model.SettlementTier;
import com.livingmods.common.util.DeterministicRandom;
import com.livingmods.common.util.Hashing;
import com.livingmods.worldgen.plan.PlannedKingdom;
import com.livingmods.worldgen.plan.PlannedSettlement;
import com.livingmods.worldgen.terrain.TerrainAnalyzer;
import com.livingmods.worldgen.terrain.TerrainSample;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;

public final class SettlementPlanner {
    private final LivingModsConfig config;
    private final CultureRegistry cultures;
    private final TerrainAnalyzer terrain;

    public SettlementPlanner(LivingModsConfig config, CultureRegistry cultures, TerrainAnalyzer terrain) {
        this.config = config;
        this.cultures = cultures;
        this.terrain = terrain;
    }

    public List<PlannedSettlement> plan(long seed, List<PlannedKingdom> kingdoms) {
        List<PlannedSettlement> all = new ArrayList<>();
        long baseSeed = Hashing.mix(seed, 0x534554544C45L);

        for (int ki = 0; ki < kingdoms.size(); ki++) {
            PlannedKingdom kingdom = kingdoms.get(ki);
            if (kingdom.underground()) {
                continue;
            }
            CultureDefinition culture = cultures.get(kingdom.cultureId())
                    .orElseThrow(() -> new IllegalStateException("Unknown culture: " + kingdom.cultureKey()));

            DeterministicRandom random = new DeterministicRandom(Hashing.mix(baseSeed, ki));
            List<PlannedSettlement> realmSettlements = new ArrayList<>();
            List<SettlementId> ids = new ArrayList<>();

            PlannedSettlement capital = buildCapital(seed, kingdom, culture, random);
            realmSettlements.add(capital);
            ids.add(capital.id());

            int target = config.settlementsPerMajorRealm();
            TierPlan plan = tierPlan(target - 1, random);
            int ordinal = 1;
            List<BlockPos2> placed = new ArrayList<>();
            placed.add(capital.center());

            for (TierSlot slot : plan.slots()) {
                BlockPos2 site = findSite(seed, ki, ordinal, kingdom, slot.tier(), slot.role(), placed, random);
                if (site == null) {
                    continue;
                }
                SettlementId id = SettlementId.deterministic(seed, ki * 1000L + ordinal);
                String name = nameSettlement(culture, slot.tier(), slot.role(), random.fork(ordinal));
                PlannedSettlement s = newSettlement(
                        id, name, slot.tier(), slot.role(), site, kingdom.id(), culture, false, random.fork("pop-" + ordinal)
                );
                realmSettlements.add(s);
                ids.add(id);
                placed.add(site);
                ordinal++;
            }

            all.addAll(realmSettlements);
            kingdoms.set(ki, new PlannedKingdom(
                    kingdom.id(),
                    kingdom.name(),
                    kingdom.cultureId(),
                    kingdom.cultureKey(),
                    kingdom.governmentType(),
                    kingdom.capitalId(),
                    kingdom.capitalCenter(),
                    List.copyOf(ids),
                    kingdom.territoryPolygon(),
                    false,
                    kingdom.religionKey()
            ));
        }
        return all;
    }

    private PlannedSettlement buildCapital(long seed, PlannedKingdom kingdom, CultureDefinition culture,
                                           DeterministicRandom random) {
        BlockPos2 center = kingdom.capitalCenter();
        String name = kingdom.name();
        return newSettlement(
                kingdom.capitalId(),
                name,
                SettlementTier.CAPITAL,
                SettlementRole.GENERAL,
                center,
                kingdom.id(),
                culture,
                true,
                random.fork("capital-pop")
        );
    }

    private PlannedSettlement newSettlement(
            SettlementId id,
            String name,
            SettlementTier tier,
            SettlementRole role,
            BlockPos2 center,
            KingdomId owner,
            CultureDefinition culture,
            boolean capital,
            DeterministicRandom random
    ) {
        int radius = tier.footprintRadius();
        BoundingBox2 bounds = BoundingBox2.around(center, radius);
        boolean walls = capital || tier.isUrban() && random.chance(0.65);
        int pop = (int) (tier.typicalPopulation() * (0.85 + random.nextDouble() * 0.3));
        return new PlannedSettlement(
                id,
                name,
                tier,
                role,
                center,
                bounds,
                Optional.of(owner),
                culture.id(),
                culture.key(),
                capital,
                walls,
                false,
                pop,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of()
        );
    }

    private BlockPos2 findSite(
            long seed,
            int kingdomIndex,
            int ordinal,
            PlannedKingdom kingdom,
            SettlementTier tier,
            SettlementRole role,
            List<BlockPos2> placed,
            DeterministicRandom random
    ) {
        BlockPos2 capital = kingdom.capitalCenter();
        int minDist = tier.influenceRadius();
        DeterministicRandom siteRandom = new DeterministicRandom(Hashing.mix(seed, Hashing.mix(0x53495445L, kingdomIndex * 1000L + ordinal)));

        BlockPos2 best = null;
        double bestScore = Double.NEGATIVE_INFINITY;
        int attempts = 48;
        int maxRadius = 2200 + tier.influenceRadius() * 4;

        for (int i = 0; i < attempts; i++) {
            double angle = siteRandom.nextDouble() * Math.PI * 2;
            int dist = minDist + siteRandom.nextInt(maxRadius - minDist);
            int x = capital.x() + (int) (Math.cos(angle) * dist);
            int z = capital.z() + (int) (Math.sin(angle) * dist);

            if (!insideTerritory(kingdom, x, z)) {
                continue;
            }

            TerrainSample sample = terrain.sample(x, z);
            if (!sample.buildable() && role != SettlementRole.PORT && role != SettlementRole.FISHING) {
                continue;
            }
            if (role == SettlementRole.PORT && !sample.goodPort()) {
                continue;
            }
            if (role == SettlementRole.FISHING && !sample.coastal() && !sample.river()) {
                continue;
            }
            if (role == SettlementRole.MINING && sample.slope() < 0.25) {
                continue;
            }
            if (role == SettlementRole.LOGGING && !sample.biomeHint().contains("forest") && sample.moisture() < 0.45) {
                continue;
            }
            if (role == SettlementRole.FARMING && !sample.goodFarmland()) {
                continue;
            }

            double score = scoreSite(sample, tier, role, capital, placed);
            if (score > bestScore) {
                bestScore = score;
                best = BlockPos2.of(x, z);
            }
        }
        return best;
    }

    private double scoreSite(TerrainSample s, SettlementTier tier, SettlementRole role,
                             BlockPos2 capital, List<BlockPos2> placed) {
        double score = 0;
        score += (1.0 - s.slope()) * 3.0;
        if (s.goodFarmland()) score += 1.5;
        if (s.river()) score += 0.8;
        if (s.coastal()) score += 0.6;
        score += s.moisture() * 0.4;

        switch (role) {
            case FARMING -> score += s.goodFarmland() ? 3.0 : -2.0;
            case MINING -> score += s.slope() > 0.2 ? 2.0 : 0;
            case LOGGING -> score += s.moisture() > 0.5 ? 1.5 : 0;
            case FISHING, PORT -> score += (s.coastal() || s.river()) ? 2.5 : -1.5;
            case MILITARY -> score += s.defensive() ? 2.0 : 0;
            default -> {}
        }

        int minSep = tier.influenceRadius();
        for (BlockPos2 p : placed) {
            double d = p.distanceTo(BlockPos2.of(s.x(), s.z()));
            if (d < minSep) {
                score -= (minSep - d) * 0.05;
            }
            double grad = Math.exp(-d / (tier.influenceRadius() * 3.0));
            score -= grad * 0.8;
        }

        double capDist = capital.distanceTo(BlockPos2.of(s.x(), s.z()));
        score -= Math.abs(capDist - tier.influenceRadius() * 8) * 0.0003;

        return score;
    }

    private boolean insideTerritory(PlannedKingdom kingdom, int x, int z) {
        List<BlockPos2> poly = kingdom.territoryPolygon();
        if (poly.isEmpty()) {
            return kingdom.capitalCenter().distanceTo(BlockPos2.of(x, z)) < 2500;
        }
        BlockPos2 c = kingdom.capitalCenter();
        double max = 0;
        for (BlockPos2 p : poly) {
            max = Math.max(max, c.distanceTo(p));
        }
        return c.distanceTo(BlockPos2.of(x, z)) <= max * 1.05;
    }

    private String nameSettlement(CultureDefinition culture, SettlementTier tier, SettlementRole role,
                                  DeterministicRandom random) {
        String prefix = random.pick(culture.naming().settlementPrefixes());
        String suffix = random.pick(culture.naming().settlementSuffixes());
        if (role != SettlementRole.GENERAL && random.chance(0.35)) {
            suffix = role.name().toLowerCase().replace('_', ' ') + " " + suffix;
        }
        return prefix + Character.toUpperCase(suffix.charAt(0)) + suffix.substring(1);
    }

    private record TierSlot(SettlementTier tier, SettlementRole role) {}

    private record TierPlan(List<TierSlot> slots) {}

    private TierPlan tierPlan(int count, DeterministicRandom random) {
        List<TierSlot> slots = new ArrayList<>();
        int cities = Math.max(1, count / 12);
        int towns = Math.max(2, count / 6);
        int villages = Math.max(4, count / 3);
        int hamlets = Math.max(0, count - cities - towns - villages);

        for (int i = 0; i < cities; i++) slots.add(new TierSlot(SettlementTier.CITY, SettlementRole.GENERAL));
        for (int i = 0; i < towns; i++) slots.add(new TierSlot(SettlementTier.TOWN, SettlementRole.GENERAL));
        for (int i = 0; i < villages; i++) slots.add(new TierSlot(SettlementTier.VILLAGE, SettlementRole.GENERAL));
        for (int i = 0; i < hamlets; i++) slots.add(new TierSlot(SettlementTier.HAMLET, SettlementRole.GENERAL));

        EnumSet<SettlementRole> specials = EnumSet.of(
                SettlementRole.FARMING, SettlementRole.MINING, SettlementRole.LOGGING,
                SettlementRole.FISHING, SettlementRole.PORT, SettlementRole.MILITARY
        );
        int specialCount = Math.min(6, Math.max(2, count / 8));
        for (int i = 0; i < specialCount; i++) {
            SettlementRole role = random.pick(new ArrayList<>(specials));
            SettlementTier tier = random.chance(0.4) ? SettlementTier.VILLAGE : SettlementTier.HAMLET;
            slots.add(new TierSlot(tier, role));
        }

        while (slots.size() > count) {
            slots.remove(slots.size() - 1);
        }
        while (slots.size() < count) {
            slots.add(new TierSlot(SettlementTier.HAMLET, SettlementRole.GENERAL));
        }

        for (int i = slots.size() - 1; i > 0; i--) {
            int j = random.nextInt(i + 1);
            TierSlot tmp = slots.get(i);
            slots.set(i, slots.get(j));
            slots.set(j, tmp);
        }
        return new TierPlan(slots);
    }
}
