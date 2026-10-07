package com.livingmods.common.model;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Technology with prerequisites that affect production, structures, and equipment.
 */
public record TechnologyDefinition(
        String key,
        String displayName,
        Set<String> prerequisites,
        double researchDifficulty,
        Map<ResourceType, Double> productionMultipliers,
        List<String> unlockedStructures,
        List<String> unlockedEquipment,
        boolean requiresSchool
) {
    public static final String BASIC_TOOLS = "basic_tools";
    public static final String IRON_WORKING = "iron_working";
    public static final String AGRICULTURE = "agriculture";
    public static final String ADVANCED_AGRICULTURE = "advanced_agriculture";
    public static final String MASONRY = "masonry";
    public static final String MEDICINE = "medicine";
    public static final String LITERACY = "literacy";
    public static final String MILITARY_DRILL = "military_drill";

    public static List<TechnologyDefinition> catalog() {
        return List.of(
                new TechnologyDefinition(BASIC_TOOLS, "Basic Tools", Set.of(), 0.4,
                        Map.of(ResourceType.WOOD, 1.1, ResourceType.STONE, 1.1),
                        List.of("workshop"), List.of("stone_axe"), false),
                new TechnologyDefinition(AGRICULTURE, "Agriculture", Set.of(BASIC_TOOLS), 0.6,
                        Map.of(ResourceType.GRAIN, 1.25, ResourceType.VEGETABLES, 1.2),
                        List.of("farm", "granary"), List.of(), false),
                new TechnologyDefinition(IRON_WORKING, "Iron Working", Set.of(BASIC_TOOLS), 0.8,
                        Map.of(ResourceType.IRON, 1.3, ResourceType.TOOLS, 1.4),
                        List.of("forge"), List.of("iron_tools", "iron_weapons"), false),
                new TechnologyDefinition(LITERACY, "Literacy", Set.of(BASIC_TOOLS), 0.7,
                        Map.of(ResourceType.KNOWLEDGE, 1.5),
                        List.of("school"), List.of(), true),
                new TechnologyDefinition(ADVANCED_AGRICULTURE, "Advanced Agriculture",
                        Set.of(AGRICULTURE, IRON_WORKING, LITERACY), 1.0,
                        Map.of(ResourceType.GRAIN, 1.6, ResourceType.VEGETABLES, 1.4, ResourceType.LIVESTOCK, 1.3),
                        List.of("irrigation", "mill"), List.of("plow"), true),
                new TechnologyDefinition(MASONRY, "Masonry", Set.of(BASIC_TOOLS), 0.7,
                        Map.of(ResourceType.STONE, 1.35),
                        List.of("stone_wall", "keep"), List.of(), false),
                new TechnologyDefinition(MEDICINE, "Medicine", Set.of(LITERACY), 0.9,
                        Map.of(ResourceType.MEDICINE, 1.5),
                        List.of("clinic"), List.of("herbal_kit"), true),
                new TechnologyDefinition(MILITARY_DRILL, "Military Drill", Set.of(IRON_WORKING), 0.75,
                        Map.of(ResourceType.WEAPONS, 1.25, ResourceType.ARMOR, 1.2),
                        List.of("barracks"), List.of("trained_guard"), false)
        );
    }

    public static TechnologyDefinition byKey(String key) {
        for (TechnologyDefinition def : catalog()) {
            if (def.key().equals(key)) {
                return def;
            }
        }
        return null;
    }
}
