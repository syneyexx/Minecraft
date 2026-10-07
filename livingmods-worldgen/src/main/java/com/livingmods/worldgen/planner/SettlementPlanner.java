package com.livingmods.worldgen.planner;

import com.livingmods.common.config.LivingModsConfig;
import com.livingmods.common.culture.CultureDefinition;
import com.livingmods.common.culture.NameGrammar;
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
import com.livingmods.worldgen.terrain.TerrainProvider;
import com.livingmods.worldgen.terrain.TerrainSample;
import com.livingmods.worldgen.territory.TerritoryMap;
import com.livingmods.worldgen.territory.TerritoryZone;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;

/**
 * Settlement planning: each settlement receives tier + economic/strategic role FIRST,
 * then placement is scored for that role. Specializations are never truncated away.
 */
public final class SettlementPlanner {
    private final LivingModsConfig config;
    private final CultureRegistry cultures;
    private final TerrainProvider terrain;

    public SettlementPlanner(LivingModsConfig config, CultureRegistry cultures, TerrainProvider terrain) {
        this.config = config;
        this.cultures = cultures;
        this.terrain = terrain;
    }

    public List<PlannedSettlement> plan(long seed, List<PlannedKingdom> kingdoms) {
        return plan(seed, kingdoms, null);
    }

    public List<PlannedSettlement> plan(long seed, List<PlannedKingdom> kingdoms, TerritoryMap territories) {
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
            // Role+tier assigned first; plan size == target-1 exactly (no specialty truncation).
            TierPlan plan = tierPlan(target - 1, culture, random);
            int ordinal = 1;
            List<BlockPos2> placed = new ArrayList<>();
            placed.add(capital.center());

            for (TierSlot slot : plan.slots()) {
                BlockPos2 site = findSite(
                        seed, ki, ordinal, kingdom, slot.tier(), slot.role(),
                        placed, random, territories
                );
                if (site == null) {
                    // Soft fallback: keep the specialized role but relax constraints.
                    site = findSiteRelaxed(seed, ki, ordinal, kingdom, slot.tier(), slot.role(), placed, random);
                }
                if (site == null) {
                    continue;
                }
                SettlementId id = SettlementId.deterministic(seed, 20_000L + ki * 100L + ordinal);
                String name = nameSettlement(culture, slot.tier(), slot.role(), random.fork(ordinal));
                PlannedSettlement s = newSettlement(
                        id, name, slot.tier(), slot.role(), site, kingdom.id(), culture, false,
                        random.fork("pop-" + ordinal)
                );
                realmSettlements.add(s);
                ids.add(id);
                placed.add(site);
                ordinal++;
            }

            all.addAll(realmSettlements);
            kingdoms.set(ki, kingdom.withSettlements(List.copyOf(ids)));
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
        int pop = (int) (tier.typicalPopulation() * (0.85 + random.nextDouble() * 0.3)
                * Math.max(0.5, config.civilizationDensityScale()));
        // Wealth/tech proxy: capitals and cities denser and larger; role adjusts slightly.
        if (role == SettlementRole.PORT || role == SettlementRole.MILITARY) {
            pop = (int) (pop * 1.1);
        }
        int radius = SettlementFootprint.footprintRadius(tier, pop, culture, config.civilizationDensityScale());
        BoundingBox2 bounds = BoundingBox2.around(center, radius);
        boolean walls = capital || tier.isUrban() && random.chance(0.65);
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
            DeterministicRandom random,
            TerritoryMap territories
    ) {
        return searchSite(seed, kingdomIndex, ordinal, kingdom, tier, role, placed, random, territories, false);
    }

    private BlockPos2 findSiteRelaxed(
            long seed,
            int kingdomIndex,
            int ordinal,
            PlannedKingdom kingdom,
            SettlementTier tier,
            SettlementRole role,
            List<BlockPos2> placed,
            DeterministicRandom random
    ) {
        return searchSite(seed, kingdomIndex, ordinal, kingdom, tier, role, placed, random, null, true);
    }

    private BlockPos2 searchSite(
            long seed,
            int kingdomIndex,
            int ordinal,
            PlannedKingdom kingdom,
            SettlementTier tier,
            SettlementRole role,
            List<BlockPos2> placed,
            DeterministicRandom random,
            TerritoryMap territories,
            boolean relaxed
    ) {
        BlockPos2 capital = kingdom.capitalCenter();
        int minDist = tier.influenceRadius();
        DeterministicRandom siteRandom = new DeterministicRandom(
                Hashing.mix(seed, Hashing.mix(0x53495445L, kingdomIndex * 1000L + ordinal)));

        BlockPos2 best = null;
        double bestScore = Double.NEGATIVE_INFINITY;
        int attempts = relaxed ? 72 : 56;
        int maxRadius = Math.min(
                2200 + tier.influenceRadius() * 4,
                Math.max(800, config.civilizationRadiusBlocks() / 2)
        );

        for (int i = 0; i < attempts; i++) {
            double angle = siteRandom.nextDouble() * Math.PI * 2;
            int dist = minDist + siteRandom.nextInt(Math.max(1, maxRadius - minDist));
            int x = capital.x() + (int) (Math.cos(angle) * dist);
            int z = capital.z() + (int) (Math.sin(angle) * dist);

            if (!insideKingdomArea(kingdom, x, z, territories)) {
                continue;
            }
            if (Math.hypot(x, z) > config.civilizationRadiusBlocks()) {
                continue;
            }

            TerrainSample sample = terrain.sample(x, z);
            if (!roleFitsTerrain(role, sample, relaxed)) {
                continue;
            }

            double score = scoreSite(sample, tier, role, capital, placed, kingdom);
            if (territories != null) {
                TerritoryZone zone = territories.zoneAt(x, z);
                if (role == SettlementRole.MILITARY && (zone == TerritoryZone.BORDER || zone == TerritoryZone.FRONTIER)) {
                    score += 2.0;
                }
                if (role == SettlementRole.FRONTIER && zone == TerritoryZone.FRONTIER) {
                    score += 1.5;
                }
            }
            if (score > bestScore) {
                bestScore = score;
                best = BlockPos2.of(x, z);
            }
        }
        return best;
    }

    private boolean roleFitsTerrain(SettlementRole role, TerrainSample sample, boolean relaxed) {
        // Ocean rejection for non-water roles — plains cities must not land in ocean.
        if (sample.biomeHint().contains("ocean") && role != SettlementRole.PORT && role != SettlementRole.FISHING) {
            return false;
        }
        return switch (role) {
            case PORT -> relaxed
                    ? (sample.coastal() || sample.river() || sample.navigableWater())
                    : sample.goodPort() || (sample.coastal() && !sample.water());
            case FISHING -> sample.coastal() || sample.river() || sample.water();
            case MINING -> relaxed ? sample.slope() > 0.12 || sample.mineralContext() : sample.mineralContext();
            case LOGGING -> sample.forested() || (!relaxed && sample.moisture() > 0.45);
            case FARMING -> relaxed ? sample.buildable() && sample.slope() < 0.35 : sample.goodFarmland();
            case MILITARY -> relaxed ? sample.buildable() : (sample.defensive() || sample.slope() > 0.18);
            case RELIGIOUS -> sample.buildable();
            default -> sample.buildable() || (relaxed && !sample.water());
        };
    }

    private double scoreSite(TerrainSample s, SettlementTier tier, SettlementRole role,
                             BlockPos2 capital, List<BlockPos2> placed, PlannedKingdom kingdom) {
        CultureDefinition culture = cultures.get(kingdom.cultureKey()).orElse(null);
        double score = s.buildableScore();
        score += (1.0 - s.slope()) * 2.0;
        if (s.goodFarmland()) score += 1.0;
        if (s.river()) score += 0.8;
        if (s.coastal()) score += 0.6;

        switch (role) {
            case FARMING -> score += s.goodFarmland() ? 4.0 : -2.0;
            case MINING -> score += s.mineralContext() ? 4.0 : -1.5;
            case LOGGING -> score += s.forested() ? 4.0 : (s.moisture() > 0.5 ? 1.0 : -1.0);
            case FISHING -> score += (s.coastal() || s.river()) ? 4.0 : -2.0;
            case PORT -> score += s.goodPort() || (s.coastal() && !s.water()) ? 4.5 : -2.5;
            case MILITARY -> score += s.defensive() ? 3.5 : 0.5;
            case RELIGIOUS -> score += s.buildable() ? 1.5 : 0;
            default -> {}
        }

        if (culture != null) {
            score += KingdomPlanner.biomePreferenceScore(culture, s.biomeHint()) * 1.5;
            score += KingdomPlanner.resourcePreferenceHint(culture, s);
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

    private boolean insideKingdomArea(PlannedKingdom kingdom, int x, int z, TerritoryMap territories) {
        if (territories != null && !territories.kingdomIndex().isEmpty()) {
            Optional<com.livingmods.common.id.KingdomId> owner = territories.ownerAt(x, z);
            if (owner.isPresent()) {
                return owner.get().equals(kingdom.id());
            }
            // Allow slight expansion into unclaimed cells near capital.
            return kingdom.capitalCenter().distanceTo(BlockPos2.of(x, z)) < 1600;
        }
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
        String base = NameGrammar.settlement(culture, random);
        if (role != SettlementRole.GENERAL && random.chance(0.35)) {
            return base + " " + role.name().charAt(0) + role.name().substring(1).toLowerCase().replace('_', ' ');
        }
        return base;
    }

    private record TierSlot(SettlementTier tier, SettlementRole role) {}

    private record TierPlan(List<TierSlot> slots) {}

    /**
     * Builds exactly {@code count} slots. Specialized roles are allocated first so they
     * cannot be truncated by a later size clamp (the old bug).
     */
    private TierPlan tierPlan(int count, CultureDefinition culture, DeterministicRandom random) {
        if (count <= 0) {
            return new TierPlan(List.of());
        }

        List<TierSlot> slots = new ArrayList<>(count);

        // 1) Specialized roles first — resource-driven pairings with appropriate tiers.
        List<SettlementRole> specialRoles = preferredSpecialRoles(culture, random);
        int specialCount = Math.min(specialRoles.size(), Math.min(count, Math.max(3, count / 5)));
        for (int i = 0; i < specialCount; i++) {
            SettlementRole role = specialRoles.get(i);
            slots.add(new TierSlot(tierForRole(role, random), role));
        }

        int remaining = count - slots.size();
        int cities = Math.min(remaining, Math.max(1, count / 12));
        remaining -= cities;
        int towns = Math.min(remaining, Math.max(2, count / 6));
        remaining -= towns;
        int villages = Math.min(remaining, Math.max(4, count / 3));
        remaining -= villages;
        int hamlets = remaining;

        for (int i = 0; i < cities; i++) slots.add(new TierSlot(SettlementTier.CITY, SettlementRole.GENERAL));
        for (int i = 0; i < towns; i++) slots.add(new TierSlot(SettlementTier.TOWN, SettlementRole.GENERAL));
        for (int i = 0; i < villages; i++) slots.add(new TierSlot(SettlementTier.VILLAGE, SettlementRole.GENERAL));
        for (int i = 0; i < hamlets; i++) slots.add(new TierSlot(SettlementTier.HAMLET, SettlementRole.GENERAL));

        // Exact size invariant — pad only with GENERAL hamlets if math drifted.
        while (slots.size() < count) {
            slots.add(new TierSlot(SettlementTier.HAMLET, SettlementRole.GENERAL));
        }
        if (slots.size() > count) {
            // Prefer dropping GENERAL hamlets, never specialized roles.
            slots.sort((a, b) -> {
                boolean as = a.role() != SettlementRole.GENERAL;
                boolean bs = b.role() != SettlementRole.GENERAL;
                if (as != bs) return as ? -1 : 1;
                return Integer.compare(b.tier().ordinal(), a.tier().ordinal());
            });
            slots = new ArrayList<>(slots.subList(0, count));
        }

        for (int i = slots.size() - 1; i > 0; i--) {
            int j = random.nextInt(i + 1);
            TierSlot tmp = slots.get(i);
            slots.set(i, slots.get(j));
            slots.set(j, tmp);
        }
        return new TierPlan(slots);
    }

    private List<SettlementRole> preferredSpecialRoles(CultureDefinition culture, DeterministicRandom random) {
        EnumSet<SettlementRole> pool = EnumSet.of(
                SettlementRole.FARMING, SettlementRole.MINING, SettlementRole.LOGGING,
                SettlementRole.FISHING, SettlementRole.PORT, SettlementRole.MILITARY
        );
        List<SettlementRole> ordered = new ArrayList<>();
        for (String res : culture.preferredResources()) {
            switch (res.toUpperCase()) {
                case "GRAIN", "VEGETABLES", "LIVESTOCK" -> ordered.add(SettlementRole.FARMING);
                case "WOOD" -> ordered.add(SettlementRole.LOGGING);
                case "FISH", "WATER" -> {
                    ordered.add(SettlementRole.FISHING);
                    ordered.add(SettlementRole.PORT);
                }
                case "IRON", "COAL", "STONE", "GOLD" -> ordered.add(SettlementRole.MINING);
                default -> {}
            }
        }
        ordered.add(SettlementRole.MILITARY);
        for (String biome : culture.preferredBiomes()) {
            String b = biome.toLowerCase();
            if (b.contains("ocean") || b.contains("beach") || b.contains("river")) {
                ordered.add(SettlementRole.PORT);
                ordered.add(SettlementRole.FISHING);
            }
            if (b.contains("forest") || b.contains("taiga") || b.contains("jungle")) {
                ordered.add(SettlementRole.LOGGING);
            }
            if (b.contains("plains") || b.contains("meadow") || b.contains("savanna")) {
                ordered.add(SettlementRole.FARMING);
            }
            if (b.contains("peaks") || b.contains("hills") || b.contains("badlands")) {
                ordered.add(SettlementRole.MINING);
            }
        }
        // Dedupe preserving order, then fill from pool.
        List<SettlementRole> unique = new ArrayList<>();
        for (SettlementRole r : ordered) {
            if (pool.contains(r) && !unique.contains(r)) unique.add(r);
        }
        for (SettlementRole r : pool) {
            if (!unique.contains(r)) unique.add(r);
        }
        // Light shuffle of the tail for variety while keeping culture-preferred first.
        if (unique.size() > 2) {
            for (int i = unique.size() - 1; i > 1; i--) {
                int j = 1 + random.nextInt(i);
                SettlementRole tmp = unique.get(i);
                unique.set(i, unique.get(j));
                unique.set(j, tmp);
            }
        }
        return unique;
    }

    private SettlementTier tierForRole(SettlementRole role, DeterministicRandom random) {
        return switch (role) {
            case PORT -> random.chance(0.45) ? SettlementTier.CITY : SettlementTier.TOWN;
            case MINING, MILITARY -> random.chance(0.5) ? SettlementTier.TOWN : SettlementTier.VILLAGE;
            case FARMING, LOGGING, FISHING -> random.chance(0.55) ? SettlementTier.VILLAGE : SettlementTier.HAMLET;
            case RELIGIOUS -> SettlementTier.TOWN;
            default -> SettlementTier.VILLAGE;
        };
    }
}
