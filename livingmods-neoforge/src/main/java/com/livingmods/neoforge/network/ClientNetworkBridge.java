package com.livingmods.neoforge.network;

import com.livingmods.neoforge.client.ClientMapCache;

import java.util.Map;

/**
 * Thin indirection so {@link LivingModsNetwork} does not hard-reference client UI types
 * in methods that also run registration on dedicated servers. Callers must gate with
 * {@code FMLEnvironment.dist.isClient()} before invoking.
 */
public final class ClientNetworkBridge {
    private ClientNetworkBridge() {}

    public static void acceptMap(MapDataPayload payload) {
        ClientMapCache.accept(payload);
    }

    public static void acceptDashboard(Map<String, String> metrics) {
        ClientMapCache.acceptDashboard(metrics);
    }
}
