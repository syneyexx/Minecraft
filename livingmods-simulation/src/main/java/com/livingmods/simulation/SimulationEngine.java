package com.livingmods.simulation;

import com.livingmods.common.config.LivingModsConfig;
import com.livingmods.common.geo.RegionCoord;
import com.livingmods.common.time.SimulationTime;
import com.livingmods.simulation.engine.BanditryEngine;
import com.livingmods.simulation.engine.ConstructionEngine;
import com.livingmods.simulation.engine.CrimeJusticeEngine;
import com.livingmods.simulation.engine.DemographyEngine;
import com.livingmods.simulation.engine.DiplomacyEngine;
import com.livingmods.simulation.engine.DiseaseEngine;
import com.livingmods.simulation.engine.DialogueEngine;
import com.livingmods.simulation.engine.EcologyEngine;
import com.livingmods.simulation.engine.EconomyEngine;
import com.livingmods.simulation.engine.EmergentTaskEngine;
import com.livingmods.simulation.engine.GovernmentEngine;
import com.livingmods.simulation.engine.HistoryEngine;
import com.livingmods.simulation.engine.MigrationEngine;
import com.livingmods.simulation.engine.MilitaryEngine;
import com.livingmods.simulation.engine.PlayerSystemsEngine;
import com.livingmods.simulation.engine.ReligionEngine;
import com.livingmods.simulation.engine.ScheduleEngine;
import com.livingmods.simulation.engine.SimulationSubsystem;
import com.livingmods.simulation.engine.TechnologyEngine;
import com.livingmods.simulation.engine.TradeEngine;
import com.livingmods.simulation.state.SettlementState;
import com.livingmods.simulation.tick.RegionalWork;
import com.livingmods.simulation.tick.SimulationContext;
import com.livingmods.simulation.tick.SimulationScheduler;
import com.livingmods.simulation.tick.SimulationScheduler.SimulationStepSchedule;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Deterministic phased simulation clock with optional parallel regional phase-1 work.
 * Phase 3 runs once globally. Saves can pause the loop at a phase boundary.
 */
public final class SimulationEngine {
    public static final long TICKS_PER_HOUR = SimulationScheduler.TICKS_PER_HOUR;
    public static final long TICKS_PER_WEEK = SimulationTime.TICKS_PER_DAY * 7L;
    public static final long TICKS_PER_MONTH = SimulationTime.TICKS_PER_DAY * 30L;
    public static final long CATCHUP_HOURLY_THRESHOLD = TICKS_PER_HOUR * 24L;
    public static final long CATCHUP_DAILY_THRESHOLD = SimulationTime.TICKS_PER_DAY * 30L;
    public static final int DEFAULT_MAX_CATCHUP_STEPS = 4096;

    private final CanonicalWorldState state;
    private final List<SimulationSubsystem> subsystems;
    private final SimulationScheduler scheduler = new SimulationScheduler();
    private final ExecutorService workers;
    private final int workerCount;
    private final int maximumRegionalJobs;
    private final AtomicBoolean pausedForSave = new AtomicBoolean(false);
    private final Object savePauseLock = new Object();
    private final AtomicBoolean midTick = new AtomicBoolean(false);
    private long tickCounter;

    public SimulationEngine(CanonicalWorldState state) {
        this(state, LivingModsConfig.defaultWorkerThreads(),
                LivingModsConfig.defaults().maximumRegionalJobs());
    }

    public SimulationEngine(CanonicalWorldState state, int workerCount) {
        this(state, workerCount, LivingModsConfig.defaults().maximumRegionalJobs());
    }

    public SimulationEngine(CanonicalWorldState state, int workerCount, int maximumRegionalJobs) {
        this.state = state;
        this.workerCount = Math.max(1, workerCount);
        this.maximumRegionalJobs = Math.max(1, maximumRegionalJobs);
        this.workers = Executors.newFixedThreadPool(this.workerCount);
        this.subsystems = List.of(
                new DemographyEngine(),
                new ScheduleEngine(),
                new EconomyEngine(),
                new TradeEngine(),
                new GovernmentEngine(),
                new ReligionEngine(),
                new DiplomacyEngine(),
                new MilitaryEngine(),
                new CrimeJusticeEngine(),
                new DiseaseEngine(),
                new MigrationEngine(),
                new EcologyEngine(),
                new TechnologyEngine(),
                new ConstructionEngine(),
                new BanditryEngine(),
                new DialogueEngine(),
                new HistoryEngine(),
                new EmergentTaskEngine(),
                new PlayerSystemsEngine()
        );
        this.tickCounter = 0L;
    }

    public CanonicalWorldState state() { return state; }
    public SimulationScheduler scheduler() { return scheduler; }
    public int workerCount() { return workerCount; }
    public int maximumRegionalJobs() { return maximumRegionalJobs; }
    public boolean isPausedForSave() { return pausedForSave.get(); }

    /**
     * Request that the simulation stop at the next phase boundary and wait for save.
     * Blocks until any in-flight tick finishes, then holds the pause flag.
     */
    public void pauseForSave() {
        pausedForSave.set(true);
        synchronized (savePauseLock) {
            while (midTick.get()) {
                try {
                    savePauseLock.wait(50L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
    }

    public void resumeAfterSave() {
        pausedForSave.set(false);
        synchronized (savePauseLock) {
            savePauseLock.notifyAll();
        }
    }

    public void tickHour() {
        advanceTicks(TICKS_PER_HOUR);
    }

    public void tickDay() {
        advanceTicks(SimulationTime.TICKS_PER_DAY);
    }

    public void advanceTo(SimulationTime target) {
        advanceToward(target, DEFAULT_MAX_CATCHUP_STEPS);
    }

    /**
     * Hierarchical catch-up toward {@code target}, bounded by {@code budget} simulation steps.
     * <ul>
     *   <li>gap &lt; 24 hours → hourly steps</li>
     *   <li>gap &lt; 30 days → daily steps</li>
     *   <li>larger → weekly then monthly aggregate steps</li>
     * </ul>
     *
     * @return number of steps executed
     */
    public int advanceToward(SimulationTime target, long budget) {
        long remaining = state.time().ticksUntil(target);
        if (remaining < 0) {
            throw new IllegalArgumentException("target before current time");
        }
        long maxSteps = Math.max(0L, budget);
        int steps = 0;
        while (remaining > 0 && steps < maxSteps) {
            awaitNotPaused();
            long step = chooseCatchUpStep(remaining);
            advanceTicks(step);
            remaining -= step;
            steps++;
        }
        return steps;
    }

    public int catchUpBounded(long maxSteps) {
        return advanceToward(state.time().plusTicks(maxSteps * TICKS_PER_HOUR), maxSteps);
    }

    private static long chooseCatchUpStep(long remaining) {
        if (remaining < CATCHUP_HOURLY_THRESHOLD) {
            return Math.min(remaining, TICKS_PER_HOUR);
        }
        if (remaining < CATCHUP_DAILY_THRESHOLD) {
            return Math.min(remaining, SimulationTime.TICKS_PER_DAY);
        }
        if (remaining < TICKS_PER_MONTH * 6L) {
            return Math.min(remaining, TICKS_PER_WEEK);
        }
        return Math.min(remaining, TICKS_PER_MONTH);
    }

    private void advanceTicks(long ticks) {
        awaitNotPaused();
        midTick.set(true);
        try {
            state.setTime(state.time().plusTicks(ticks));
            runPhasedTick();
        } finally {
            midTick.set(false);
            synchronized (savePauseLock) {
                savePauseLock.notifyAll();
            }
        }
    }

    private void awaitNotPaused() {
        if (!pausedForSave.get()) {
            return;
        }
        synchronized (savePauseLock) {
            while (pausedForSave.get()) {
                try {
                    savePauseLock.wait(100L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    private void runPhasedTick() {
        SimulationStepSchedule schedule = scheduler.planStep(state.time());
        SimulationContext ctx = new SimulationContext(state.seed(), state.time(), tickCounter++, schedule);
        Set<RegionCoord> regions = collectRegions();

        // Immutable / read phase → independent regional calc (batched) → ordered commit by region
        List<RegionalWork> phase1 = runPhase1(ctx, regions);
        commitRegionalWork(phase1);

        // Subsystem priority order (list order)
        for (SimulationSubsystem subsystem : subsystems) {
            subsystem.phase2Global(state, ctx);
        }

        // Phase 3 once globally — not once per region in parallel
        runPhase3(ctx);
    }

    private Set<RegionCoord> collectRegions() {
        Set<RegionCoord> regions = new HashSet<>();
        for (SettlementState s : state.settlements().values()) {
            regions.add(s.region());
        }
        if (regions.isEmpty()) {
            regions.add(RegionCoord.of(0, 0));
        }
        return regions;
    }

    private List<RegionalWork> runPhase1(SimulationContext ctx, Set<RegionCoord> regions) {
        List<RegionCoord> sorted = new ArrayList<>(regions);
        sorted.sort(RegionCoord::compareTo);

        List<RegionalWork> all = new ArrayList<>(sorted.size());
        for (int i = 0; i < sorted.size(); i += maximumRegionalJobs) {
            int end = Math.min(i + maximumRegionalJobs, sorted.size());
            List<RegionCoord> batch = sorted.subList(i, end);
            List<Callable<RegionalWork>> tasks = new ArrayList<>(batch.size());
            for (RegionCoord region : batch) {
                tasks.add(() -> {
                    RegionalWork work = new RegionalWork(region);
                    for (SimulationSubsystem subsystem : subsystems) {
                        subsystem.phase1Regional(state, work, ctx);
                    }
                    return work;
                });
            }
            all.addAll(invokeAll(tasks));
        }
        return all;
    }

    private void commitRegionalWork(List<RegionalWork> works) {
        works.sort(Comparator.comparing(w -> w.region()));
        for (RegionalWork work : works) {
            work.applyCommits();
        }
    }

    private void runPhase3(SimulationContext ctx) {
        for (SimulationSubsystem subsystem : subsystems) {
            subsystem.phase3Independent(state, ctx);
        }
    }

    private <T> List<T> invokeAll(List<Callable<T>> tasks) {
        try {
            List<Future<T>> futures = workers.invokeAll(tasks);
            List<T> results = new ArrayList<>(futures.size());
            for (Future<T> f : futures) {
                results.add(f.get());
            }
            return results;
        } catch (Exception e) {
            throw new IllegalStateException("simulation worker failure", e);
        }
    }

    public void shutdown() {
        workers.shutdown();
    }
}
