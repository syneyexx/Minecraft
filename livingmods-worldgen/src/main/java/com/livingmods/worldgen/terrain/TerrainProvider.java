package com.livingmods.worldgen.terrain;

import com.livingmods.common.geo.BlockPos2;
import com.livingmods.common.geo.BoundingBox2;
import com.livingmods.common.geo.RegionCoord;

/**
 * Terrain queries for civilization planning.
 * <p>
 * Implementations:
 * <ul>
 *   <li>{@link TerrainAnalyzer} — deterministic synthetic noise for tests/tools only</li>
 *   <li>MinecraftTerrainProvider (neoforge) — real overworld heightmaps/biomes during server planning</li>
 * </ul>
 * LivingMods placement must correspond to the terrain this provider reports.
 */
public interface TerrainProvider {

    long worldSeed();

    /** World-surface height (blocks). Equivalent to elevation in {@link TerrainSample}. */
    double surfaceHeight(int x, int z);

    /**
     * Ocean-floor / solid-ground height under water columns.
     * When dry land, typically equals or is near {@link #surfaceHeight}.
     */
    double oceanFloor(int x, int z);

    /** Water column depth; 0 when dry. */
    default double waterDepth(int x, int z) {
        return Math.max(0.0, surfaceHeight(x, z) - oceanFloor(x, z));
    }

    /** Biome id / hint string used for culture matching (e.g. "plains", "minecraft:plains"). */
    String biomeHint(int x, int z);

    double temperature(int x, int z);

    double humidity(int x, int z);

    boolean waterPresence(int x, int z);

    boolean river(int x, int z);

    boolean coast(int x, int z);

    double slope(int x, int z);

    /** Local elevation variance / roughness in [0,1]. */
    double roughness(int x, int z);

    /** Composite buildable-area score in roughly [0, ~4]; higher is better for settlement. */
    double buildableScore(int x, int z);

    TerrainSample sample(int x, int z);

    default TerrainSample sample(BlockPos2 pos) {
        return sample(pos.x(), pos.z());
    }

    default double averageSlope(BoundingBox2 box, int step) {
        double sum = 0;
        int n = 0;
        for (int x = box.minX(); x <= box.maxX(); x += step) {
            for (int z = box.minZ(); z <= box.maxZ(); z += step) {
                sum += slope(x, z);
                n++;
            }
        }
        return n == 0 ? 1.0 : sum / n;
    }

    default RegionTerrainSummary summarizeRegion(RegionCoord region, int regionSizeChunks) {
        BlockPos2 origin = region.blockOrigin(regionSizeChunks);
        int size = regionSizeChunks * 16;
        double elev = 0, slopeSum = 0, moist = 0, temp = 0;
        int water = 0, buildable = 0, n = 0;
        int step = 32;
        for (int x = 0; x < size; x += step) {
            for (int z = 0; z < size; z += step) {
                TerrainSample s = sample(origin.x() + x, origin.z() + z);
                elev += s.elevation();
                slopeSum += s.slope();
                moist += s.moisture();
                temp += s.temperature();
                if (s.water()) water++;
                if (s.buildable()) buildable++;
                n++;
            }
        }
        if (n == 0) {
            return new RegionTerrainSummary(region, 64, 1, 0.5, 0.5, 0, 0, "plains");
        }
        return new RegionTerrainSummary(
                region,
                elev / n,
                slopeSum / n,
                moist / n,
                temp / n,
                water / (double) n,
                buildable / (double) n,
                biomeHint(origin.x() + size / 2, origin.z() + size / 2)
        );
    }
}
