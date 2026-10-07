package com.livingmods.common.culture;

import com.livingmods.common.id.CultureId;
import com.livingmods.common.model.GovernmentType;

import java.util.List;
import java.util.Map;

/**
 * Culture is not a decorative string — it drives architecture, government,
 * naming, military style, economy specialization, and more.
 */
public record CultureDefinition(
        CultureId id,
        String key,
        String displayName,
        GovernmentType preferredGovernment,
        ArchitectureStyle architecture,
        NamingStyle naming,
        MilitaryStyle military,
        List<String> preferredBiomes,
        List<String> preferredResources,
        Map<String, Double> professionWeights,
        String religionKey,
        String lawKey,
        boolean underground
) {
    public record ArchitectureStyle(
            String primaryBlock,
            String secondaryBlock,
            String accentBlock,
            String roofBlock,
            String roadBlock,
            String wallBlock,
            RoofStyle roofStyle,
            LayoutStyle layoutStyle,
            double windowDensity,
            boolean preferStraightStreets
    ) {}

    public enum RoofStyle { GABLE, HIP, FLAT, DOMED, THATCH, MAGICAL }
    public enum LayoutStyle { GRID, ORGANIC_RADIAL, TERRACED, CAVERN }

    public record NamingStyle(
            List<String> settlementPrefixes,
            List<String> settlementSuffixes,
            List<String> maleNames,
            List<String> femaleNames,
            List<String> familyNames,
            List<String> rulerTitles,
            List<String> dynastyPrefixes,
            List<String> kingdomPatterns
    ) {
        public NamingStyle {
            settlementPrefixes = List.copyOf(settlementPrefixes);
            settlementSuffixes = List.copyOf(settlementSuffixes);
            maleNames = List.copyOf(maleNames);
            femaleNames = List.copyOf(femaleNames);
            familyNames = List.copyOf(familyNames);
            rulerTitles = List.copyOf(rulerTitles);
            dynastyPrefixes = dynastyPrefixes == null ? List.of() : List.copyOf(dynastyPrefixes);
            kingdomPatterns = kingdomPatterns == null ? List.of() : List.copyOf(kingdomPatterns);
        }

        /** Back-compat constructor without dynasty/kingdom grammars. */
        public NamingStyle(
                List<String> settlementPrefixes,
                List<String> settlementSuffixes,
                List<String> maleNames,
                List<String> femaleNames,
                List<String> familyNames,
                List<String> rulerTitles
        ) {
            this(settlementPrefixes, settlementSuffixes, maleNames, femaleNames, familyNames, rulerTitles,
                    List.of("House", "Clan", "Line of"),
                    List.of("Kingdom of {prefix}{suffix}", "{title}'s {prefix} Realm", "Dominion of {family}"));
        }
    }

    public record MilitaryStyle(
            String unitDoctrine,
            double cavalryPreference,
            double fortificationPreference,
            String primaryWeaponTheme
    ) {}
}
