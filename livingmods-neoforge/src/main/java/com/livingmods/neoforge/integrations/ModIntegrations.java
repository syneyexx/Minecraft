package com.livingmods.neoforge.integrations;

import com.livingmods.neoforge.LivingModsMod;
import net.neoforged.fml.ModList;

/** Optional mod adapters — no hard compile-time dependencies. */
public final class ModIntegrations {
    private ModIntegrations() {}

    public static boolean isLoaded(String modId) {
        return ModList.get().isLoaded(modId);
    }

    public static void logAvailability() {
        LivingModsMod.LOG.info("Optional integrations: create={} waystones={} jei={}",
                isLoaded("create"), isLoaded("waystones"), isLoaded("jei"));
    }
}
