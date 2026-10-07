package com.livingmods.neoforge.worldgen;

import com.livingmods.common.config.LivingModsConfig;
import com.livingmods.worldgen.persist.WorldPlanStore;
import com.livingmods.worldgen.plan.WorldPlan;
import com.livingmods.worldgen.terrain.TerrainProvider;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.storage.LevelResource;

import java.io.DataOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * World-local plan cache under world/livingmods/worldplan/.
 * <p>
 * On first generation, builds a {@link MinecraftTerrainProvider} from the overworld so
 * kingdom/settlement placement matches real Minecraft terrain. Subsequent loads restore
 * the immutable full plan from disk (never silently regenerate).
 * <p>
 * Chunk materialization lifecycle stays in {@link CivilizationMaterializer} (Block C).
 */
public final class WorldPlanCache {
    private static volatile WorldPlan cached;
    private static volatile TerrainProvider terrainProvider;

    private WorldPlanCache() {}

    public static WorldPlan get() {
        return cached;
    }

    public static TerrainProvider terrain() {
        return terrainProvider;
    }

    public static void clear() {
        cached = null;
        terrainProvider = null;
    }

    public static WorldPlan loadOrGenerate(MinecraftServer server) throws Exception {
        Path worldDir = server.getWorldPath(LevelResource.ROOT);
        ServerLevel overworld = server.overworld();
        long seed = overworld.getSeed();
        Path seedMarker = worldDir.resolve("livingmods/worldplan/seed.dat");
        Files.createDirectories(seedMarker.getParent());
        if (!Files.isRegularFile(seedMarker)) {
            try (DataOutputStream out = new DataOutputStream(Files.newOutputStream(seedMarker))) {
                out.writeLong(seed);
            }
        }

        MinecraftTerrainProvider minecraftTerrain = new MinecraftTerrainProvider(overworld);
        terrainProvider = minecraftTerrain;

        LivingModsConfig config = LivingModsConfig.defaults();
        // Pass Minecraft terrain into planner only when generating; load path restores plan as-is.
        cached = WorldPlanStore.loadOrGenerate(worldDir, config, seed, minecraftTerrain);
        return cached;
    }
}
