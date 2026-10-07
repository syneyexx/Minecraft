package com.livingmods.neoforge.worldgen;

import com.livingmods.common.geo.BlockPos2;
import com.livingmods.worldgen.terrain.TerrainProvider;
import com.livingmods.worldgen.terrain.TerrainSample;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.RandomState;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * {@link TerrainProvider} backed by the overworld {@link ChunkGenerator} / {@link BiomeSource}.
 * <p>
 * <b>What is sampled (server-start planning, before chunks exist):</b>
 * <ul>
 *   <li>{@code generator.getBaseHeight(x,z, WORLD_SURFACE_WG, level, randomState)} — surface height</li>
 *   <li>{@code generator.getBaseHeight(x,z, OCEAN_FLOOR_WG, level, randomState)} — ocean floor / solid ground</li>
 *   <li>{@code biomeSource.getNoiseBiome(quartX, quartY, quartZ, randomState.sampler())} — biome id/hint</li>
 *   <li>{@link Biome#getBaseTemperature()} and climate {@code downfall} — temperature / humidity</li>
 *   <li>Optional {@link Climate.Sampler#sample} TargetPoint when accessible — continentalness/erosion hints for roughness</li>
 * </ul>
 * Slope/roughness are finite-difference estimates from neighboring surface heights.
 * Water / river / coast are derived from surface vs ocean-floor delta and biome tags/names.
 * <p>
 * This keeps LivingMods placement aligned with real Minecraft terrain (e.g. no plains city in ocean).
 * Chunk materialization (Block C) continues to use heightmaps at place-time via CivilizationMaterializer.
 */
public final class MinecraftTerrainProvider implements TerrainProvider {
    private final ServerLevel level;
    private final ChunkGenerator generator;
    private final BiomeSource biomeSource;
    private final RandomState randomState;
    private final long worldSeed;
    private final Map<Long, TerrainSample> cache = new HashMap<>();

    public MinecraftTerrainProvider(ServerLevel overworld) {
        this.level = overworld;
        this.generator = overworld.getChunkSource().getGenerator();
        this.biomeSource = generator.getBiomeSource();
        this.randomState = overworld.getChunkSource().randomState();
        this.worldSeed = overworld.getSeed();
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
        if (cache.size() < 250_000) {
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

    private TerrainSample compute(int x, int z) {
        int surface = generator.getBaseHeight(x, z, Heightmap.Types.WORLD_SURFACE_WG, level, randomState);
        int floor = generator.getBaseHeight(x, z, Heightmap.Types.OCEAN_FLOOR_WG, level, randomState);

        int qx = x >> 2;
        int qz = z >> 2;
        int qy = Math.max(0, surface >> 2);
        Holder<Biome> biomeHolder = biomeSource.getNoiseBiome(qx, qy, qz, randomState.sampler());
        Biome biome = biomeHolder.value();
        String biomeHint = biomeKey(biomeHolder);

        float baseTemp = biome.getBaseTemperature();
        // Normalize typical MC temps (~-0.5..2) into [0,1] for planners.
        double temperature = clamp01((baseTemp + 0.5) / 2.5);
        double humidity = clamp01(biome.getModifiedClimateSettings().downfall());

        boolean river = biomeHint.contains("river");
        boolean ocean = biomeHint.contains("ocean") || biomeHint.contains("beach");
        double waterDepth = Math.max(0, surface - floor);
        boolean water = waterDepth >= 2.0 || ocean && surface <= level.getSeaLevel()
                || biomeHint.contains("ocean") || biomeHint.contains("river") && waterDepth >= 1.0;

        int s1 = generator.getBaseHeight(x + 4, z, Heightmap.Types.WORLD_SURFACE_WG, level, randomState);
        int s2 = generator.getBaseHeight(x - 4, z, Heightmap.Types.WORLD_SURFACE_WG, level, randomState);
        int s3 = generator.getBaseHeight(x, z + 4, Heightmap.Types.WORLD_SURFACE_WG, level, randomState);
        int s4 = generator.getBaseHeight(x, z - 4, Heightmap.Types.WORLD_SURFACE_WG, level, randomState);
        double dx = (s1 - s2) / 8.0;
        double dz = (s3 - s4) / 8.0;
        double slope = clamp01(Math.hypot(dx, dz) / 2.5);

        double roughness = slope;
        try {
            Climate.TargetPoint point = randomState.sampler().sample(qx, qy, qz);
            // Weirdness / erosion magnitude as cheap roughness proxy (quantized longs).
            double weird = Math.abs(Climate.unquantizeCoord(point.weirdness()));
            double erosion = Math.abs(Climate.unquantizeCoord(point.erosion()));
            roughness = clamp01(slope * 0.5 + weird * 0.35 + erosion * 0.25);
        } catch (Exception ignored) {
            // Sampler details vary by generator; slope alone is fine.
        }

        boolean coastal = !water && (ocean || nearWater(x, z, 24));

        return new TerrainSample(
                x, z,
                surface,
                floor,
                slope,
                roughness,
                humidity,
                temperature,
                water,
                coastal,
                river,
                biomeHint
        );
    }

    private boolean nearWater(int x, int z, int radius) {
        for (int i = 0; i < 8; i++) {
            double a = i * Math.PI / 4;
            int sx = x + (int) (Math.cos(a) * radius);
            int sz = z + (int) (Math.sin(a) * radius);
            int surface = generator.getBaseHeight(sx, sz, Heightmap.Types.WORLD_SURFACE_WG, level, randomState);
            int floor = generator.getBaseHeight(sx, sz, Heightmap.Types.OCEAN_FLOOR_WG, level, randomState);
            if (surface - floor >= 2) return true;
            int qx = sx >> 2;
            int qz = sz >> 2;
            Holder<Biome> h = biomeSource.getNoiseBiome(qx, Math.max(0, surface >> 2), qz, randomState.sampler());
            String key = biomeKey(h);
            if (key.contains("ocean") || key.contains("river") || key.contains("beach")) return true;
        }
        return false;
    }

    private String biomeKey(Holder<Biome> holder) {
        Optional<ResourceKey<Biome>> key = holder.unwrapKey();
        if (key.isPresent()) {
            ResourceLocation loc = key.get().location();
            return loc.getPath();
        }
        // Fallback unwrap via registry lookup.
        return level.registryAccess().registryOrThrow(Registries.BIOME)
                .getResourceKey(holder.value())
                .map(k -> k.location().getPath())
                .orElse("unknown");
    }

    private static double clamp01(double v) {
        return Math.max(0, Math.min(1, v));
    }
}
