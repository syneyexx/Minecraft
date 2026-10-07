package com.livingmods.neoforge.worldgen;

import com.livingmods.common.culture.CultureDefinition;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/** Resolves culture palette strings to block states with safe vanilla fallbacks. */
public final class CulturalBlocks {
    private CulturalBlocks() {}

    public static BlockState resolve(String name, BlockState fallback) {
        if (name == null || name.isBlank()) {
            return fallback;
        }
        try {
            ResourceLocation id = name.contains(":")
                    ? ResourceLocation.parse(name)
                    : ResourceLocation.parse("minecraft:" + name);
            var block = BuiltInRegistries.BLOCK.get(id);
            if (block == null || block == Blocks.AIR) {
                return fallback;
            }
            return block.defaultBlockState();
        } catch (Exception e) {
            return fallback;
        }
    }

    public static BlockState primary(CultureDefinition culture) {
        return resolve(culture.architecture().primaryBlock(), Blocks.STONE_BRICKS.defaultBlockState());
    }

    public static BlockState secondary(CultureDefinition culture) {
        return resolve(culture.architecture().secondaryBlock(), Blocks.OAK_PLANKS.defaultBlockState());
    }

    public static BlockState accent(CultureDefinition culture) {
        return resolve(culture.architecture().accentBlock(), Blocks.SMOOTH_STONE.defaultBlockState());
    }

    public static BlockState roof(CultureDefinition culture) {
        return resolve(culture.architecture().roofBlock(), Blocks.SPRUCE_STAIRS.defaultBlockState());
    }

    public static BlockState road(CultureDefinition culture) {
        return resolve(culture.architecture().roadBlock(), Blocks.COBBLESTONE.defaultBlockState());
    }

    public static BlockState wall(CultureDefinition culture) {
        return resolve(culture.architecture().wallBlock(), Blocks.STONE_BRICK_WALL.defaultBlockState());
    }
}
