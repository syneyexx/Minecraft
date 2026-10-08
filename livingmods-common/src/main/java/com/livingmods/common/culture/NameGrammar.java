package com.livingmods.common.culture;

import com.livingmods.common.util.DeterministicRandom;

/**
 * Culture-specific name generation for citizens, settlements, kingdoms, and dynasties.
 */
public final class NameGrammar {
    private NameGrammar() {}

    public static String citizenGiven(CultureDefinition culture, boolean female, DeterministicRandom rng) {
        CultureDefinition.NamingStyle n = NamingLibrary.namingFor(culture);
        if (female) {
            return rng.pick(n.femaleNames());
        }
        return rng.pick(n.maleNames());
    }

    public static String citizenFamily(CultureDefinition culture, DeterministicRandom rng) {
        return rng.pick(NamingLibrary.namingFor(culture).familyNames());
    }

    public static String settlement(CultureDefinition culture, DeterministicRandom rng) {
        CultureDefinition.NamingStyle n = NamingLibrary.namingFor(culture);
        return rng.pick(n.settlementPrefixes()) + rng.pick(n.settlementSuffixes());
    }

    public static String kingdom(CultureDefinition culture, DeterministicRandom rng) {
        CultureDefinition.NamingStyle n = NamingLibrary.namingFor(culture);
        if (!n.kingdomPatterns().isEmpty()) {
            String pattern = rng.pick(n.kingdomPatterns());
            return pattern
                    .replace("{prefix}", rng.pick(n.settlementPrefixes()))
                    .replace("{suffix}", rng.pick(n.settlementSuffixes()))
                    .replace("{title}", rng.pick(n.rulerTitles()))
                    .replace("{family}", rng.pick(n.familyNames()));
        }
        return "Realm of " + rng.pick(n.settlementPrefixes()) + rng.pick(n.settlementSuffixes());
    }

    public static String dynasty(CultureDefinition culture, DeterministicRandom rng) {
        CultureDefinition.NamingStyle n = NamingLibrary.namingFor(culture);
        if (!n.dynastyPrefixes().isEmpty()) {
            return rng.pick(n.dynastyPrefixes()) + " " + rng.pick(n.familyNames());
        }
        return "House " + rng.pick(n.familyNames());
    }

    public static String childGiven(CultureDefinition culture, boolean female, DeterministicRandom rng) {
        return citizenGiven(culture, female, rng);
    }
}
