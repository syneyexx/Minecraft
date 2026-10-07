package com.livingmods.neoforge.worldgen;

import com.livingmods.common.culture.CultureDefinition;
import com.livingmods.common.culture.CultureRegistry;
import com.livingmods.common.geo.BoundingBox2;
import com.livingmods.common.util.DeterministicRandom;
import com.livingmods.worldgen.plan.PlannedRuin;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * Ruins with age/decay: partial walls, rubble, vegetation, preserving historical metadata via seed.
 */
public final class RuinMaterializer {
    private final CultureRegistry cultures = new CultureRegistry();

    public void materialize(ServerLevel level, SafeChunkWriter writer, PlannedRuin ruin) {
        BoundingBox2 bounds = ruin.bounds();
        BoundingBox2 chunkBox = chunkBox(writer);
        if (!bounds.intersects(chunkBox.expand(1))) {
            return;
        }
        CultureDefinition culture = cultures.get(ruin.cultureKey()).orElse(cultures.all().get(0));
        BlockState primary = CulturalBlocks.primary(culture);
        BlockState mossy = Blocks.MOSSY_STONE_BRICKS.defaultBlockState();
        DeterministicRandom random = new DeterministicRandom(ruin.decaySeed());
        // Decay intensity from seed
        double decay = 0.35 + (Math.floorMod(ruin.decaySeed(), 100) / 100.0) * 0.45;

        int cx = bounds.center().x();
        int cz = bounds.center().z();
        int baseY = level.getHeight(Heightmap.Types.WORLD_SURFACE_WG, cx, cz) - 1;

        for (int x = bounds.minX(); x <= bounds.maxX(); x++) {
            for (int z = bounds.minZ(); z <= bounds.maxZ(); z++) {
                if (!writer.inChunk(x, z)) continue;
                boolean edge = x == bounds.minX() || x == bounds.maxX() || z == bounds.minZ() || z == bounds.maxZ();
                int y = level.getHeight(Heightmap.Types.WORLD_SURFACE_WG, x, z) - 1;

                if (edge) {
                    if (random.nextDouble() < decay) {
                        // Missing wall segment
                        if (random.chance(0.4)) {
                            writer.trySet(new BlockPos(x, y + 1, z), Blocks.COBBLESTONE.defaultBlockState()); // rubble
                        }
                        continue;
                    }
                    int h = 1 + random.nextInt(4);
                    for (int dy = 1; dy <= h; dy++) {
                        BlockState mat = random.chance(0.45) ? mossy : primary;
                        writer.trySet(new BlockPos(x, y + dy, z), mat);
                    }
                    if (random.chance(0.2)) {
                        writer.trySet(new BlockPos(x, y + h + 1, z), Blocks.VINE.defaultBlockState());
                    }
                } else if (random.chance(0.08 * decay)) {
                    // Interior rubble / vegetation
                    writer.trySet(new BlockPos(x, y + 1, z), random.chance(0.5)
                            ? Blocks.COBBLESTONE.defaultBlockState()
                            : Blocks.MOSS_CARPET.defaultBlockState());
                } else if (random.chance(0.03)) {
                    writer.trySet(new BlockPos(x, y + 1, z), Blocks.POPPY.defaultBlockState());
                }
            }
        }

        // Collapsed corner accent + historical "marker" (lodestone-like aesthetic)
        writer.trySet(new BlockPos(cx, baseY + 1, cz), Blocks.CHISELED_STONE_BRICKS.defaultBlockState());
        if (random.chance(0.5)) {
            writer.trySet(new BlockPos(cx, baseY + 2, cz), Blocks.MOSS_BLOCK.defaultBlockState());
        }
        // decaySeed / historicalNote are plan-side metadata; physical hint via cracked bricks density
    }

    private static BoundingBox2 chunkBox(SafeChunkWriter writer) {
        int cx = writer.chunk().getPos().x << 4;
        int cz = writer.chunk().getPos().z << 4;
        return BoundingBox2.of(cx, cz, cx + 15, cz + 15);
    }
}
