package com.livingmods.neoforge.integrations;

public final class WaystonesIntegration {
    private WaystonesIntegration() {}

    public static boolean available() {
        return ModIntegrations.isLoaded("waystones");
    }
}
