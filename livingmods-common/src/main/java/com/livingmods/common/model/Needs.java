package com.livingmods.common.model;

public record Needs(
        double hunger,
        double safety,
        double social,
        double comfort,
        double status,
        double economicSecurity
) {
    public static Needs satisfied() {
        return new Needs(0.1, 0.8, 0.6, 0.6, 0.4, 0.6);
    }

    public Needs withHunger(double h) {
        return new Needs(clamp(h), safety, social, comfort, status, economicSecurity);
    }

    public boolean isDesperate() {
        return hunger > 0.8 || safety < 0.2 || economicSecurity < 0.15;
    }

    private static double clamp(double v) {
        return Math.max(0.0, Math.min(1.0, v));
    }
}
