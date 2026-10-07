package com.livingmods.neoforge.worldgen;

import com.livingmods.common.culture.CultureDefinition;
import com.livingmods.common.culture.CultureRegistry;
import com.livingmods.common.geo.BlockPos2;
import com.livingmods.common.geo.BoundingBox2;
import com.livingmods.common.geo.ChunkCoord;
import com.livingmods.common.model.RoadClass;
import com.livingmods.worldgen.plan.ChunkCivilizationSlice;
import com.livingmods.worldgen.plan.PlannedBuilding;
import com.livingmods.worldgen.plan.PlannedRoad;
import com.livingmods.worldgen.plan.PlannedSettlement;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;

public final class CivilizationMaterializer {
    private final CultureRegistry cultures = new CultureRegistry();

    public void materializeChunk(ServerLevel level, LevelChunk chunk, ChunkCivilizationSlice slice) {
        for (PlannedRoad road : slice.roads()) {
            placeRoad(level, chunk, road);
        }
        for (PlannedSettlement settlement : slice.settlements()) {
            for (PlannedBuilding building : settlement.buildings()) {
                placeBuilding(level, chunk, building, settlement.cultureKey());
            }
            if (settlement.walls()) {
                placeWalls(level, chunk, settlement);
            }
        }
    }

    private void placeRoad(ServerLevel level, LevelChunk chunk, PlannedRoad road) {
        BlockState surface = roadSurface(road.roadClass());
        ChunkCoord coord = ChunkCoord.of(chunk.getPos().x, chunk.getPos().z);
        BoundingBox2 box = BoundingBox2.of(coord.x() << 4, coord.z() << 4, (coord.x() << 4) + 15, (coord.z() << 4) + 15);
        for (BlockPos2 p : road.path()) {
            if (!box.contains(p)) continue;
            int y = level.getHeight(Heightmap.Types.WORLD_SURFACE_WG, p.x(), p.z());
            BlockPos pos = new BlockPos(p.x(), y - 1, p.z());
            if (chunk.getPos().x == pos.getX() >> 4 && chunk.getPos().z == pos.getZ() >> 4) {
                chunk.setBlockState(pos, surface, false);
            }
        }
    }

    private BlockState roadSurface(RoadClass roadClass) {
        return switch (roadClass) {
            case ROYAL_HIGHWAY -> Blocks.POLISHED_ANDESITE.defaultBlockState();
            case MAJOR, REGIONAL -> Blocks.COBBLESTONE.defaultBlockState();
            case LOCAL, VILLAGE -> Blocks.GRAVEL.defaultBlockState();
            default -> Blocks.DIRT.defaultBlockState();
        };
    }

    private void placeBuilding(ServerLevel level, LevelChunk chunk, PlannedBuilding building, String cultureKey) {
        CultureDefinition culture = cultures.get(cultureKey).orElse(cultures.all().get(0));
        CultureDefinition.ArchitectureStyle arch = culture.architecture();
        BlockState primary = Blocks.STONE_BRICKS.defaultBlockState();
        BlockState accent = Blocks.OAK_PLANKS.defaultBlockState();
        try {
            primary = net.minecraft.core.registries.BuiltInRegistries.BLOCK.get(
                    net.minecraft.resources.ResourceLocation.parse("minecraft:" + arch.primaryBlock())
            ).defaultBlockState();
            accent = net.minecraft.core.registries.BuiltInRegistries.BLOCK.get(
                    net.minecraft.resources.ResourceLocation.parse("minecraft:" + arch.secondaryBlock())
            ).defaultBlockState();
        } catch (Exception ignored) {}

        BoundingBox2 fp = building.footprint();
        int baseY = building.foundationY() > 0 ? building.foundationY() : level.getHeight(Heightmap.Types.WORLD_SURFACE_WG, fp.center().x(), fp.center().z());
        for (int x = fp.minX(); x <= fp.maxX(); x++) {
            for (int z = fp.minZ(); z <= fp.maxZ(); z++) {
                if ((x >> 4) != chunk.getPos().x || (z >> 4) != chunk.getPos().z) continue;
                for (int y = baseY; y < baseY + 4; y++) {
                    BlockState state = y == baseY ? Blocks.STONE_BRICKS.defaultBlockState() : (y == baseY + 3 ? accent : primary);
                    chunk.setBlockState(new BlockPos(x, y, z), state, false);
                }
            }
        }
    }

    private void placeWalls(ServerLevel level, LevelChunk chunk, PlannedSettlement settlement) {
        for (BlockPos2 gate : settlement.gatePositions()) {
            int y = level.getHeight(Heightmap.Types.WORLD_SURFACE_WG, gate.x(), gate.z());
            if ((gate.x() >> 4) == chunk.getPos().x && (gate.z() >> 4) == chunk.getPos().z) {
                chunk.setBlockState(new BlockPos(gate.x(), y, gate.z()), Blocks.OAK_FENCE.defaultBlockState(), false);
            }
        }
    }
}
