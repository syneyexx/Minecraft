package com.livingmods.common.model;

import java.util.List;

/** Pathogen parameters for DiseaseEngine. */
public record DiseaseDefinition(
        String key,
        String displayName,
        double infectivity,
        double severity,
        double mortality,
        double durationDays,
        double immunityDays,
        double densitySensitivity,
        double sanitationSensitivity,
        double tradeSensitivity,
        double healthcareMitigation
) {
    public static List<DiseaseDefinition> catalog() {
        return List.of(
                new DiseaseDefinition("fever", "Seasonal Fever",
                        0.18, 0.25, 0.015, 8, 90, 0.4, 0.3, 0.2, 0.5),
                new DiseaseDefinition("flux", "Bloody Flux",
                        0.12, 0.45, 0.04, 12, 180, 0.5, 0.6, 0.15, 0.55),
                new DiseaseDefinition("pox", "Pox",
                        0.22, 0.55, 0.06, 14, 365, 0.35, 0.25, 0.35, 0.4),
                new DiseaseDefinition("cough", "Lung Cough",
                        0.28, 0.2, 0.01, 6, 60, 0.55, 0.2, 0.25, 0.45)
        );
    }

    public static DiseaseDefinition byKey(String key) {
        for (DiseaseDefinition def : catalog()) {
            if (def.key().equals(key)) {
                return def;
            }
        }
        return catalog().getFirst();
    }
}
