package com.livingmods.neoforge.integrations;

/**
 * Soft availability check for the Create mod.
 * <p>
 * This is <strong>not</strong> a complete Create integration — only {@link #available()}
 * detection. Callers must fall back gracefully when Create is absent or when deeper
 * hooks are not yet implemented.
 */
public final class CreateIntegration {
    private CreateIntegration() {}

    /** {@code true} when the Create mod jar is present on the classpath. */
    public static boolean available() {
        return ModIntegrations.isLoaded("create");
    }
}
