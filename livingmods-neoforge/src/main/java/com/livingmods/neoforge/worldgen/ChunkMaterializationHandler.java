package com.livingmods.neoforge.worldgen;

import com.livingmods.common.geo.ChunkCoord;
import com.livingmods.worldgen.plan.ChunkCivilizationSlice;
import com.livingmods.worldgen.plan.WorldPlan;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.level.ChunkEvent;

public final class ChunkMaterializationHandler {
    private static final CivilizationMaterializer MATERIALIZER = new CivilizationMaterializer();

    private ChunkMaterializationHandler() {}

    @SubscribeEvent
    public static void onChunkLoad(ChunkEvent.Load event) {
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        if (!(event.getChunk() instanceof LevelChunk chunk)) {
            return;
        }
        WorldPlan plan = WorldPlanCache.get();
        if (plan == null) {
            return;
        }
        ChunkCivilizationSlice slice = plan.sliceForChunk(ChunkCoord.of(chunk.getPos().x, chunk.getPos().z));
        if (slice.isEmpty()) {
            return;
        }
        MATERIALIZER.materializeChunk(level, chunk, slice);
    }
}
