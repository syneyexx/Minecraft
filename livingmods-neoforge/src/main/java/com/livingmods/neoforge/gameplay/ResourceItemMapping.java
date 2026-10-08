package com.livingmods.neoforge.gameplay;

import com.livingmods.common.model.ResourceType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

import java.util.Locale;
import java.util.Optional;

/**
 * Single authoritative ResourceType ↔ Minecraft item mapping for player trade/delivery.
 * Currency authority: {@link ResourceType#GOLD} ↔ {@link Items#GOLD_INGOT}.
 */
public final class ResourceItemMapping {
    private ResourceItemMapping() {}

    public static Optional<Item> itemFor(ResourceType type) {
        if (type == null) return Optional.empty();
        return Optional.ofNullable(switch (type) {
            case GRAIN, FOOD -> Items.WHEAT;
            case VEGETABLES -> Items.CARROT;
            case MEAT -> Items.BEEF;
            case FISH -> Items.COD;
            case WOOD, CONSTRUCTION -> Items.OAK_LOG;
            case STONE -> Items.COBBLESTONE;
            case IRON, IRON_ORE -> Items.IRON_INGOT;
            case COAL, FUEL -> Items.COAL;
            case GOLD -> Items.GOLD_INGOT;
            case TOOLS -> Items.IRON_PICKAXE;
            case WEAPONS -> Items.IRON_SWORD;
            case ARMOR -> Items.IRON_CHESTPLATE;
            case CLOTH, TEXTILES -> Items.WHITE_WOOL;
            case LUXURY -> Items.GOLDEN_APPLE;
            case MEDICINE -> Items.GLISTERING_MELON_SLICE;
            case LIVESTOCK -> Items.LEATHER;
            case WATER -> Items.WATER_BUCKET;
            case KNOWLEDGE -> Items.BOOK;
        });
    }

    public static Optional<Item> itemFor(String resourceName) {
        if (resourceName == null || resourceName.isBlank()) return Optional.empty();
        try {
            return itemFor(ResourceType.valueOf(resourceName.trim().toUpperCase(Locale.ROOT)));
        } catch (Exception e) {
            return switch (resourceName.trim().toUpperCase(Locale.ROOT)) {
                case "WHEAT", "BREAD" -> Optional.of(Items.WHEAT);
                case "LOG" -> Optional.of(Items.OAK_LOG);
                default -> Optional.empty();
            };
        }
    }

    public static Item currencyItem() {
        return Items.GOLD_INGOT;
    }

    public static ResourceType currencyResource() {
        return ResourceType.GOLD;
    }
}
