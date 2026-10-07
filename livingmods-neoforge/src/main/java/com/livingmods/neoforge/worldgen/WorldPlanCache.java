package com.livingmods.neoforge.worldgen;

import com.livingmods.common.config.LivingModsConfig;
import com.livingmods.worldgen.persist.WorldPlanStore;
import com.livingmods.worldgen.plan.WorldPlan;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

import java.io.DataOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/** World-local plan cache under world/livingmods/worldplan/. */
public final class WorldPlanCache {
    private static volatile WorldPlan cached;

    private WorldPlanCache() {}

    public static WorldPlan get() {
        return cached;
    }

    public static void clear() {
        cached = null;
    }

    public static WorldPlan loadOrGenerate(MinecraftServer server) throws Exception {
        Path worldDir = server.getWorldPath(LevelResource.ROOT);
        long seed = server.overworld().getSeed();
        Path seedMarker = worldDir.resolve("livingmods/worldplan/seed.dat");
        Files.createDirectories(seedMarker.getParent());
        if (!Files.isRegularFile(seedMarker)) {
            try (DataOutputStream out = new DataOutputStream(Files.newOutputStream(seedMarker))) {
                out.writeLong(seed);
            }
        }
        cached = WorldPlanStore.loadOrGenerate(worldDir, LivingModsConfig.defaults());
        return cached;
    }
}
