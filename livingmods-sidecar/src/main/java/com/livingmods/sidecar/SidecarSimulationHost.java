package com.livingmods.sidecar;

import com.livingmods.common.config.LivingModsConfig;
import com.livingmods.common.event.HistoricalEvent;
import com.livingmods.common.time.SimulationTime;
import com.livingmods.protocol.EventPayload;
import com.livingmods.simulation.CanonicalWorldState;
import com.livingmods.simulation.InitialStateFactory;
import com.livingmods.simulation.SimulationEngine;
import com.livingmods.worldgen.WorldPlanner;
import com.livingmods.worldgen.plan.WorldPlan;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

public final class SidecarSimulationHost implements AutoCloseable {
    private static final Logger LOG = SidecarLogging.logger(SidecarSimulationHost.class);

    private final UUID worldId;
    private final Path saveDir;
    private final LivingModsConfig config;
    private final WorldPlan worldPlan;
    private final CanonicalWorldState state;
    private final SimulationEngine engine;
    private final BlockingQueue<EventPayload> outboundEvents = new LinkedBlockingQueue<>();
    private final PersistenceCoordinator persistence;
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final Thread simulationThread;

    public SidecarSimulationHost(UUID worldId, Path saveDir, int workers) throws IOException {
        this.worldId = worldId;
        this.saveDir = saveDir;
        this.config = LivingModsConfig.defaults().withWorkers(workers);
        this.persistence = new PersistenceCoordinator(saveDir);

        CanonicalWorldState loaded = persistence.loadOrNull();
        long seed = readSeed(saveDir, worldId);
        this.worldPlan = new WorldPlanner(config).plan(seed);
        if (loaded != null && loaded.planContentHash() == worldPlan.contentHash()) {
            this.state = loaded;
            LOG.info("Loaded canonical state revision=" + state.saveRevision());
        } else {
            this.state = InitialStateFactory.fromWorldPlan(worldPlan);
            LOG.info("Bootstrapped canonical state from world plan");
        }

        this.engine = new SimulationEngine(state, workers);
        this.simulationThread = new Thread(this::simulationLoop, "livingmods-sidecar-sim");
        this.simulationThread.setDaemon(true);
        this.simulationThread.start();
    }

    private void simulationLoop() {
        while (running.get()) {
            try {
                engine.tickHour();
                Thread.sleep(Math.max(5L, config.simulationBudgetMillis()));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                LOG.warning("Simulation step failed: " + e.getMessage());
                pushEvent(new EventPayload(
                        com.livingmods.common.event.CivilizationEventType.SIDECAR_DEGRADED,
                        0, 0, 0, 0,
                        java.util.Map.of("error", String.valueOf(e.getMessage()))
                ));
            }
        }
    }

    public UUID worldId() {
        return worldId;
    }

    public WorldPlan worldPlan() {
        return worldPlan;
    }

    public CanonicalWorldState state() {
        return state;
    }

    public SimulationEngine engine() {
        return engine;
    }

    public PersistenceCoordinator persistence() {
        return persistence;
    }

    public EventPayload pollEvent() {
        return outboundEvents.poll();
    }

    public void onHistoricalEvent(HistoricalEvent event) {
        outboundEvents.offer(new EventPayload(
                event.type(),
                event.location().map(p -> p.x() >> 9).orElse(0),
                event.location().map(p -> p.z() >> 9).orElse(0),
                event.location().map(com.livingmods.common.geo.BlockPos2::x).orElse(0),
                event.location().map(com.livingmods.common.geo.BlockPos2::z).orElse(0),
                event.tags()
        ));
    }

    public void pushEvent(EventPayload payload) {
        outboundEvents.offer(payload);
    }

    public void syncTime(long minecraftGameTime, boolean jumped) {
        if (jumped) {
            state.setTime(SimulationTime.ofTicks(minecraftGameTime));
        } else if (minecraftGameTime > state.time().absoluteTicks()) {
            engine.advanceTo(SimulationTime.ofTicks(minecraftGameTime));
        }
    }

    private static long readSeed(Path saveDir, UUID worldId) throws IOException {
        Path seedFile = saveDir.resolve("world.seed");
        if (Files.isRegularFile(seedFile)) {
            try (var in = new java.io.DataInputStream(Files.newInputStream(seedFile))) {
                return in.readLong();
            }
        }
        return worldId.getMostSignificantBits() ^ worldId.getLeastSignificantBits();
    }

    @Override
    public void close() {
        running.set(false);
        simulationThread.interrupt();
        engine.shutdown();
    }
}
