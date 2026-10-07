package com.livingmods.common.model;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Religion affects culture expression, buildings, festivals, diplomacy leanings,
 * government preference, and marriage / political context.
 */
public record ReligionDefinition(
        String key,
        String displayName,
        Set<String> festivalKeys,
        List<String> sacredBuildings,
        Map<String, Double> cultureModifiers,
        DiplomaticRelation preferredForeignStance,
        GovernmentType preferredGovernment,
        double marriageAllianceBias,
        double legitimacyBonus,
        String politicalDoctrine
) {
    public static List<ReligionDefinition> catalog() {
        return List.of(
                new ReligionDefinition("solar_cult", "Solar Cult",
                        Set.of("solstice_fire", "harvest_blessing"),
                        List.of("sun_temple", "obelisk"),
                        Map.of("festival_intensity", 1.2, "military_morale", 1.05),
                        DiplomaticRelation.FRIENDLY, GovernmentType.MONARCHY,
                        0.15, 0.05, "divine_right"),
                new ReligionDefinition("ancestor_ways", "Ancestor Ways",
                        Set.of("remembrance_night", "kin_feast"),
                        List.of("ancestral_hall", "cairn"),
                        Map.of("family_cohesion", 1.3, "migration_resistance", 1.1),
                        DiplomaticRelation.NEUTRAL, GovernmentType.TRIBAL_COUNCIL,
                        0.25, 0.08, "blood_oath"),
                new ReligionDefinition("river_faith", "River Faith",
                        Set.of("flood_rite", "boat_lanterns"),
                        List.of("riverside_shrine", "bathhouse"),
                        Map.of("trade_openness", 1.25, "sanitation", 1.15),
                        DiplomaticRelation.FRIENDLY, GovernmentType.REPUBLIC,
                        0.1, 0.04, "flow_and_exchange"),
                new ReligionDefinition("stone_pact", "Stone Pact",
                        Set.of("mason_day", "wall_watch"),
                        List.of("stone_circle", "fort_chapel"),
                        Map.of("fortification", 1.3, "construction", 1.15),
                        DiplomaticRelation.HOSTILE, GovernmentType.OLIGARCHY,
                        0.05, 0.06, "endurance"),
                new ReligionDefinition("wizard_canon", "Wizard Canon",
                        Set.of("arcane_vigil", "root_communion"),
                        List.of("crystal_sanctum", "root_altar"),
                        Map.of("knowledge", 1.4, "magic_acceptability", 1.5),
                        DiplomaticRelation.NEUTRAL, GovernmentType.THEOCRACY,
                        0.2, 0.12, "hidden_wisdom"),
                new ReligionDefinition("green_circle", "Green Circle",
                        Set.of("planting_moon", "wild_hunt"),
                        List.of("grove", "herb_lodge"),
                        Map.of("agriculture", 1.2, "ecology_harmony", 1.3),
                        DiplomaticRelation.FRIENDLY, GovernmentType.TRIBAL_COUNCIL,
                        0.12, 0.03, "living_balance")
        );
    }

    public static ReligionDefinition byKey(String key) {
        if (key == null || key.isBlank()) {
            return catalog().getFirst();
        }
        for (ReligionDefinition def : catalog()) {
            if (def.key().equals(key) || key.contains(def.key()) || def.key().contains(key)) {
                return def;
            }
        }
        // Fuzzy match on culture religion keys like "solar_cult_law"
        String normalized = key.toLowerCase().replace("_law", "");
        for (ReligionDefinition def : catalog()) {
            if (normalized.contains(def.key()) || def.key().contains(normalized)) {
                return def;
            }
        }
        return catalog().getFirst();
    }
}
