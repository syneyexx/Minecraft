package com.livingmods.neoforge.sidecar;

import com.livingmods.neoforge.LivingModsMod;
import com.livingmods.neoforge.LivingModsWorldIds;
import com.livingmods.neoforge.worldgen.WorldPlanCache;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class WorldSessionLifecycle {
    private static final Map<UUID, SidecarClient> CLIENTS = new ConcurrentHashMap<>();
    private static final int BASE_PORT = 27564;

    private WorldSessionLifecycle() {}

    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        MinecraftServer server = event.getServer();
        ServerLevel overworld = server.overworld();
        UUID worldId = LivingModsWorldIds.fromSeed(overworld.getSeed());
        try {
            WorldPlanCache.loadOrGenerate(server);
        } catch (Exception e) {
            LivingModsMod.LOG.warn("World plan load failed: {}", e.toString());
        }
        if (!SidecarProcessManager.enabled()) {
            LivingModsMod.LOG.info("Sidecar disabled via system property");
            return;
        }
        int port = BASE_PORT + (worldId.hashCode() & 0x7FF);
        try {
            SidecarProcessManager.start(server, worldId, port);
            SidecarClient client = new SidecarClient(worldId, "127.0.0.1", port);
            client.handshake(status -> LivingModsMod.LOG.info("Sidecar handshake {}", status));
            CLIENTS.put(worldId, client);
        } catch (Exception e) {
            LivingModsMod.LOG.error("Failed to start sidecar", e);
        }
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        for (SidecarClient client : CLIENTS.values()) {
            client.close();
        }
        CLIENTS.clear();
        SidecarProcessManager.stopAll();
    }

    public static SidecarClient clientFor(UUID worldId) {
        return CLIENTS.get(worldId);
    }
}
