package com.livingmods.common.model;

import com.livingmods.common.util.DeterministicRandom;

/** Trait values in [0,1]. Influence actual decisions. */
public record Personality(
        double aggression,
        double caution,
        double greed,
        double loyalty,
        double ambition,
        double sociability,
        double tradeAffinity,
        double treachery
) {
    public static Personality random(DeterministicRandom random) {
        return new Personality(
                clamp(random.nextDouble()),
                clamp(random.nextDouble()),
                clamp(random.nextDouble()),
                clamp(random.nextDouble()),
                clamp(random.nextDouble()),
                clamp(random.nextDouble()),
                clamp(random.nextDouble()),
                clamp(random.nextDouble() * 0.4)
        );
    }

    private static double clamp(double v) {
        return Math.max(0.0, Math.min(1.0, v));
    }
}
