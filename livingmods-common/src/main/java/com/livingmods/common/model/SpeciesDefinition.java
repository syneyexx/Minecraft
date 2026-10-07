package com.livingmods.common.model;

import com.livingmods.common.id.SpeciesId;

import java.util.List;
import java.util.Set;

/** Data-driven wildlife / livestock species used by EcologyEngine. */
public record SpeciesDefinition(
        SpeciesId id,
        String key,
        String displayName,
        SpeciesArchetype archetype,
        Set<String> habitats,
        double carryingCapacity,
        double reproductionRate,
        double mortalityRate,
        Set<String> dietKeys,
        Set<String> predatorKeys,
        Set<String> preyKeys,
        boolean migrates,
        String physicalEntityMapping
) {
    public static List<SpeciesDefinition> catalog() {
        return List.of(
                def(0, "deer", "Deer", SpeciesArchetype.HERBIVORE,
                        Set.of("forest", "plains", "taiga"), 120, 0.04, 0.015,
                        Set.of("plants"), Set.of("wolf"), Set.of(), true, "minecraft:deer"),
                def(1, "wolf", "Wolf", SpeciesArchetype.PREDATOR,
                        Set.of("forest", "taiga", "mountains"), 30, 0.02, 0.02,
                        Set.of("deer", "hare", "livestock_sheep"), Set.of(), Set.of("deer", "hare"), true, "minecraft:wolf"),
                def(2, "hare", "Hare", SpeciesArchetype.SMALL_GAME,
                        Set.of("plains", "forest", "meadow"), 200, 0.08, 0.03,
                        Set.of("plants"), Set.of("wolf", "eagle"), Set.of(), false, "minecraft:rabbit"),
                def(3, "eagle", "Eagle", SpeciesArchetype.BIRD,
                        Set.of("mountains", "plains", "coast"), 20, 0.015, 0.01,
                        Set.of("hare", "fish"), Set.of(), Set.of("hare"), true, "minecraft:eagle"),
                def(4, "fish", "River Fish", SpeciesArchetype.AQUATIC,
                        Set.of("river", "lake", "coast"), 300, 0.06, 0.02,
                        Set.of("plankton"), Set.of("eagle"), Set.of(), false, "minecraft:cod"),
                def(5, "livestock_sheep", "Sheep", SpeciesArchetype.LIVESTOCK,
                        Set.of("plains", "meadow", "settlement"), 80, 0.03, 0.01,
                        Set.of("plants", "grain"), Set.of("wolf"), Set.of(), false, "minecraft:sheep"),
                def(6, "livestock_cattle", "Cattle", SpeciesArchetype.LIVESTOCK,
                        Set.of("plains", "meadow", "settlement"), 50, 0.02, 0.008,
                        Set.of("plants", "grain"), Set.of("wolf"), Set.of(), false, "minecraft:cow"),
                def(7, "boar", "Boar", SpeciesArchetype.HERBIVORE,
                        Set.of("forest", "swamp"), 90, 0.035, 0.018,
                        Set.of("plants", "roots"), Set.of("wolf"), Set.of(), true, "minecraft:pig")
        );
    }

    public static SpeciesDefinition byKey(String key) {
        for (SpeciesDefinition def : catalog()) {
            if (def.key().equals(key)) {
                return def;
            }
        }
        return null;
    }

    private static SpeciesDefinition def(
            long ordinal,
            String key,
            String display,
            SpeciesArchetype archetype,
            Set<String> habitats,
            double capacity,
            double repro,
            double mort,
            Set<String> diet,
            Set<String> predators,
            Set<String> prey,
            boolean migrates,
            String mapping
    ) {
        return new SpeciesDefinition(
                SpeciesId.deterministic(0xEC01L, ordinal),
                key, display, archetype, habitats, capacity, repro, mort,
                diet, predators, prey, migrates, mapping
        );
    }
}
