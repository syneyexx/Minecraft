package com.livingmods.worldgen.terrain;

import com.livingmods.common.geo.BlockPos2;
import com.livingmods.common.geo.BoundingBox2;
import com.livingmods.common.geo.RegionCoord;
import com.livingmods.common.util.DeterministicRandom;
import com.livingmods.common.util.Hashing;

import java.util.HashMap;
import java.util.Map;

/**
 * Deterministic terrain sampling for planning. Independent of chunk generation order.
 * Uses seeded noise domains — not Minecraft's runtime sampler (worldgen module has no MC deps).
 */
public final class TerrainAnalyzer {
    private final long worldSeed;
    private final Map<Long, TerrainSample> cache = new HashMap<>();

    public TerrainAnalyzer(long worldSeed) {
        this.worldSeed = worldSeed;
    }

    public TerrainSample sample(int x, int z) {
        long key = ((long) x << 32) ^ (z & 0xffffffffL);
        TerrainSample cached = cache.get(key);
        if (cached != null) return cached;
        TerrainSample sample = compute(x, z);
        if (cache.size() < 200_000) {
            cache.put(key, sample);
        }
        return sample;
    }

    public TerrainSample sample(BlockPos2 pos) {
        return sample(pos.x(), pos.z());
    }

    public double averageSlope(BoundingBox2 box, int step) {
        double sum = 0;
        int n = 0;
        for (int x = box.minX(); x <= box.maxX(); x += step) {
            for (int z = box.minZ(); z <= box.maxZ(); z += step) {
                sum += sample(x, z).slope();
                n++;
            }
        }
        return n == 0 ? 1.0 : sum / n;
    }

    public RegionTerrainSummary summarizeRegion(RegionCoord region, int regionSizeChunks) {
        BlockPos2 origin = region.blockOrigin(regionSizeChunks);
        int size = regionSizeChunks * 16;
        double elev = 0, slope = 0, moist = 0, temp = 0;
        int water = 0, buildable = 0, n = 0;
        int step = 32;
        for (int x = 0; x < size; x += step) {
            for (int z = 0; z < size; z += step) {
                TerrainSample s = sample(origin.x() + x, origin.z() + z);
                elev += s.elevation();
                slope += s.slope();
                moist += s.moisture();
                temp += s.temperature();
                if (s.water()) water++;
                if (s.buildable()) buildable++;
                n++;
            }
        }
        return new RegionTerrainSummary(
                region,
                elev / n,
                slope / n,
                moist / n,
                temp / n,
                water / (double) n,
                buildable / (double) n,
                dominantBiome(origin.x() + size / 2, origin.z() + size / 2)
        );
    }

    public String dominantBiome(int x, int z) {
        return sample(x, z).biomeHint();
    }

    private TerrainSample compute(int x, int z) {
        double nx = x * 0.0015;
        double nz = z * 0.0015;
        double elevNoise = fbm(nx, nz, 1) * 40 + 70;
        double ridge = Math.abs(fbm(nx * 0.5, nz * 0.5, 2));
        double elevation = elevNoise + ridge * 25;
        double dx = fbm(nx + 0.01, nz, 1) - fbm(nx - 0.01, nz, 1);
        double dz = fbm(nx, nz + 0.01, 1) - fbm(nx, nz - 0.01, 1);
        double slope = Math.min(1.0, Math.hypot(dx, dz) * 25);
        double moisture = clamp01(fbm(nx * 0.7 + 10, nz * 0.7, 3) * 0.5 + 0.5);
        double temperature = clamp01(0.55 - (elevation - 64) * 0.008 + fbm(nx + 3, nz + 3, 2) * 0.15);
        boolean river = riverNoise(x, z);
        boolean water = elevation < 62 || river && elevation < 66;
        boolean coastal = !water && nearWater(x, z, 24);
        String biome = classifyBiome(elevation, moisture, temperature, water, coastal);
        return new TerrainSample(x, z, elevation, slope, moisture, temperature, water, coastal, river, biome);
    }

    private boolean nearWater(int x, int z, int radius) {
        for (int i = 0; i < 8; i++) {
            double a = i * Math.PI / 4;
            int sx = x + (int) (Math.cos(a) * radius);
            int sz = z + (int) (Math.sin(a) * radius);
            TerrainSample s = computeLite(sx, sz);
            if (s.water()) return true;
        }
        return false;
    }

    private TerrainSample computeLite(int x, int z) {
        double nx = x * 0.0015;
        double nz = z * 0.0015;
        double elevation = fbm(nx, nz, 1) * 40 + 70 + Math.abs(fbm(nx * 0.5, nz * 0.5, 2)) * 25;
        boolean river = riverNoise(x, z);
        boolean water = elevation < 62 || river && elevation < 66;
        return new TerrainSample(x, z, elevation, 0, 0.5, 0.5, water, false, river, "plains");
    }

    private boolean riverNoise(int x, int z) {
        double v = Math.abs(fbm(x * 0.0008 + 50, z * 0.0008, 2));
        return v < 0.04;
    }

    private String classifyBiome(double elev, double moist, double temp, boolean water, boolean coastal) {
        if (water) return coastal ? "ocean" : "river";
        if (elev > 110) return temp < 0.35 ? "frozen_peaks" : "jagged_peaks";
        if (elev > 95) return "windswept_hills";
        if (temp < 0.25) return moist > 0.5 ? "snowy_taiga" : "snowy_plains";
        if (moist < 0.25) return temp > 0.7 ? "desert" : "badlands";
        if (moist > 0.7 && temp > 0.65) return "jungle";
        if (moist > 0.55) return "forest";
        if (coastal) return "beach";
        if (temp > 0.7) return "savanna";
        return "plains";
    }

    private double fbm(double x, double z, int domain) {
        double sum = 0;
        double amp = 1;
        double freq = 1;
        double norm = 0;
        for (int o = 0; o < 4; o++) {
            sum += amp * valueNoise(x * freq, z * freq, domain + o);
            norm += amp;
            amp *= 0.5;
            freq *= 2.0;
        }
        return sum / norm;
    }

    private double valueNoise(double x, double z, int domain) {
        int x0 = (int) Math.floor(x);
        int z0 = (int) Math.floor(z);
        int x1 = x0 + 1;
        int z1 = z0 + 1;
        double fx = x - x0;
        double fz = z - z0;
        double sx = fx * fx * (3 - 2 * fx);
        double sz = fz * fz * (3 - 2 * fz);
        double n00 = hashGradient(x0, z0, domain);
        double n10 = hashGradient(x1, z0, domain);
        double n01 = hashGradient(x0, z1, domain);
        double n11 = hashGradient(x1, z1, domain);
        double ix0 = lerp(n00, n10, sx);
        double ix1 = lerp(n01, n11, sx);
        return lerp(ix0, ix1, sz);
    }

    private double hashGradient(int x, int z, int domain) {
        long h = Hashing.mix(Hashing.mix(worldSeed, domain), Hashing.mix(x, z));
        return ((h & 0xffffff) / (double) 0xffffff) * 2.0 - 1.0;
    }

    private static double lerp(double a, double b, double t) {
        return a + (b - a) * t;
    }

    private static double clamp01(double v) {
        return Math.max(0, Math.min(1, v));
    }

    public DeterministicRandom randomFor(String domain, long ordinal) {
        return new DeterministicRandom(Hashing.mix(worldSeed, Hashing.hashString(domain) ^ ordinal));
    }
}
