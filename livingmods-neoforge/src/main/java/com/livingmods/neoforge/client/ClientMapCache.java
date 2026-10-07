package com.livingmods.neoforge.client;

import com.livingmods.neoforge.network.MapDataPayload;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Client-only cache for map / dashboard payloads received over NeoForge networking. */
@OnlyIn(Dist.CLIENT)
public final class ClientMapCache {
    private static volatile MapDataPayload mapData;
    private static final Map<String, String> dashboard = new LinkedHashMap<>();

    private ClientMapCache() {}

    public static void accept(MapDataPayload payload) {
        mapData = payload;
    }

    public static MapDataPayload mapData() {
        return mapData;
    }

    public static void acceptDashboard(Map<String, String> metrics) {
        synchronized (dashboard) {
            dashboard.clear();
            if (metrics != null) {
                dashboard.putAll(metrics);
            }
        }
    }

    public static void mergeDashboard(Map<String, String> metrics) {
        if (metrics == null) return;
        synchronized (dashboard) {
            dashboard.putAll(metrics);
        }
    }

    public static Map<String, String> dashboardSnapshot() {
        synchronized (dashboard) {
            return Collections.unmodifiableMap(new LinkedHashMap<>(dashboard));
        }
    }

    /** Clear on disconnect / world switch so world A data never paints world B. */
    public static void clear() {
        mapData = null;
        synchronized (dashboard) {
            dashboard.clear();
        }
    }
}
