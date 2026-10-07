package com.livingmods.common.model;

/**
 * Canonical resource categories. New values are appended only so save ordinals stay stable.
 */
public enum ResourceType {
    GRAIN,
    VEGETABLES,
    MEAT,
    FISH,
    WOOD,
    STONE,
    IRON,
    COAL,
    GOLD,
    TOOLS,
    WEAPONS,
    ARMOR,
    CLOTH,
    LUXURY,
    MEDICINE,
    KNOWLEDGE,
    LIVESTOCK,
    WATER,
    /** Milled / baked consumable food (grain chain output). */
    FOOD,
    /** Raw ore for the iron → tools/weapons chain. */
    IRON_ORE,
    /** Textile goods (finished cloth products). */
    TEXTILES,
    /** Coal/charcoal/fuel for smelting and heating. */
    FUEL,
    /** Timber, stone, and processed building stock. */
    CONSTRUCTION
}
