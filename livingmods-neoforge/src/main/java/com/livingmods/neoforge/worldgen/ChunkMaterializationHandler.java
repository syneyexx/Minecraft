package com.livingmods.neoforge.worldgen;

import com.livingmods.common.geo.ChunkCoord;
import com.livingmods.common.version.LivingModsVersions;
import com.livingmods.neoforge.LivingModsMod;
import com.livingmods.worldgen.plan.ChunkCivilizationSlice;
import com.livingmods.worldgen.plan.WorldPlan;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.level.ChunkEvent;

/**
 * Safe generation lifecycle: Overworld-only surface kingdoms, deferred to server thread,
 * per-chunk attachment provenance so content is never rewritten after first apply
 * (player edits / foreign mods preserved).
 */
public final class ChunkMaterializationHandler {
    private static final CivilizationMaterializer MATERIALIZER = new CivilizationMaterializer();

    private ChunkMaterializationHandler() {}

    @SubscribeEvent
    public static void onChunkLoad(ChunkEvent.Load event) {
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        // Surface kingdoms + Wizard Trees live in the Overworld dimension (underground Y).
        if (level.dimension() != Level.OVERWORLD) {
            return;
        }
        if (!(event.getChunk() instanceof LevelChunk chunk)) {
            return;
        }

        // ChunkEvent.Load may fire before FULL; never touch the level synchronously.
        final boolean newChunk = event.isNewChunk();
        level.getServer().execute(() -> materializeDeferred(level, chunk, newChunk));
    }

    private static void materializeDeferred(ServerLevel level, LevelChunk chunk, boolean newChunk) {
        try {
            if (level.getChunkSource().getChunkNow(chunk.getPos().x, chunk.getPos().z) == null) {
                return;
            }
            WorldPlan plan = WorldPlanCache.get();
            if (plan == null) {
                return;
            }

            ChunkMaterializationState state = ChunkMaterializationState.of(chunk);
            // Never re-place already applied content — preserves player edits & foreign builds.
            if (state.isAppliedFor(LivingModsVersions.PHYSICAL_CONTENT_REVISION, plan.contentHash())) {
                return;
            }
            // Older applied revision with different contract: still do not rewrite player worlds.
            if (state.isAppliedAnyRevision()) {
                return;
            }

            ChunkCivilizationSlice slice = plan.sliceForChunk(ChunkCoord.of(chunk.getPos().x, chunk.getPos().z));
            if (slice.isEmpty()) {
                state.markApplied(plan.contentHash(), true);
                return;
            }

            // Prefer first-time generation; still allow first LivingMods apply on existing empty status.
            if (!newChunk && state.isAppliedAnyRevision()) {
                return;
            }

            MATERIALIZER.materializeChunk(level, chunk, slice, state);
            state.markApplied(plan.contentHash(), false);
            LivingModsMod.LOG.debug(
                    "Materialized LivingMods chunk {},{} (newChunk={})",
                    chunk.getPos().x, chunk.getPos().z, newChunk
            );
        } catch (Exception e) {
            LivingModsMod.LOG.error(
                    "LivingMods materialization failed for chunk {},{}",
                    chunk.getPos().x, chunk.getPos().z, e
            );
        }
    }
}
