package com.livingmods.neoforge.worldgen;

import com.livingmods.common.culture.CultureDefinition;
import com.livingmods.common.culture.CultureRegistry;
import com.livingmods.common.geo.BlockPos2;
import com.livingmods.common.geo.BoundingBox2;
import com.livingmods.worldgen.plan.PlannedSettlement;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Materializes wallPath with foundation, cultural palette, parapets, towers, and proper gates
 * (opening + gatehouse structure + road continuity) — never a single fence block.
 */
public final class WallMaterializer {
    private static final int WALL_HEIGHT = 5;
    private static final int TOWER_EVERY = 8;

    private final CultureRegistry cultures = new CultureRegistry();

    public void materialize(ServerLevel level, SafeChunkWriter writer, PlannedSettlement settlement) {
        if (!settlement.walls() || settlement.wallPath().isEmpty()) {
            return;
        }
        CultureDefinition culture = cultures.get(settlement.cultureKey()).orElse(cultures.all().get(0));
        BlockState wall = CulturalBlocks.primary(culture);
        BlockState parapet = CulturalBlocks.wall(culture);
        BlockState accent = CulturalBlocks.accent(culture);
        BlockState road = CulturalBlocks.road(culture);

        Set<Long> gateCells = new HashSet<>();
        for (BlockPos2 g : settlement.gatePositions()) {
            for (int dx = -2; dx <= 2; dx++) {
                for (int dz = -2; dz <= 2; dz++) {
                    gateCells.add(pack(g.x() + dx, g.z() + dz));
                }
            }
        }

        List<BlockPos2> ring = densifyRing(settlement.wallPath());
        BoundingBox2 chunkBox = chunkBox(writer);

        int index = 0;
        for (BlockPos2 p : ring) {
            if (!chunkBox.expand(2).contains(p)) {
                index++;
                continue;
            }
            if (gateCells.contains(pack(p.x(), p.z()))) {
                index++;
                continue;
            }
            int y = level.getHeight(Heightmap.Types.WORLD_SURFACE_WG, p.x(), p.z()) - 1;
            // Foundation
            writer.trySet(new BlockPos(p.x(), y, p.z()), Blocks.STONE_BRICKS.defaultBlockState());
            writer.trySet(new BlockPos(p.x(), y - 1, p.z()), Blocks.STONE_BRICKS.defaultBlockState());
            for (int h = 1; h <= WALL_HEIGHT; h++) {
                writer.trySet(new BlockPos(p.x(), y + h, p.z()), wall);
                // Thickness
                writer.trySet(new BlockPos(p.x() + 1, y + h, p.z()), wall);
            }
            // Parapet
            writer.trySet(new BlockPos(p.x(), y + WALL_HEIGHT + 1, p.z()), parapet);
            writer.trySet(new BlockPos(p.x() + 1, y + WALL_HEIGHT + 1, p.z()), parapet);
            if (index % 2 == 0) {
                writer.trySet(new BlockPos(p.x(), y + WALL_HEIGHT + 2, p.z()), accent);
            }
            // Towers
            if (index % TOWER_EVERY == 0) {
                placeTower(writer, p.x(), y, p.z(), wall, accent);
            }
            index++;
        }

        for (BlockPos2 gate : settlement.gatePositions()) {
            if (!chunkBox.expand(4).contains(gate)) continue;
            placeGate(level, writer, gate, wall, accent, road, culture);
        }
    }

    private void placeTower(SafeChunkWriter writer, int x, int baseY, int z, BlockState wall, BlockState accent) {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                for (int h = 1; h <= WALL_HEIGHT + 4; h++) {
                    boolean edge = Math.abs(dx) == 1 || Math.abs(dz) == 1;
                    if (edge) {
                        writer.trySet(new BlockPos(x + dx, baseY + h, z + dz), h > WALL_HEIGHT + 2 ? accent : wall);
                    } else {
                        writer.trySet(new BlockPos(x + dx, baseY + h, z + dz), Blocks.AIR.defaultBlockState());
                    }
                }
                writer.trySet(new BlockPos(x + dx, baseY + WALL_HEIGHT + 5, z + dz), accent);
            }
        }
        writer.trySet(new BlockPos(x, baseY + WALL_HEIGHT + 2, z), Blocks.LANTERN.defaultBlockState());
    }

    private void placeGate(
            ServerLevel level,
            SafeChunkWriter writer,
            BlockPos2 gate,
            BlockState wall,
            BlockState accent,
            BlockState road,
            CultureDefinition culture
    ) {
        int y = level.getHeight(Heightmap.Types.WORLD_SURFACE_WG, gate.x(), gate.z()) - 1;
        // Opening 3 wide, 4 high + gatehouse flanks
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                int x = gate.x() + dx;
                int z = gate.z() + dz;
                // Road continuity through gate
                writer.trySet(new BlockPos(x, y, z), road);
                for (int h = 1; h <= 4; h++) {
                    if (Math.abs(dx) <= 1) {
                        writer.trySet(new BlockPos(x, y + h, z), Blocks.AIR.defaultBlockState());
                    }
                }
            }
        }
        // Gate structure pillars + arch
        for (int h = 1; h <= 5; h++) {
            writer.trySet(new BlockPos(gate.x() - 2, y + h, gate.z()), wall);
            writer.trySet(new BlockPos(gate.x() + 2, y + h, gate.z()), wall);
        }
        for (int dx = -2; dx <= 2; dx++) {
            writer.trySet(new BlockPos(gate.x() + dx, y + 5, gate.z()), accent);
            writer.trySet(new BlockPos(gate.x() + dx, y + 6, gate.z()), CulturalBlocks.wall(culture));
        }
        // Optional gatehouse rooms on flanks
        for (int h = 1; h <= 4; h++) {
            writer.trySet(new BlockPos(gate.x() - 3, y + h, gate.z()), wall);
            writer.trySet(new BlockPos(gate.x() + 3, y + h, gate.z()), wall);
            writer.trySet(new BlockPos(gate.x() - 3, y + h, gate.z() + 1), wall);
            writer.trySet(new BlockPos(gate.x() + 3, y + h, gate.z() + 1), wall);
        }
        writer.trySet(new BlockPos(gate.x() - 3, y + 2, gate.z()), Blocks.LANTERN.defaultBlockState());
        writer.trySet(new BlockPos(gate.x() + 3, y + 2, gate.z()), Blocks.LANTERN.defaultBlockState());
        // Iron gate hint
        writer.trySet(new BlockPos(gate.x(), y + 1, gate.z()), Blocks.IRON_BARS.defaultBlockState());
        writer.trySet(new BlockPos(gate.x(), y + 2, gate.z()), Blocks.IRON_BARS.defaultBlockState());
    }

    private static List<BlockPos2> densifyRing(List<BlockPos2> ring) {
        if (ring.size() < 2) return ring;
        java.util.ArrayList<BlockPos2> out = new java.util.ArrayList<>();
        for (int i = 0; i < ring.size(); i++) {
            BlockPos2 a = ring.get(i);
            BlockPos2 b = ring.get((i + 1) % ring.size());
            out.addAll(RoadMaterializer.line(a, b));
        }
        return out;
    }

    private static long pack(int x, int z) {
        return ((long) x << 32) ^ (z & 0xffffffffL);
    }

    private static BoundingBox2 chunkBox(SafeChunkWriter writer) {
        int cx = writer.chunk().getPos().x << 4;
        int cz = writer.chunk().getPos().z << 4;
        return BoundingBox2.of(cx, cz, cx + 15, cz + 15);
    }
}
