package com.livingmods.neoforge.worldgen;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.material.Fluids;

/**
 * Thread-local-safe chunk writer: only mutates the target chunk on the server thread,
 * never overwrites block entities / foreign machinery, and only replaces air or natural terrain
 * during initial LivingMods generation.
 */
public final class SafeChunkWriter {
    private final ServerLevel level;
    private final LevelChunk chunk;
    private final int chunkX;
    private final int chunkZ;
    private int placed;
    private int protectedCollisions;
    /** When true, vanilla crafted blocks (planks/cobble/etc.) are treated conservatively. */
    private boolean dynamicMode;

    public SafeChunkWriter(ServerLevel level, LevelChunk chunk) {
        this.level = level;
        this.chunk = chunk;
        this.chunkX = chunk.getPos().x;
        this.chunkZ = chunk.getPos().z;
    }

    public void setDynamicMode(boolean dynamicMode) {
        this.dynamicMode = dynamicMode;
    }

    public boolean dynamicMode() {
        return dynamicMode;
    }

    public ServerLevel level() {
        return level;
    }

    public LevelChunk chunk() {
        return chunk;
    }

    public boolean inChunk(int x, int z) {
        return (x >> 4) == chunkX && (z >> 4) == chunkZ;
    }

    public boolean inChunk(BlockPos pos) {
        return inChunk(pos.getX(), pos.getZ());
    }

    public boolean trySet(BlockPos pos, BlockState state) {
        if (!inChunk(pos)) {
            return false;
        }
        if (!canReplace(pos)) {
            protectedCollisions++;
            return false;
        }
        chunk.setBlockState(pos, state, false);
        placed++;
        return true;
    }

    /**
     * Replace only blocks that look like LivingMods placeholders / dynamic content.
     * Never touches foreign/player/protected geometry.
     */
    public boolean trySetLivingModsOnly(BlockPos pos, BlockState state) {
        if (!inChunk(pos)) {
            return false;
        }
        BlockState existing = chunk.getBlockState(pos);
        if (chunk.getBlockEntity(pos) != null || isForeignProtected(existing)) {
            protectedCollisions++;
            return false;
        }
        if (!(existing.isAir() || isLivingModsPlaceholder(existing))) {
            protectedCollisions++;
            return false;
        }
        chunk.setBlockState(pos, state, false);
        placed++;
        return true;
    }

    public boolean chunkContains(int x, int z) {
        return inChunk(x, z);
    }

    public boolean trySetAirPreferred(BlockPos pos, BlockState state) {
        if (!inChunk(pos)) {
            return false;
        }
        BlockState existing = chunk.getBlockState(pos);
        if (!existing.isAir() && !existing.canBeReplaced() && !isNaturalTerrain(existing) && !isLivingModsPlaceholder(existing)) {
            return false;
        }
        if (chunk.getBlockEntity(pos) != null) {
            return false;
        }
        if (isForeignProtected(existing)) {
            return false;
        }
        chunk.setBlockState(pos, state, false);
        placed++;
        return true;
    }

    public boolean canReplace(BlockPos pos) {
        if (!inChunk(pos)) {
            return false;
        }
        BlockEntity be = chunk.getBlockEntity(pos);
        if (be != null) {
            return false;
        }
        BlockState existing = chunk.getBlockState(pos);
        if (isForeignProtected(existing)) {
            return false;
        }
        if (dynamicMode) {
            // Conservative dynamic expansion: only air / fluids / clear natural terrain.
            // Do NOT treat player-built vanilla structures (planks, cobble walls, etc.) as replaceable.
            return existing.isAir()
                    || existing.canBeReplaced()
                    || existing.getFluidState().is(Fluids.WATER)
                    || existing.getFluidState().is(Fluids.LAVA)
                    || isStrictNaturalTerrain(existing);
        }
        return existing.isAir()
                || existing.canBeReplaced()
                || existing.getFluidState().is(Fluids.WATER)
                || existing.getFluidState().is(Fluids.LAVA)
                || isNaturalTerrain(existing)
                || isLivingModsPlaceholder(existing);
    }

    public static boolean isNaturalTerrain(BlockState state) {
        return isStrictNaturalTerrain(state)
                || state.is(BlockTags.LEAVES)
                || state.is(BlockTags.LOGS);
    }

    /** Terrain-only replaceability — excludes logs/leaves that may be player plantings. */
    public static boolean isStrictNaturalTerrain(BlockState state) {
        return state.is(BlockTags.DIRT)
                || state.is(BlockTags.BASE_STONE_OVERWORLD)
                || state.is(BlockTags.BASE_STONE_NETHER)
                || state.is(BlockTags.SAND)
                || state.is(BlockTags.TERRACOTTA)
                || state.is(Blocks.GRASS_BLOCK)
                || state.is(Blocks.SNOW)
                || state.is(Blocks.SNOW_BLOCK)
                || state.is(Blocks.CLAY)
                || state.is(Blocks.GRAVEL)
                || state.is(Blocks.MOSS_BLOCK)
                || state.is(Blocks.MUD)
                || state.is(Blocks.PACKED_MUD)
                || state.is(BlockTags.FLOWERS)
                || state.is(Blocks.TALL_GRASS)
                || state.is(Blocks.SHORT_GRASS)
                || state.is(Blocks.FERN)
                || state.is(Blocks.LARGE_FERN)
                || state.is(Blocks.VINE)
                || state.is(Blocks.WATER)
                || state.is(Blocks.LAVA)
                || state.is(Blocks.ICE)
                || state.is(Blocks.PACKED_ICE)
                || state.is(Blocks.BLUE_ICE)
                || state.is(Blocks.SEAGRASS)
                || state.is(Blocks.TALL_SEAGRASS)
                || state.is(Blocks.KELP)
                || state.is(Blocks.KELP_PLANT);
    }

    /** Soft marker: cobble/planks we may overwrite during the same generation pass. */
    private static boolean isLivingModsPlaceholder(BlockState state) {
        return state.is(Blocks.COBBLESTONE)
                || state.is(Blocks.STONE_BRICKS)
                || state.is(Blocks.MOSSY_STONE_BRICKS)
                || state.is(Blocks.OAK_PLANKS)
                || state.is(Blocks.SPRUCE_PLANKS)
                || state.is(Blocks.DARK_OAK_PLANKS)
                || state.is(Blocks.GRAVEL)
                || state.is(Blocks.DIRT_PATH)
                || state.is(Blocks.POLISHED_ANDESITE)
                || state.is(Blocks.POLISHED_DEEPSLATE)
                || state.is(Blocks.DEEPSLATE_BRICKS);
    }

    private static boolean isForeignProtected(BlockState state) {
        String id = state.getBlock().builtInRegistryHolder().key().location().toString();
        if (id.startsWith("livingmods:")) {
            return false;
        }
        if (id.startsWith("minecraft:")) {
            // Protect containers / functional vanilla blocks even without block-entity check races.
            return state.is(Blocks.CHEST)
                    || state.is(Blocks.TRAPPED_CHEST)
                    || state.is(Blocks.BARREL)
                    || state.is(Blocks.FURNACE)
                    || state.is(Blocks.BLAST_FURNACE)
                    || state.is(Blocks.SMOKER)
                    || state.is(Blocks.BREWING_STAND)
                    || state.is(Blocks.HOPPER)
                    || state.is(Blocks.DROPPER)
                    || state.is(Blocks.DISPENSER)
                    || state.is(Blocks.SPAWNER)
                    || state.is(Blocks.BEACON)
                    || state.is(Blocks.END_PORTAL_FRAME)
                    || state.is(Blocks.END_PORTAL)
                    || state.is(Blocks.NETHER_PORTAL);
        }
        // Soft refuse: never casually overwrite Create / SecurityCraft / Waystones / other mod blocks.
        return id.contains("create")
                || id.contains("securitycraft")
                || id.contains("waystone")
                || id.contains("immersiveengineering")
                || id.contains("mekanism")
                || id.contains("ae2")
                || id.contains("refinedstorage")
                || true; // any non-livingmods, non-minecraft block is protected
    }

    public int placed() {
        return placed;
    }

    public int placedCount() {
        return placed;
    }

    public int protectedCollisionCount() {
        return protectedCollisions;
    }
}
