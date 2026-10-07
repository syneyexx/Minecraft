package com.livingmods.neoforge.worldgen;

import com.livingmods.common.geo.BlockPos2;
import com.livingmods.common.geo.BoundingBox2;
import com.livingmods.common.util.DeterministicRandom;
import com.livingmods.common.util.Hashing;
import com.livingmods.worldgen.plan.PlannedBanditCamp;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * Bandit camp variants: road camp, hideout, forest, ruined-fort, stronghold.
 */
public final class BanditCampMaterializer {

    public void materialize(ServerLevel level, SafeChunkWriter writer, PlannedBanditCamp camp) {
        BoundingBox2 chunkBox = chunkBox(writer);
        if (!chunkBox.expand(camp.size() + 4).contains(camp.center())) {
            return;
        }
        int y = level.getHeight(Heightmap.Types.WORLD_SURFACE_WG, camp.center().x(), camp.center().z()) - 1;
        DeterministicRandom random = new DeterministicRandom(Hashing.mix(camp.center().packed(), camp.size()));
        switch (camp.variant()) {
            case ROAD_CAMP -> placeRoadCamp(writer, camp.center(), y, camp.size(), random);
            case HIDEOUT -> placeHideout(writer, camp.center(), y, camp.size(), random);
            case FOREST -> placeForest(writer, camp.center(), y, camp.size(), random);
            case RUINED_FORT -> placeRuinedFort(writer, camp.center(), y, camp.size(), random);
            case STRONGHOLD -> placeStronghold(writer, camp.center(), y, camp.size(), random);
        }
    }

    private void placeRoadCamp(SafeChunkWriter writer, BlockPos2 c, int y, int size, DeterministicRandom random) {
        int tents = 2 + size / 3;
        for (int i = 0; i < tents; i++) {
            int ox = random.nextInt(-size, size + 1);
            int oz = random.nextInt(-size, size + 1);
            placeTent(writer, c.x() + ox, y, c.z() + oz);
        }
        writer.trySet(new BlockPos(c.x(), y + 1, c.z()), Blocks.CAMPFIRE.defaultBlockState());
        writer.trySet(new BlockPos(c.x() + 2, y + 1, c.z()), Blocks.CHEST.defaultBlockState());
        // Roadside barrier
        for (int dx = -size; dx <= size; dx++) {
            writer.trySet(new BlockPos(c.x() + dx, y + 1, c.z() - size), Blocks.OAK_FENCE.defaultBlockState());
        }
    }

    private void placeHideout(SafeChunkWriter writer, BlockPos2 c, int y, int size, DeterministicRandom random) {
        // Semi-buried dugout
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                for (int dy = -2; dy <= 1; dy++) {
                    boolean edge = Math.abs(dx) == 2 || Math.abs(dz) == 2 || dy == -2;
                    BlockPos p = new BlockPos(c.x() + dx, y + dy, c.z() + dz);
                    if (edge) {
                        writer.trySet(p, Blocks.MOSSY_COBBLESTONE.defaultBlockState());
                    } else {
                        writer.trySet(p, Blocks.AIR.defaultBlockState());
                    }
                }
            }
        }
        writer.trySet(new BlockPos(c.x(), y - 1, c.z()), Blocks.RED_BED.defaultBlockState());
        writer.trySet(new BlockPos(c.x() + 1, y - 1, c.z()), Blocks.CHEST.defaultBlockState());
        writer.trySet(new BlockPos(c.x(), y + 2, c.z()), Blocks.OAK_TRAPDOOR.defaultBlockState());
    }

    private void placeForest(SafeChunkWriter writer, BlockPos2 c, int y, int size, DeterministicRandom random) {
        for (int i = 0; i < 3 + size / 2; i++) {
            int ox = random.nextInt(-size, size + 1);
            int oz = random.nextInt(-size, size + 1);
            placeTent(writer, c.x() + ox, y, c.z() + oz);
            if (random.chance(0.4)) {
                writer.trySet(new BlockPos(c.x() + ox + 2, y + 1, c.z() + oz), Blocks.OAK_LEAVES.defaultBlockState());
            }
        }
        writer.trySet(new BlockPos(c.x(), y + 1, c.z()), Blocks.CAMPFIRE.defaultBlockState());
        // Camouflage brush ring
        for (int a = 0; a < 12; a++) {
            double ang = a * Math.PI / 6;
            int x = c.x() + (int) (Math.cos(ang) * size);
            int z = c.z() + (int) (Math.sin(ang) * size);
            writer.trySet(new BlockPos(x, y + 1, z), Blocks.OAK_LEAVES.defaultBlockState());
        }
    }

    private void placeRuinedFort(SafeChunkWriter writer, BlockPos2 c, int y, int size, DeterministicRandom random) {
        int r = Math.max(4, size);
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                boolean edge = Math.abs(dx) == r || Math.abs(dz) == r;
                if (!edge) continue;
                if (random.chance(0.35)) continue; // decay gaps
                int h = 2 + random.nextInt(4);
                for (int dy = 1; dy <= h; dy++) {
                    BlockState mat = random.chance(0.3)
                            ? Blocks.MOSSY_STONE_BRICKS.defaultBlockState()
                            : Blocks.STONE_BRICKS.defaultBlockState();
                    writer.trySet(new BlockPos(c.x() + dx, y + dy, c.z() + dz), mat);
                }
            }
        }
        writer.trySet(new BlockPos(c.x(), y + 1, c.z()), Blocks.CAMPFIRE.defaultBlockState());
        writer.trySet(new BlockPos(c.x() + 1, y + 1, c.z()), Blocks.CHEST.defaultBlockState());
        writer.trySet(new BlockPos(c.x() - 1, y + 1, c.z() - 1), Blocks.COBBLESTONE.defaultBlockState()); // rubble
    }

    private void placeStronghold(SafeChunkWriter writer, BlockPos2 c, int y, int size, DeterministicRandom random) {
        int r = Math.max(6, size);
        // Keep walls
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                boolean edge = Math.abs(dx) == r || Math.abs(dz) == r;
                if (!edge) continue;
                for (int dy = 1; dy <= 6; dy++) {
                    writer.trySet(new BlockPos(c.x() + dx, y + dy, c.z() + dz), Blocks.STONE_BRICKS.defaultBlockState());
                }
                writer.trySet(new BlockPos(c.x() + dx, y + 7, c.z() + dz), Blocks.STONE_BRICK_WALL.defaultBlockState());
            }
        }
        // Gate gap
        for (int dy = 1; dy <= 3; dy++) {
            writer.trySet(new BlockPos(c.x(), y + dy, c.z() - r), Blocks.AIR.defaultBlockState());
            writer.trySet(new BlockPos(c.x() + 1, y + dy, c.z() - r), Blocks.AIR.defaultBlockState());
        }
        // Inner keep
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                boolean edge = Math.abs(dx) == 2 || Math.abs(dz) == 2;
                for (int dy = 1; dy <= 5; dy++) {
                    if (edge) {
                        writer.trySet(new BlockPos(c.x() + dx, y + dy, c.z() + dz), Blocks.DEEPSLATE_BRICKS.defaultBlockState());
                    } else {
                        writer.trySet(new BlockPos(c.x() + dx, y + dy, c.z() + dz), Blocks.AIR.defaultBlockState());
                    }
                }
            }
        }
        writer.trySet(new BlockPos(c.x(), y + 1, c.z()), Blocks.CHEST.defaultBlockState());
        writer.trySet(new BlockPos(c.x() + 1, y + 1, c.z()), Blocks.CRAFTING_TABLE.defaultBlockState());
        writer.trySet(new BlockPos(c.x() - 1, y + 1, c.z()), Blocks.WHITE_BED.defaultBlockState());
        // Corner towers
        for (int[] t : new int[][]{{-r, -r}, {-r, r}, {r, -r}, {r, r}}) {
            for (int dy = 1; dy <= 9; dy++) {
                writer.trySet(new BlockPos(c.x() + t[0], y + dy, c.z() + t[1]), Blocks.STONE_BRICKS.defaultBlockState());
            }
        }
    }

    private void placeTent(SafeChunkWriter writer, int x, int y, int z) {
        writer.trySet(new BlockPos(x, y + 1, z), Blocks.RED_WOOL.defaultBlockState());
        writer.trySet(new BlockPos(x + 1, y + 1, z), Blocks.RED_WOOL.defaultBlockState());
        writer.trySet(new BlockPos(x, y + 1, z + 1), Blocks.RED_WOOL.defaultBlockState());
        writer.trySet(new BlockPos(x + 1, y + 1, z + 1), Blocks.RED_WOOL.defaultBlockState());
        writer.trySet(new BlockPos(x, y + 2, z), Blocks.RED_WOOL.defaultBlockState());
        writer.trySet(new BlockPos(x + 1, y + 2, z), Blocks.OAK_FENCE.defaultBlockState());
    }

    private static BoundingBox2 chunkBox(SafeChunkWriter writer) {
        int cx = writer.chunk().getPos().x << 4;
        int cz = writer.chunk().getPos().z << 4;
        return BoundingBox2.of(cx, cz, cx + 15, cz + 15);
    }
}
