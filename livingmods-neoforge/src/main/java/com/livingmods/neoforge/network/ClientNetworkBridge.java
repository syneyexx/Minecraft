package com.livingmods.neoforge.network;

import com.livingmods.neoforge.client.ClientGameplayScreens;
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

    public static void acceptDashboardContext(Map<String, String> data) {
        ClientMapCache.acceptPlayerContext(data);
    }

    public static void clearCaches() {
        ClientMapCache.clear();
    }

    public static void openCitizen(CitizenInteractionPayloads.OpenScreen payload) {
        ClientGameplayScreens.openCitizen(payload);
    }

    public static void updateDialogue(CitizenInteractionPayloads.DialogueUpdate update) {
        ClientGameplayScreens.updateDialogue(update);
    }

    public static void openMarket(CitizenInteractionPayloads.MarketScreenData data) {
        ClientGameplayScreens.openMarket(data);
    }

    public static void openRealm(CitizenInteractionPayloads.RealmPanel panel) {
        ClientGameplayScreens.openRealm(panel);
    }

    public static void mergeTasks(CitizenInteractionPayloads.TaskJournalUpdate update) {
        ClientGameplayScreens.mergeTasks(update);
    }
}
