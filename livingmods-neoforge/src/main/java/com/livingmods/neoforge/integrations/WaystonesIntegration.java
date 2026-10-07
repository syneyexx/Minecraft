package com.livingmods.neoforge.integrations;

/**
 * Soft availability check for the Waystones mod.
 * <p>
 * This is <strong>not</strong> a complete Waystones integration — only {@link #available()}
 * detection. Callers must fall back gracefully when Waystones is absent.
 */
public final class WaystonesIntegration {
    private WaystonesIntegration() {}

    /** {@code true} when the Waystones mod jar is present on the classpath. */
    public static boolean available() {
        return ModIntegrations.isLoaded("waystones");
    }
}
