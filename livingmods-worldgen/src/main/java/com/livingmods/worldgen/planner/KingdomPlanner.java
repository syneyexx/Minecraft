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
import com.livingmods.worldgen.terrain.TerrainProvider;
import com.livingmods.worldgen.terrain.TerrainSample;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Places surface kingdoms inside the Core Realm Zone.
 * Culture geography preferences are scored <em>before</em> capital fixation:
 * each culture picks its best remaining candidate site.
 */
public final class KingdomPlanner {
    private final LivingModsConfig config;
    private final CultureRegistry cultures;
    private final TerrainProvider terrain;

    public KingdomPlanner(LivingModsConfig config, CultureRegistry cultures, TerrainProvider terrain) {
        this.config = config;
        this.cultures = cultures;
        this.terrain = terrain;
    }

    public List<PlannedKingdom> plan(long seed, List<RegionTerrainSummary> regions) {
        DeterministicRandom random = new DeterministicRandom(Hashing.mix(seed, 0x4B494E47L));

        List<CultureDefinition> surface = new ArrayList<>(cultures.surfaceCultures());
        DeterministicRandom cultureRandom = random.fork("cultures");
        for (int i = surface.size() - 1; i > 0; i--) {
            int j = cultureRandom.nextInt(i + 1);
            CultureDefinition tmp = surface.get(i);
            surface.set(i, surface.get(j));
            surface.set(j, tmp);
        }

        int count = Math.min(config.surfaceKingdomCount(), surface.size());
        List<CandidateSite> candidates = buildCandidates(seed, regions);
        candidates.sort(Comparator.comparingDouble(CandidateSite::baseSuitability).reversed());

        List<PlannedKingdom> kingdoms = new ArrayList<>();
        Set<Long> usedCenters = new HashSet<>();
        int minSeparation = Math.max(
                config.planningRegionSizeChunks() * 16 * 2,
                config.civilizationRadiusBlocks() / Math.max(4, count + 1)
        );

        List<CultureDefinition> assignedCultures = surface.subList(0, count);
        // Culture-first: for each culture, pick the highest-scoring remaining site for its geography prefs.
        for (int ci = 0; ci < assignedCultures.size(); ci++) {
            CultureDefinition culture = assignedCultures.get(ci);
            CandidateSite best = null;
            double bestScore = Double.NEGATIVE_INFINITY;
            BlockPos2 bestCapital = null;

            for (CandidateSite candidate : candidates) {
                if (usedCenters.contains(candidate.center().packed())) continue;
                boolean tooClose = false;
                for (PlannedKingdom existing : kingdoms) {
                    if (candidate.center().distanceTo(existing.capitalCenter()) < minSeparation) {
                        tooClose = true;
                        break;
                    }
                }
                if (tooClose) continue;

                BlockPos2 capital = refineCapital(seed, candidate, culture, kingdoms.size());
                if (capital == null) continue;
                if (!withinCoreRealm(capital)) continue;

                double score = cultureSiteScore(culture, candidate, capital);
                if (score > bestScore) {
                    bestScore = score;
                    best = candidate;
                    bestCapital = capital;
                }
            }

            if (best == null || bestCapital == null) {
                continue;
            }
            usedCenters.add(best.center().packed());
            usedCenters.add(bestCapital.packed());

            KingdomId id = KingdomId.deterministic(seed, kingdoms.size());
            SettlementId capitalId = SettlementId.deterministic(seed, 10_000L + kingdoms.size());
            String name = nameKingdom(culture, random.fork(kingdoms.size()));

            kingdoms.add(new PlannedKingdom(
                    id,
                    name,
                    culture.id(),
                    culture.key(),
                    culture.preferredGovernment(),
                    capitalId,
                    bestCapital,
                    List.of(capitalId),
                    List.of(), // territory filled after TerritoryMap build
                    List.of(),
                    false,
                    culture.religionKey()
            ));
        }
        return kingdoms;
    }

    private List<CandidateSite> buildCandidates(long seed, List<RegionTerrainSummary> regions) {
        List<CandidateSite> out = new ArrayList<>();
        for (RegionTerrainSummary summary : regions) {
            if (summary.buildableFraction() < 0.18) continue;
            BlockPos2 origin = summary.region().blockOrigin(config.planningRegionSizeChunks());
            int size = config.planningRegionSizeChunks() * 16;
            BlockPos2 center = BlockPos2.of(origin.x() + size / 2, origin.z() + size / 2);
            if (!withinCoreRealm(center)) continue;
            out.add(new CandidateSite(summary, center, summary.kingdomSuitability()));
        }
        // Ensure we have enough candidates even if region grid is sparse near coasts.
        if (out.size() < config.surfaceKingdomCount() * 3) {
            DeterministicRandom extra = new DeterministicRandom(Hashing.mix(seed, 0xCA11D07EL));
            int radius = config.coreRealmHalfExtent();
            for (int i = 0; i < 80 && out.size() < config.surfaceKingdomCount() * 8; i++) {
                int x = extra.nextInt(-radius, radius);
                int z = extra.nextInt(-radius, radius);
                if (!withinCoreRealm(BlockPos2.of(x, z))) continue;
                TerrainSample s = terrain.sample(x, z);
                if (!s.buildable()) continue;
                RegionCoord region = RegionCoord.of(
                        Math.floorDiv(x, config.planningRegionSizeChunks() * 16),
                        Math.floorDiv(z, config.planningRegionSizeChunks() * 16)
                );
                out.add(new CandidateSite(
                        new RegionTerrainSummary(region, s.elevation(), s.slope(), s.moisture(),
                                s.temperature(), s.water() ? 1 : 0, s.buildable() ? 1 : 0, s.biomeHint()),
                        BlockPos2.of(x, z),
                        s.buildableScore()
                ));
            }
        }
        return out;
    }

    private BlockPos2 refineCapital(long seed, CandidateSite candidate, CultureDefinition culture, int ordinal) {
        DeterministicRandom random = new DeterministicRandom(Hashing.mix(seed, Hashing.mix(0xCA917A11L, ordinal)));
        BlockPos2 origin = candidate.summary().region().blockOrigin(config.planningRegionSizeChunks());
        int size = config.planningRegionSizeChunks() * 16;
        boolean hasRegion = candidate.summary().region() != null;
        BlockPos2 best = null;
        double bestScore = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < 48; i++) {
            int x;
            int z;
            if (hasRegion && size > 0) {
                x = origin.x() + random.nextInt(Math.max(1, size));
                z = origin.z() + random.nextInt(Math.max(1, size));
            } else {
                x = candidate.center().x() + random.nextInt(-180, 180);
                z = candidate.center().z() + random.nextInt(-180, 180);
            }
            if (!withinCoreRealm(BlockPos2.of(x, z))) continue;
            TerrainSample sample = terrain.sample(x, z);
            if (!sample.buildable()) continue;
            // Reject ocean / deep water so plains cities never land in ocean.
            if (sample.water() || sample.biomeHint().contains("ocean")) continue;
            double score = capitalScore(sample, culture);
            if (score > bestScore) {
                bestScore = score;
                best = BlockPos2.of(x, z);
            }
        }
        return best;
    }

    private double cultureSiteScore(CultureDefinition culture, CandidateSite candidate, BlockPos2 capital) {
        TerrainSample sample = terrain.sample(capital);
        double score = capitalScore(sample, culture);
        score += candidate.baseSuitability() * 0.35;
        score += biomePreferenceScore(culture, sample.biomeHint()) * 3.0;
        score += biomePreferenceScore(culture, candidate.summary().dominantBiome()) * 1.5;
        score += resourcePreferenceHint(culture, sample);
        return score;
    }

    private double capitalScore(TerrainSample s, CultureDefinition culture) {
        double score = s.buildableScore();
        if (s.goodFarmland()) score += 2.0;
        if (s.defensive()) score += 1.5;
        if (s.coastal()) score += 1.0;
        if (s.river()) score += 1.2;
        score += (1.0 - s.slope()) * 2.0;
        score += s.moisture() * 0.5;
        score += biomePreferenceScore(culture, s.biomeHint()) * 2.5;
        if (s.water() || s.biomeHint().contains("ocean")) score -= 10.0;
        return score;
    }

    static double biomePreferenceScore(CultureDefinition culture, String biomeHint) {
        if (biomeHint == null || culture.preferredBiomes().isEmpty()) return 0;
        String b = biomeHint.toLowerCase();
        double best = 0;
        for (String pref : culture.preferredBiomes()) {
            String p = pref.toLowerCase();
            if (b.equals(p) || b.endsWith(":" + p) || b.contains(p)) {
                best = Math.max(best, 1.0);
            } else if (p.contains(b) || fuzzyBiomeFamily(b, p)) {
                best = Math.max(best, 0.55);
            }
        }
        return best;
    }

    private static boolean fuzzyBiomeFamily(String biome, String pref) {
        if (pref.contains("taiga") && biome.contains("taiga")) return true;
        if (pref.contains("plains") && biome.contains("plains")) return true;
        if (pref.contains("forest") && biome.contains("forest")) return true;
        if (pref.contains("desert") && biome.contains("desert")) return true;
        if (pref.contains("jungle") && biome.contains("jungle")) return true;
        if (pref.contains("ocean") && biome.contains("ocean")) return true;
        if (pref.contains("hills") && (biome.contains("hills") || biome.contains("windswept"))) return true;
        return false;
    }

    static double resourcePreferenceHint(CultureDefinition culture, TerrainSample sample) {
        double score = 0;
        for (String res : culture.preferredResources()) {
            String r = res.toUpperCase();
            switch (r) {
                case "GRAIN", "VEGETABLES", "LIVESTOCK" -> {
                    if (sample.goodFarmland()) score += 0.6;
                }
                case "WOOD" -> {
                    if (sample.forested()) score += 0.6;
                }
                case "FISH", "WATER" -> {
                    if (sample.navigableWater() || sample.coastal() || sample.river()) score += 0.6;
                }
                case "IRON", "COAL", "STONE", "GOLD" -> {
                    if (sample.mineralContext()) score += 0.6;
                }
                default -> {}
            }
        }
        return score;
    }

    private boolean withinCoreRealm(BlockPos2 pos) {
        return Math.hypot(pos.x(), pos.z()) <= config.civilizationRadiusBlocks();
    }

    private String nameKingdom(CultureDefinition culture, DeterministicRandom random) {
        String prefix = random.pick(culture.naming().settlementPrefixes());
        String suffix = random.pick(culture.naming().settlementSuffixes());
        return prefix + Character.toUpperCase(suffix.charAt(0)) + suffix.substring(1);
    }

    private record CandidateSite(RegionTerrainSummary summary, BlockPos2 center, double baseSuitability) {}
}
