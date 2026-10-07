package com.livingmods.neoforge.worldgen;

import com.livingmods.common.geo.BlockPos2;
import com.livingmods.common.geo.BoundingBox2;
import com.livingmods.common.model.ResourceType;
import com.livingmods.worldgen.plan.PlannedResourceSite;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.Fluids;

/**
 * Farms, mines, and ports from PlannedResourceSite — field boundaries, docks, mine entrances,
 * without unsafe carving of unrelated structures.
 */
public final class ResourceSiteMaterializer {

    public void materialize(ServerLevel level, SafeChunkWriter writer, PlannedResourceSite site) {
        BoundingBox2 chunkBox = chunkBox(writer);
        BlockPos2 c = site.center();
        if (!chunkBox.expand(16).contains(c)) {
            return;
        }
        int y = level.getHeight(Heightmap.Types.WORLD_SURFACE_WG, c.x(), c.z()) - 1;
        switch (classify(site.resource())) {
            case FARM -> placeFarm(level, writer, c, y, site);
            case MINE -> placeMine(level, writer, c, y, site);
            case PORT -> placePort(level, writer, c, y, site);
            default -> placeGenericCamp(writer, c, y, site);
        }
    }

    private enum Kind { FARM, MINE, PORT, CAMP }

    private static Kind classify(ResourceType type) {
        return switch (type) {
            case GRAIN, VEGETABLES, LIVESTOCK, MEAT, FOOD -> Kind.FARM;
            case IRON, IRON_ORE, COAL, FUEL, GOLD, STONE -> Kind.MINE;
            case FISH, WATER -> Kind.PORT;
            default -> Kind.CAMP;
        };
    }

    private void placeFarm(ServerLevel level, SafeChunkWriter writer, BlockPos2 c, int y, PlannedResourceSite site) {
        int r = 6 + (int) (site.richness() * 4);
        BlockState crop = site.resource() == ResourceType.VEGETABLES
                ? Blocks.CARROTS.defaultBlockState().setValue(CropBlock.AGE, 7)
                : Blocks.WHEAT.defaultBlockState().setValue(CropBlock.AGE, 7);
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                int x = c.x() + dx;
                int z = c.z() + dz;
                if (!writer.inChunk(x, z)) continue;
                int gy = level.getHeight(Heightmap.Types.WORLD_SURFACE_WG, x, z) - 1;
                boolean edge = Math.abs(dx) == r || Math.abs(dz) == r;
                if (edge) {
                    writer.trySet(new BlockPos(x, gy + 1, z), Blocks.OAK_FENCE.defaultBlockState());
                    continue;
                }
                // Irrigation cross
                if (dx == 0 || dz == 0) {
                    writer.trySet(new BlockPos(x, gy, z), Blocks.WATER.defaultBlockState());
                    continue;
                }
                writer.trySet(new BlockPos(x, gy, z), Blocks.FARMLAND.defaultBlockState());
                writer.trySet(new BlockPos(x, gy + 1, z), crop);
            }
        }
        // Farmhouse + barn
        placeHut(writer, c.x() - r - 3, y, c.z(), Blocks.OAK_PLANKS.defaultBlockState(), true);
        placeHut(writer, c.x() + r + 2, y, c.z(), Blocks.SPRUCE_PLANKS.defaultBlockState(), false);
        // Path to center
        for (int i = 0; i < r; i++) {
            writer.trySet(new BlockPos(c.x() - r - 1 + i, y, c.z()), Blocks.DIRT_PATH.defaultBlockState());
        }
    }

    private void placeMine(ServerLevel level, SafeChunkWriter writer, BlockPos2 c, int y, PlannedResourceSite site) {
        // Entrance frame — shallow controlled shaft only, no wild carving
        BlockState frame = Blocks.OAK_LOG.defaultBlockState();
        for (int dx = -1; dx <= 1; dx++) {
            writer.trySet(new BlockPos(c.x() + dx, y + 1, c.z()), Blocks.AIR.defaultBlockState());
            writer.trySet(new BlockPos(c.x() + dx, y + 2, c.z()), Blocks.AIR.defaultBlockState());
        }
        writer.trySet(new BlockPos(c.x() - 2, y + 1, c.z()), frame);
        writer.trySet(new BlockPos(c.x() + 2, y + 1, c.z()), frame);
        writer.trySet(new BlockPos(c.x() - 2, y + 2, c.z()), frame);
        writer.trySet(new BlockPos(c.x() + 2, y + 2, c.z()), frame);
        writer.trySet(new BlockPos(c.x() - 1, y + 3, c.z()), frame);
        writer.trySet(new BlockPos(c.x(), y + 3, c.z()), frame);
        writer.trySet(new BlockPos(c.x() + 1, y + 3, c.z()), frame);

        // Supports + short tunnel (local chunk only)
        for (int d = 1; d <= 6; d++) {
            int x = c.x();
            int z = c.z() + d;
            int ty = y - Math.min(d / 2, 3);
            if (!writer.inChunk(x, z)) break;
            writer.trySet(new BlockPos(x, ty + 1, z), Blocks.AIR.defaultBlockState());
            writer.trySet(new BlockPos(x, ty + 2, z), Blocks.AIR.defaultBlockState());
            writer.trySet(new BlockPos(x - 1, ty + 1, z), Blocks.OAK_FENCE.defaultBlockState());
            writer.trySet(new BlockPos(x + 1, ty + 1, z), Blocks.OAK_FENCE.defaultBlockState());
            writer.trySet(new BlockPos(x, ty, z), Blocks.COBBLESTONE.defaultBlockState());
        }
        // Storage + worker hut + road stub
        writer.trySet(new BlockPos(c.x() - 4, y + 1, c.z()), Blocks.CHEST.defaultBlockState());
        placeHut(writer, c.x() - 6, y, c.z() - 3, Blocks.COBBLESTONE.defaultBlockState(), true);
        for (int i = 1; i <= 8; i++) {
            writer.trySet(new BlockPos(c.x() - i, y, c.z()), Blocks.GRAVEL.defaultBlockState());
        }
        if (site.resource() == ResourceType.IRON) {
            writer.trySet(new BlockPos(c.x(), y - 2, c.z() + 4), Blocks.IRON_ORE.defaultBlockState());
        } else if (site.resource() == ResourceType.COAL) {
            writer.trySet(new BlockPos(c.x(), y - 2, c.z() + 4), Blocks.COAL_ORE.defaultBlockState());
        }
    }

    private void placePort(ServerLevel level, SafeChunkWriter writer, BlockPos2 c, int y, PlannedResourceSite site) {
        // Find water nearby for dock alignment
        BlockPos2 water = findWater(level, c, 12);
        BlockPos2 dock = water != null ? water : c;
        int dy = level.getHeight(Heightmap.Types.WORLD_SURFACE_WG, dock.x(), dock.z()) - 1;
        // Dock planks into water
        for (int i = 0; i < 8; i++) {
            int x = dock.x() + i;
            int z = dock.z();
            if (!writer.inChunk(x, z)) continue;
            writer.trySet(new BlockPos(x, dy + 1, z), Blocks.SPRUCE_PLANKS.defaultBlockState());
            writer.trySet(new BlockPos(x, dy + 1, z + 1), Blocks.SPRUCE_PLANKS.defaultBlockState());
            writer.trySet(new BlockPos(x, dy + 2, z), Blocks.AIR.defaultBlockState());
            if (i % 2 == 0) {
                writer.trySet(new BlockPos(x, dy, z), Blocks.SPRUCE_LOG.defaultBlockState());
            }
        }
        // Warehouse + market pad + road
        placeHut(writer, c.x() - 4, y, c.z() - 4, Blocks.SPRUCE_PLANKS.defaultBlockState(), false);
        writer.trySet(new BlockPos(c.x() - 3, y + 1, c.z() - 3), Blocks.CHEST.defaultBlockState());
        writer.trySet(new BlockPos(c.x() - 2, y + 1, c.z() - 3), Blocks.BARREL.defaultBlockState());
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                writer.trySet(new BlockPos(c.x() + dx, y, c.z() + dz), Blocks.COBBLESTONE.defaultBlockState());
            }
        }
        for (int i = 0; i < 10; i++) {
            writer.trySet(new BlockPos(c.x() - i, y, c.z()), Blocks.GRAVEL.defaultBlockState());
        }
    }

    private void placeGenericCamp(SafeChunkWriter writer, BlockPos2 c, int y, PlannedResourceSite site) {
        placeHut(writer, c.x(), y, c.z(), Blocks.OAK_PLANKS.defaultBlockState(), true);
        writer.trySet(new BlockPos(c.x() + 3, y + 1, c.z()), Blocks.CHEST.defaultBlockState());
        if (site.resource() == ResourceType.WOOD) {
            writer.trySet(new BlockPos(c.x() - 2, y + 1, c.z()), Blocks.OAK_LOG.defaultBlockState());
        }
    }

    private void placeHut(SafeChunkWriter writer, int x, int y, int z, BlockState wall, boolean bed) {
        for (int dx = 0; dx < 4; dx++) {
            for (int dz = 0; dz < 4; dz++) {
                boolean edge = dx == 0 || dx == 3 || dz == 0 || dz == 3;
                writer.trySet(new BlockPos(x + dx, y, z + dz), Blocks.COBBLESTONE.defaultBlockState());
                for (int h = 1; h <= 3; h++) {
                    if (edge) {
                        if (dx == 1 && dz == 0 && h <= 2) {
                            writer.trySet(new BlockPos(x + dx, y + h, z + dz), Blocks.AIR.defaultBlockState());
                        } else {
                            writer.trySet(new BlockPos(x + dx, y + h, z + dz), wall);
                        }
                    } else {
                        writer.trySet(new BlockPos(x + dx, y + h, z + dz), Blocks.AIR.defaultBlockState());
                    }
                }
                writer.trySet(new BlockPos(x + dx, y + 4, z + dz), Blocks.OAK_STAIRS.defaultBlockState());
            }
        }
        if (bed) {
            writer.trySet(new BlockPos(x + 2, y + 1, z + 2), Blocks.RED_BED.defaultBlockState());
        }
        writer.trySet(new BlockPos(x + 1, y + 1, z + 2), Blocks.CRAFTING_TABLE.defaultBlockState());
    }

    private BlockPos2 findWater(ServerLevel level, BlockPos2 origin, int radius) {
        for (int r = 1; r <= radius; r++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (Math.abs(dx) != r && Math.abs(dz) != r) continue;
                    int x = origin.x() + dx;
                    int z = origin.z() + dz;
                    int y = level.getHeight(Heightmap.Types.WORLD_SURFACE_WG, x, z) - 1;
                    BlockState s = level.getBlockState(new BlockPos(x, y, z));
                    if (s.getFluidState().is(Fluids.WATER) || s.is(Blocks.WATER)) {
                        return BlockPos2.of(x, z);
                    }
                }
            }
        }
        return null;
    }

    private static BoundingBox2 chunkBox(SafeChunkWriter writer) {
        int cx = writer.chunk().getPos().x << 4;
        int cz = writer.chunk().getPos().z << 4;
        return BoundingBox2.of(cx, cz, cx + 15, cz + 15);
    }
}
