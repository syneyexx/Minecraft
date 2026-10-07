package com.livingmods.neoforge.worldgen;

import com.livingmods.common.culture.CultureDefinition;
import com.livingmods.common.culture.CultureRegistry;
import com.livingmods.common.geo.BlockPos2;
import com.livingmods.common.geo.BoundingBox2;
import com.livingmods.worldgen.plan.PlannedBridge;
import com.livingmods.worldgen.plan.PlannedRoad;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.Fluids;

import java.util.List;

/**
 * PlannedBridge → supports, deck, railings, ramps with cultural materials; continuous with road over water.
 */
public final class BridgeMaterializer {
    private final CultureRegistry cultures = new CultureRegistry();

    public void materialize(ServerLevel level, SafeChunkWriter writer, PlannedRoad road) {
        for (PlannedBridge bridge : road.bridges()) {
            materializeBridge(level, writer, bridge, road);
        }
    }

    private void materializeBridge(ServerLevel level, SafeChunkWriter writer, PlannedBridge bridge, PlannedRoad road) {
        CultureDefinition culture = cultures.get(bridge.cultureKey()).orElse(
                cultures.get(road.cultureKey()).orElse(cultures.all().get(0))
        );
        BlockState deck = switch (bridge.kind()) {
            case FORD -> Blocks.DIRT.defaultBlockState();
            case WOODEN -> Blocks.SPRUCE_PLANKS.defaultBlockState();
            case STONE, MAJOR -> CulturalBlocks.primary(culture);
        };
        BlockState rail = bridge.kind() == PlannedBridge.BridgeKind.WOODEN
                ? Blocks.SPRUCE_FENCE.defaultBlockState()
                : CulturalBlocks.wall(culture);
        BlockState support = bridge.kind() == PlannedBridge.BridgeKind.WOODEN
                ? Blocks.SPRUCE_LOG.defaultBlockState()
                : Blocks.STONE_BRICKS.defaultBlockState();

        List<BlockPos2> span = RoadMaterializer.line(bridge.start(), bridge.end());
        BoundingBox2 chunkBox = chunkBox(writer);
        if (span.isEmpty()) return;

        int startY = level.getHeight(Heightmap.Types.WORLD_SURFACE_WG, bridge.start().x(), bridge.start().z()) - 1;
        int endY = level.getHeight(Heightmap.Types.WORLD_SURFACE_WG, bridge.end().x(), bridge.end().z()) - 1;
        int deckY = Math.max(startY, endY) + (bridge.kind() == PlannedBridge.BridgeKind.FORD ? 0 : 1);

        int i = 0;
        for (BlockPos2 p : span) {
            if (!chunkBox.expand(2).contains(p)) {
                i++;
                continue;
            }
            double t = span.size() == 1 ? 0 : (double) i / (span.size() - 1);
            int rampY = (int) Math.round(startY + (endY - startY) * t);
            int y = bridge.kind() == PlannedBridge.BridgeKind.FORD ? rampY : Math.max(deckY, rampY);

            // Deck (road continuity, width 3)
            for (int w = -1; w <= 1; w++) {
                writer.trySet(new BlockPos(p.x() + w, y, p.z()), deck);
                writer.trySet(new BlockPos(p.x() + w, y + 1, p.z()), Blocks.AIR.defaultBlockState());
            }
            // Railings
            if (bridge.kind() != PlannedBridge.BridgeKind.FORD) {
                writer.trySet(new BlockPos(p.x() - 2, y + 1, p.z()), rail);
                writer.trySet(new BlockPos(p.x() + 2, y + 1, p.z()), rail);
            }
            // Supports / pillars into water
            if (bridge.kind() != PlannedBridge.BridgeKind.FORD && i % 3 == 0) {
                for (int sy = y - 1; sy >= y - 12; sy--) {
                    BlockPos sp = new BlockPos(p.x(), sy, p.z());
                    if (!writer.inChunk(sp)) break;
                    BlockState below = writer.chunk().getBlockState(sp);
                    if (!below.isAir() && !below.getFluidState().is(Fluids.WATER) && !SafeChunkWriter.isNaturalTerrain(below)) {
                        break;
                    }
                    writer.trySet(sp, support);
                    if (!below.getFluidState().is(Fluids.WATER) && !below.isAir() && !below.is(Blocks.WATER)) {
                        break;
                    }
                }
            }
            i++;
        }

        // Approach ramps
        placeRamp(level, writer, bridge.start(), startY, deckY, deck, -1);
        placeRamp(level, writer, bridge.end(), endY, deckY, deck, 1);
    }

    private void placeRamp(
            ServerLevel level, SafeChunkWriter writer, BlockPos2 end, int groundY, int deckY, BlockState deck, int dir
    ) {
        int steps = Math.max(1, Math.abs(deckY - groundY));
        for (int s = 0; s <= steps; s++) {
            int x = end.x() + dir * s;
            int z = end.z();
            int y = groundY + (deckY > groundY ? s : -s);
            for (int w = -1; w <= 1; w++) {
                writer.trySet(new BlockPos(x + w, y, z), deck);
            }
        }
    }

    private static BoundingBox2 chunkBox(SafeChunkWriter writer) {
        int cx = writer.chunk().getPos().x << 4;
        int cz = writer.chunk().getPos().z << 4;
        return BoundingBox2.of(cx, cz, cx + 15, cz + 15);
    }
}
