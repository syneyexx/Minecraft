package com.livingmods.sidecar;

import com.livingmods.common.config.LivingModsConfig;
import com.livingmods.common.event.HistoricalEvent;
import com.livingmods.common.time.SimulationTime;
import com.livingmods.protocol.EventPayload;
import com.livingmods.simulation.CanonicalWorldState;
import com.livingmods.simulation.InitialStateFactory;
import com.livingmods.simulation.SimulationEngine;
import com.livingmods.simulation.tick.SimulationScheduler;
import com.livingmods.worldgen.plan.WorldPlan;
import com.livingmods.worldgen.persist.WorldPlanStore;

import java.io.IOException;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

public final class SidecarSimulationHost implements AutoCloseable {
    private static final Logger LOG = SidecarLogging.logger(SidecarSimulationHost.class);

    private static final long SMALL_GAP_TICKS = SimulationTime.TICKS_PER_DAY;
    private static final long MEDIUM_GAP_TICKS = SimulationTime.TICKS_PER_DAY * 14L;
    private static final long WEEK_HOUR_STEPS = 24L * 7L;
    private static final long MONTH_HOUR_STEPS = 24L * 30L;

    private final UUID worldId;
    private final Path saveDir;
    private final Path worldRoot;
    private final long seed;
    private final long expectedPlanHash;
    private final int planRevision;
    private final LivingModsConfig config;
    private final WorldPlan worldPlan;
    private final CanonicalWorldState state;
    private final SimulationEngine engine;
    private final BlockingQueue<EventPayload> outboundEvents = new LinkedBlockingQueue<>();
    private final PersistenceCoordinator persistence;
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final AtomicBoolean frozen = new AtomicBoolean(false);
    private final AtomicBoolean failed = new AtomicBoolean(false);
    private final AtomicLong targetMinecraftTime = new AtomicLong(0L);
    private final Object timeLock = new Object();
    private final Thread simulationThread;
    private final java.util.concurrent.ConcurrentHashMap<Long, Integer> regionSubscriptions =
            new java.util.concurrent.ConcurrentHashMap<>();

    public SidecarSimulationHost(
            UUID worldId,
            Path saveDir,
            Path worldRoot,
            long seed,
            long expectedPlanHash,
            int planRevision,
            int workers
    ) throws IOException {
        this.worldId = worldId;
        this.saveDir = saveDir;
        this.worldRoot = worldRoot;
        this.seed = seed;
        this.expectedPlanHash = expectedPlanHash;
        this.planRevision = planRevision;
        this.config = LivingModsConfig.defaults().withWorkers(workers);
        this.persistence = new PersistenceCoordinator(saveDir);

        // Never infer seed from UUID bits — use the exact seed from Minecraft.
        this.worldPlan = WorldPlanStore.loadOrGenerate(worldRoot, config, seed);
        if (worldPlan.contentHash() != expectedPlanHash) {
            failed.set(true);
            frozen.set(true);
            String msg = "World plan contentHash mismatch: expected=" + expectedPlanHash
                    + " actual=" + worldPlan.contentHash()
                    + " seed=" + seed
                    + " worldRoot=" + worldRoot
                    + " planRevision=" + planRevision;
            LOG.severe(msg);
            throw new IOException(msg);
        }

        CanonicalWorldState loaded = persistence.loadOrNull();
        if (loaded != null && loaded.planContentHash() == worldPlan.contentHash()) {
            this.state = loaded;
            InitialStateFactory.attachWorldPlan(state, worldPlan);
            LOG.info("Loaded canonical state revision=" + state.saveRevision());
        } else {
            this.state = InitialStateFactory.fromWorldPlan(worldPlan);
            LOG.info("Bootstrapped canonical state from world plan");
            if (persistence.beginSaveBarrier()) {
                persistence.completeSaveBarrier(state);
            }
        }

        this.state.setWorldId(worldId);
        this.engine = new SimulationEngine(state, workers, config.maximumRegionalJobs());
        this.persistence.bindEngine(engine);
        this.simulationThread = new Thread(this::simulationLoop, "livingmods-sidecar-sim");
        this.simulationThread.setDaemon(true);
        this.simulationThread.start();
    }

    private void simulationLoop() {
        while (running.get()) {
            try {
                if (frozen.get() || failed.get()) {
                    Thread.sleep(Math.max(25L, config.simulationBudgetMillis()));
                    continue;
                }
                long target = targetMinecraftTime.get();
                long current = state.time().absoluteTicks();
                if (target <= current) {
                    synchronized (timeLock) {
                        if (targetMinecraftTime.get() <= state.time().absoluteTicks()) {
                            timeLock.wait(Math.max(5L, config.simulationBudgetMillis()));
                        }
                    }
                    continue;
                }
                catchUpToward(target, config.simulationBudgetMillis());
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

    /**
     * Hierarchical catch-up toward Minecraft game time within a wall-clock budget:
     * small gaps advance hourly, medium daily, large via bounded weekly/monthly hour steps.
     */
    private void catchUpToward(long targetTicks, long budgetMillis) {
        long deadline = System.currentTimeMillis() + Math.max(1L, budgetMillis);
        while (running.get() && !frozen.get() && !failed.get()
                && System.currentTimeMillis() < deadline) {
            long gap = targetTicks - state.time().absoluteTicks();
            if (gap <= 0) {
                return;
            }
            long started = System.nanoTime();
            if (gap <= SMALL_GAP_TICKS) {
                engine.tickHour();
            } else if (gap <= MEDIUM_GAP_TICKS) {
                engine.tickDay();
            } else if (gap <= SimulationTime.TICKS_PER_DAY * 60L) {
                long steps = Math.min(WEEK_HOUR_STEPS, gap / SimulationScheduler.TICKS_PER_HOUR);
                engine.catchUpBounded(Math.max(1L, steps));
            } else {
                long steps = Math.min(MONTH_HOUR_STEPS, gap / SimulationScheduler.TICKS_PER_HOUR);
                engine.catchUpBounded(Math.max(1L, steps));
            }
            // Host keeps a DiagnosticsExporter via SidecarMain; step timing recorded when available.
            lastStepNanos = System.nanoTime() - started;
        }
    }

    private volatile long lastStepNanos;

    public long lastStepNanos() {
        return lastStepNanos;
    }

    public int subscriptionCount() {
        return regionSubscriptions.size();
    }

    public int outboundEventQueueDepth() {
        return outboundEvents.size();
    }

    public UUID worldId() {
        return worldId;
    }

    public Path worldRoot() {
        return worldRoot;
    }

    public long seed() {
        return seed;
    }

    public long expectedPlanHash() {
        return expectedPlanHash;
    }

    public int planRevision() {
        return planRevision;
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

    public boolean isFrozen() {
        return frozen.get();
    }

    public boolean isFailed() {
        return failed.get();
    }

    public void setFrozen(boolean value) {
        frozen.set(value);
        if (!value) {
            synchronized (timeLock) {
                timeLock.notifyAll();
            }
        }
    }

    public void markFailed(String reason) {
        failed.set(true);
        frozen.set(true);
        LOG.severe("Sidecar host failed: " + reason);
        pushEvent(new EventPayload(
                com.livingmods.common.event.CivilizationEventType.SIDECAR_DEGRADED,
                0, 0, 0, 0,
                java.util.Map.of("error", reason == null ? "failed" : reason)
        ));
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
        if (frozen.get() || failed.get()) {
            return;
        }
        if (jumped) {
            state.setTime(SimulationTime.ofTicks(Math.max(0L, minecraftGameTime)));
        }
        targetMinecraftTime.set(Math.max(0L, minecraftGameTime));
        synchronized (timeLock) {
            timeLock.notifyAll();
        }
    }

    /** detailLevel &lt; 0 unsubscribes. */
    public void setRegionSubscription(int regionX, int regionZ, int detailLevel) {
        long key = (((long) regionX) << 32) ^ (regionZ & 0xffffffffL);
        if (detailLevel < 0) {
            regionSubscriptions.remove(key);
        } else {
            regionSubscriptions.put(key, detailLevel);
        }
    }

    public boolean isRegionSubscribed(int regionX, int regionZ) {
        long key = (((long) regionX) << 32) ^ (regionZ & 0xffffffffL);
        return regionSubscriptions.containsKey(key);
    }

    public boolean finalSaveBarrier() {
        if (!persistence.beginSaveBarrier()) {
            return false;
        }
        try {
            persistence.completeSaveBarrier(state);
            return true;
        } catch (IOException e) {
            LOG.warning("Final save barrier failed: " + e.getMessage());
            return false;
        }
    }

    @Override
    public void close() {
        running.set(false);
        synchronized (timeLock) {
            timeLock.notifyAll();
        }
        simulationThread.interrupt();
        try {
            simulationThread.join(2_000L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        finalSaveBarrier();
        engine.shutdown();
    }
}
