package com.livingmods.neoforge.worldgen;

import com.livingmods.common.culture.CultureDefinition;
import com.livingmods.common.culture.CultureRegistry;
import com.livingmods.common.geo.BlockPos2;
import com.livingmods.common.geo.BoundingBox2;
import com.livingmods.common.model.RoadClass;
import com.livingmods.common.util.DeterministicRandom;
import com.livingmods.common.util.Hashing;
import com.livingmods.worldgen.plan.PlannedRoad;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.ArrayList;
import java.util.List;

/**
 * Continuous roads with class-based width, interpolated segments, grading / stairs / retaining,
 * and sparse roadside details. Uses culture roadBlock when available.
 */
public final class RoadMaterializer {
    private final CultureRegistry cultures = new CultureRegistry();

    public void materialize(ServerLevel level, SafeChunkWriter writer, PlannedRoad road) {
        CultureDefinition culture = cultures.get(road.cultureKey()).orElse(null);
        BlockState surface = culture != null
                ? CulturalBlocks.road(culture)
                : fallbackSurface(road.roadClass());
        int width = widthFor(road.roadClass());
        List<BlockPos2> smoothed = interpolate(road.path());
        BoundingBox2 chunkBox = chunkBox(writer);

        Integer prevY = null;
        int pointIndex = 0;
        for (BlockPos2 p : smoothed) {
            if (!chunkBox.expand(width + 1).contains(p)) {
                pointIndex++;
                continue;
            }
            int rawY = level.getHeight(Heightmap.Types.WORLD_SURFACE_WG, p.x(), p.z()) - 1;
            int y = prevY == null ? rawY : gradeY(prevY, rawY);
            prevY = y;

            BlockPos2 next = pointIndex + 1 < smoothed.size() ? smoothed.get(pointIndex + 1) : p;
            int dx = Integer.signum(next.x() - p.x());
            int dz = Integer.signum(next.z() - p.z());
            // Perpendicular for width
            int px = -dz;
            int pz = dx;
            if (px == 0 && pz == 0) {
                px = 1;
            }
            int half = width / 2;
            for (int w = -half; w <= half; w++) {
                int x = p.x() + px * w;
                int z = p.z() + pz * w;
                placeRoadColumn(level, writer, x, z, y, surface, prevY, road.roadClass());
            }

            // Sparse roadside details
            DeterministicRandom decor = new DeterministicRandom(Hashing.mix(road.id().hashCode(), p.packed()));
            if (decor.chance(0.012) && road.roadClass().ordinal() <= RoadClass.REGIONAL.ordinal()) {
                placeRoadside(writer, p.x() + px * (half + 1), y, p.z() + pz * (half + 1), decor, road.roadClass());
            }
            pointIndex++;
        }
    }

    public void materializeStreetNetwork(
            ServerLevel level,
            SafeChunkWriter writer,
            List<BlockPos2> streets,
            String cultureKey
    ) {
        if (streets.isEmpty()) return;
        CultureDefinition culture = cultures.get(cultureKey).orElse(null);
        BlockState surface = culture != null
                ? CulturalBlocks.road(culture)
                : Blocks.DIRT_PATH.defaultBlockState();
        BoundingBox2 chunkBox = chunkBox(writer);
        // Connect sequential street samples with short segments
        for (int i = 0; i < streets.size(); i++) {
            BlockPos2 a = streets.get(i);
            BlockPos2 b = streets.get((i + 1) % streets.size());
            if (a.distanceTo(b) > 24) continue;
            for (BlockPos2 p : line(a, b)) {
                if (!chunkBox.expand(2).contains(p)) continue;
                int y = level.getHeight(Heightmap.Types.WORLD_SURFACE_WG, p.x(), p.z()) - 1;
                for (int w = -1; w <= 1; w++) {
                    placeRoadColumn(level, writer, p.x() + w, p.z(), y, surface, y, RoadClass.VILLAGE);
                }
            }
        }
        // Also place disks at each sample so isolated nodes still get pavement
        for (BlockPos2 p : streets) {
            if (!chunkBox.expand(2).contains(p)) continue;
            int y = level.getHeight(Heightmap.Types.WORLD_SURFACE_WG, p.x(), p.z()) - 1;
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    placeRoadColumn(level, writer, p.x() + dx, p.z() + dz, y, surface, y, RoadClass.VILLAGE);
                }
            }
        }
    }

    private void placeRoadColumn(
            ServerLevel level,
            SafeChunkWriter writer,
            int x,
            int z,
            int y,
            BlockState surface,
            int refY,
            RoadClass roadClass
    ) {
        if (!writer.inChunk(x, z)) return;
        int surfaceY = level.getHeight(Heightmap.Types.WORLD_SURFACE_WG, x, z) - 1;
        int targetY = gradeY(refY, surfaceY);

        // Retaining / fill
        if (surfaceY < targetY) {
            for (int fy = surfaceY + 1; fy <= targetY; fy++) {
                writer.trySet(new BlockPos(x, fy, z), Blocks.COBBLESTONE.defaultBlockState());
            }
        } else if (surfaceY > targetY + 1) {
            // Cut with stair step
            for (int cy = targetY + 1; cy <= surfaceY + 2; cy++) {
                writer.trySet(new BlockPos(x, cy, z), Blocks.AIR.defaultBlockState());
            }
            if (surfaceY - targetY >= 2) {
                writer.trySet(new BlockPos(x, targetY + 1, z), Blocks.COBBLESTONE_STAIRS.defaultBlockState());
            }
        }

        writer.trySet(new BlockPos(x, targetY, z), surface);
        writer.trySet(new BlockPos(x, targetY + 1, z), Blocks.AIR.defaultBlockState());
        writer.trySet(new BlockPos(x, targetY + 2, z), Blocks.AIR.defaultBlockState());

        // Switchback retaining on steep major roads
        if (roadClass == RoadClass.ROYAL_HIGHWAY || roadClass == RoadClass.MAJOR) {
            if (Math.abs(surfaceY - refY) >= 3) {
                writer.trySet(new BlockPos(x, targetY - 1, z), Blocks.STONE_BRICKS.defaultBlockState());
            }
        }
    }

    private void placeRoadside(SafeChunkWriter writer, int x, int y, int z, DeterministicRandom decor, RoadClass cls) {
        if (!writer.inChunk(x, z)) return;
        int roll = decor.nextInt(4);
        BlockPos base = new BlockPos(x, y + 1, z);
        switch (roll) {
            case 0 -> {
                writer.trySet(base, Blocks.OAK_FENCE.defaultBlockState());
                writer.trySet(base.above(), Blocks.TORCH.defaultBlockState());
            }
            case 1 -> writer.trySet(base, Blocks.OAK_SIGN.defaultBlockState());
            case 2 -> {
                if (cls == RoadClass.ROYAL_HIGHWAY || cls == RoadClass.MAJOR) {
                    writer.trySet(base, Blocks.STONE_BRICK_WALL.defaultBlockState());
                    writer.trySet(base.above(), Blocks.STONE_BRICK_WALL.defaultBlockState());
                }
            }
            default -> writer.trySet(new BlockPos(x, y, z), Blocks.COBBLESTONE.defaultBlockState()); // milestone pad
        }
    }

    private static int gradeY(int prev, int raw) {
        int delta = raw - prev;
        if (delta > 1) return prev + 1;
        if (delta < -1) return prev - 1;
        return raw;
    }

    private static int widthFor(RoadClass roadClass) {
        return switch (roadClass) {
            case ROYAL_HIGHWAY -> 6;
            case MAJOR -> 5;
            case REGIONAL -> 3;
            case LOCAL -> 3;
            case VILLAGE -> 2;
            case TRAIL -> 1;
        };
    }

    private static BlockState fallbackSurface(RoadClass roadClass) {
        return CulturalBlocks.resolve(roadClass.defaultMaterial(), Blocks.COBBLESTONE.defaultBlockState());
    }

    /** Densify sparse path points into continuous unit steps. */
    static List<BlockPos2> interpolate(List<BlockPos2> path) {
        if (path.size() < 2) return path;
        List<BlockPos2> out = new ArrayList<>();
        for (int i = 0; i < path.size() - 1; i++) {
            out.addAll(line(path.get(i), path.get(i + 1)));
        }
        out.add(path.get(path.size() - 1));
        return out;
    }

    static List<BlockPos2> line(BlockPos2 a, BlockPos2 b) {
        List<BlockPos2> pts = new ArrayList<>();
        int x0 = a.x();
        int z0 = a.z();
        int x1 = b.x();
        int z1 = b.z();
        int dx = Math.abs(x1 - x0);
        int dz = Math.abs(z1 - z0);
        int sx = Integer.signum(x1 - x0);
        int sz = Integer.signum(z1 - z0);
        int err = dx - dz;
        int x = x0;
        int z = z0;
        int guard = dx + dz + 2;
        while (guard-- > 0) {
            pts.add(BlockPos2.of(x, z));
            if (x == x1 && z == z1) break;
            int e2 = 2 * err;
            if (e2 > -dz) {
                err -= dz;
                x += sx;
            }
            if (e2 < dx) {
                err += dx;
                z += sz;
            }
        }
        return pts;
    }

    private static BoundingBox2 chunkBox(SafeChunkWriter writer) {
        int cx = writer.chunk().getPos().x << 4;
        int cz = writer.chunk().getPos().z << 4;
        return BoundingBox2.of(cx, cz, cx + 15, cz + 15);
    }
}
