package com.livingmods.neoforge.sidecar;

import com.livingmods.common.model.WorldIdentityContract;
import com.livingmods.neoforge.LivingModsMod;
import com.livingmods.neoforge.LivingModsWorldIds;
import com.livingmods.neoforge.entity.CitizenProjectionBinder;
import com.livingmods.neoforge.worldgen.WorldPlanCache;
import com.livingmods.protocol.MessageType;
import com.livingmods.worldgen.plan.WorldPlan;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public final class WorldSessionLifecycle {
    private static final Map<UUID, SidecarClient> CLIENTS = new ConcurrentHashMap<>();
    private static final int BASE_PORT = 27564;
    private static final int MAX_RESTART_ATTEMPTS = 3;
    private static final long SAVE_SHUTDOWN_TIMEOUT_MS = 5_000L;

    private static volatile UUID activeWorldId;
    private static volatile WorldIdentityContract activeIdentity;
    private static volatile Path activeWorldRoot;
    private static final AtomicInteger restartAttempts = new AtomicInteger(0);
    private static final TimeSyncBridge timeSyncBridge = new TimeSyncBridge();
    private static volatile boolean worldActive;

    private WorldSessionLifecycle() {}

    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        MinecraftServer server = event.getServer();
        ServerLevel overworld = server.overworld();
        Path worldRoot = server.getWorldPath(LevelResource.ROOT);
        long seed = overworld.getSeed();
        try {
            UUID worldId = LivingModsWorldIds.loadOrCreate(worldRoot, seed);
            WorldPlan plan = WorldPlanCache.loadOrGenerate(server);
            int planRevision = plan.worldgenVersion();
            WorldIdentityContract identity = WorldIdentityContract.of(
                    worldId, seed, plan.contentHash(), planRevision);

            activeWorldId = worldId;
            activeIdentity = identity;
            activeWorldRoot = worldRoot;
            worldActive = true;
            restartAttempts.set(0);

            if (!SidecarProcessManager.enabled()) {
                LivingModsMod.LOG.info("Sidecar disabled via system property");
                return;
            }

            startSession(server, identity, worldRoot);
        } catch (Exception e) {
            LivingModsMod.LOG.error("Failed to start LivingMods world session", e);
        }
    }

    private static void startSession(MinecraftServer server, WorldIdentityContract identity, Path worldRoot)
            throws Exception {
        UUID worldId = identity.worldId();
        int port = BASE_PORT + (worldId.hashCode() & 0x7FF);
        int workers = SidecarProcessManager.defaultWorkers();
        SidecarProcessManager.start(
                server,
                worldId,
                port,
                worldRoot,
                identity.minecraftSeed(),
                identity.worldPlanHash(),
                identity.worldPlanRevision(),
                workers
        );

        // Brief settle so the sidecar accept loop is up.
        Thread.sleep(250L);

        SidecarClient client = new SidecarClient(worldId, "127.0.0.1", port);
        CLIENTS.put(worldId, client);
        client.handshake(identity, worldRoot.toAbsolutePath().toString(), status ->
                LivingModsMod.LOG.info("Sidecar handshake {}", status)
        ).exceptionally(ex -> {
            LivingModsMod.LOG.error("Sidecar handshake failed", ex);
            return null;
        });
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        UUID worldId = activeWorldId;
        if (worldId == null || !worldActive) {
            return;
        }

        SidecarClient client = CLIENTS.get(worldId);
        if (client != null) {
            timeSyncBridge.onServerTick(server.overworld(), client);
        }

        if (!SidecarProcessManager.enabled()) {
            return;
        }
        if (!SidecarProcessManager.isAlive(worldId) && activeIdentity != null) {
            attemptBoundedRestart(server, worldId);
        }

        // Interest-based citizen projection (never 1:1 with population).
        if (client != null && client.isReady()) {
            CitizenProjectionBinder.get().onServerTick(server);
        }
    }

    private static void attemptBoundedRestart(MinecraftServer server, UUID worldId) {
        int attempt = restartAttempts.incrementAndGet();
        if (attempt > MAX_RESTART_ATTEMPTS) {
            LivingModsMod.LOG.error("Sidecar died; exhausted {} restart attempts for world {}",
                    MAX_RESTART_ATTEMPTS, worldId);
            SidecarClient client = CLIENTS.get(worldId);
            if (client != null) {
                client.setState(ConnectionState.FAILED);
            }
            return;
        }
        LivingModsMod.LOG.warn("Sidecar process died; restart attempt {}/{} for world {}",
                attempt, MAX_RESTART_ATTEMPTS, worldId);
        try {
            SidecarClient old = CLIENTS.remove(worldId);
            if (old != null) {
                old.markReconnecting();
                old.close();
            }
            SidecarProcessManager.restart(worldId);
            Thread.sleep(250L);
            int port = SidecarProcessManager.port(worldId);
            SidecarClient client = new SidecarClient(worldId, "127.0.0.1", port);
            CLIENTS.put(worldId, client);
            client.markReconnecting();
            WorldIdentityContract identity = activeIdentity;
            Path worldRoot = activeWorldRoot;
            if (identity != null) {
                client.handshake(
                        identity,
                        worldRoot == null ? null : worldRoot.toAbsolutePath().toString(),
                        status -> LivingModsMod.LOG.info("Sidecar re-handshake {}", status)
                );
            }
        } catch (Exception e) {
            LivingModsMod.LOG.error("Sidecar restart failed", e);
        }
    }

    @SubscribeEvent
    public static void onLevelSave(LevelEvent.Save event) {
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        if (level != level.getServer().overworld()) {
            return;
        }
        UUID worldId = activeWorldId;
        SidecarClient client = worldId == null ? null : CLIENTS.get(worldId);
        if (client == null || !client.isReady()) {
            return;
        }
        client.sendAsync(MessageType.SAVE_REQUEST, new byte[0]).exceptionally(ex -> {
            LivingModsMod.LOG.debug("SAVE_REQUEST failed: {}", ex.toString());
            return null;
        });
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        worldActive = false;
        UUID worldId = activeWorldId;
        SidecarClient client = worldId == null ? null : CLIENTS.get(worldId);
        if (client != null) {
            try {
                client.sendAsync(MessageType.SAVE_REQUEST, new byte[0])
                        .orTimeout(SAVE_SHUTDOWN_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                        .exceptionally(ex -> null)
                        .thenCompose(ignored -> client.sendAsync(MessageType.SHUTDOWN, new byte[0])
                                .orTimeout(SAVE_SHUTDOWN_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                                .exceptionally(ex -> null))
                        .get(SAVE_SHUTDOWN_TIMEOUT_MS * 2, TimeUnit.MILLISECONDS);
            } catch (Exception e) {
                LivingModsMod.LOG.warn("Graceful sidecar shutdown incomplete: {}", e.toString());
            }
            client.close();
        }
        CLIENTS.clear();
        SidecarProcessManager.stopAll();
        WorldPlanCache.clear();
        CitizenProjectionBinder.get().clear();
        timeSyncBridge.reset();
        activeWorldId = null;
        activeIdentity = null;
        activeWorldRoot = null;
        restartAttempts.set(0);
        // Ensure no static process/socket/plan/canonical/cache from world A leaks into world B.
        LivingModsMod.LOG.info("LivingMods world session cleared on server stop");
    }

    public static SidecarClient clientFor(UUID worldId) {
        return CLIENTS.get(worldId);
    }

    public static UUID activeWorldId() {
        return activeWorldId;
    }

    public static WorldIdentityContract activeIdentity() {
        return activeIdentity;
    }

    public static SidecarClient activeClient() {
        UUID id = activeWorldId;
        return id == null ? null : CLIENTS.get(id);
    }
}
