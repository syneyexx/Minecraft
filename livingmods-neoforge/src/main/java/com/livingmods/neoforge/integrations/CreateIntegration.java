package com.livingmods.neoforge.integrations;

public final class CreateIntegration {
    private CreateIntegration() {}

    public static boolean available() {
        return ModIntegrations.isLoaded("create");
    }
}
