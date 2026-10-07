package com.livingmods.worldgen.terrain;

import com.livingmods.common.geo.BlockPos2;
import com.livingmods.common.util.DeterministicRandom;
import com.livingmods.common.util.Hashing;

import java.util.HashMap;
import java.util.Map;

/**
 * DETERMINISTIC SYNTHETIC {@link TerrainProvider} for tests and pure worldgen tools ONLY.
 * Independent of chunk generation order. Uses seeded noise domains — not Minecraft's
 * runtime sampler (worldgen module has no MC deps).
 * <p>
 * Production Minecraft planning must inject {@code MinecraftTerrainProvider} from neoforge
 * so LivingMods placement corresponds to real terrain.
 */
public final class TerrainAnalyzer implements TerrainProvider {
    private final long worldSeed;
    private final Map<Long, TerrainSample> cache = new HashMap<>();

    public TerrainAnalyzer(long worldSeed) {
        this.worldSeed = worldSeed;
    }

    @Override
    public long worldSeed() {
        return worldSeed;
    }

    @Override
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

    @Override
    public TerrainSample sample(BlockPos2 pos) {
        return sample(pos.x(), pos.z());
    }

    @Override
    public double surfaceHeight(int x, int z) {
        return sample(x, z).elevation();
    }

    @Override
    public double oceanFloor(int x, int z) {
        return sample(x, z).oceanFloor();
    }

    @Override
    public String biomeHint(int x, int z) {
        return sample(x, z).biomeHint();
    }

    @Override
    public double temperature(int x, int z) {
        return sample(x, z).temperature();
    }

    @Override
    public double humidity(int x, int z) {
        return sample(x, z).moisture();
    }

    @Override
    public boolean waterPresence(int x, int z) {
        return sample(x, z).water();
    }

    @Override
    public boolean river(int x, int z) {
        return sample(x, z).river();
    }

    @Override
    public boolean coast(int x, int z) {
        return sample(x, z).coastal();
    }

    @Override
    public double slope(int x, int z) {
        return sample(x, z).slope();
    }

    @Override
    public double roughness(int x, int z) {
        return sample(x, z).roughness();
    }

    @Override
    public double buildableScore(int x, int z) {
        return sample(x, z).buildableScore();
    }

    public String dominantBiome(int x, int z) {
        return biomeHint(x, z);
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
        double roughness = Math.min(1.0, Math.abs(fbm(nx * 2.2, nz * 2.2, 4)) * 0.7 + slope * 0.4);
        double moisture = clamp01(fbm(nx * 0.7 + 10, nz * 0.7, 3) * 0.5 + 0.5);
        double temperature = clamp01(0.55 - (elevation - 64) * 0.008 + fbm(nx + 3, nz + 3, 2) * 0.15);
        boolean river = riverNoise(x, z);
        boolean water = elevation < 62 || river && elevation < 66;
        double oceanFloor = water ? Math.min(elevation - 4, 58) : elevation - slope * 2;
        boolean coastal = !water && nearWater(x, z, 24);
        String biome = classifyBiome(elevation, moisture, temperature, water, coastal);
        return new TerrainSample(
                x, z, elevation, oceanFloor, slope, roughness, moisture, temperature,
                water, coastal, river, biome
        );
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
        return new TerrainSample(x, z, elevation, 0.0, 0.5, 0.5, water, false, river, "plains");
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
