package com.livingmods.simulation;

import com.livingmods.common.geo.RegionCoord;
import com.livingmods.common.time.SimulationTime;
import com.livingmods.simulation.engine.ConstructionEngine;
import com.livingmods.simulation.engine.CrimeJusticeEngine;
import com.livingmods.simulation.engine.DemographyEngine;
import com.livingmods.simulation.engine.DiplomacyEngine;
import com.livingmods.simulation.engine.DiseaseEngine;
import com.livingmods.simulation.engine.DialogueEngine;
import com.livingmods.simulation.engine.EcologyEngine;
import com.livingmods.simulation.engine.EconomyEngine;
import com.livingmods.simulation.engine.GovernmentEngine;
import com.livingmods.simulation.engine.HistoryEngine;
import com.livingmods.simulation.engine.MigrationEngine;
import com.livingmods.simulation.engine.MilitaryEngine;
import com.livingmods.simulation.engine.PlayerSystemsEngine;
import com.livingmods.simulation.engine.SimulationSubsystem;
import com.livingmods.simulation.engine.TechnologyEngine;
import com.livingmods.simulation.engine.TradeEngine;
import com.livingmods.simulation.state.SettlementState;
import com.livingmods.simulation.tick.RegionalWork;
import com.livingmods.simulation.tick.SimulationContext;
import com.livingmods.simulation.tick.SimulationScheduler;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Deterministic phased simulation clock with optional parallel regional phase-1/3 work.
 */
public final class SimulationEngine {
    public static final long TICKS_PER_HOUR = SimulationScheduler.TICKS_PER_HOUR;

    private final CanonicalWorldState state;
    private final List<SimulationSubsystem> subsystems;
    private final SimulationScheduler scheduler = new SimulationScheduler();
    private final ExecutorService workers;
    private final int workerCount;
    private long tickCounter;

    public SimulationEngine(CanonicalWorldState state) {
        this(state, Math.max(1, Runtime.getRuntime().availableProcessors()));
    }

    public SimulationEngine(CanonicalWorldState state, int workerCount) {
        this.state = state;
        this.workerCount = Math.max(1, workerCount);
        this.workers = Executors.newFixedThreadPool(this.workerCount);
        this.subsystems = List.of(
                new DemographyEngine(),
                new EconomyEngine(),
                new TradeEngine(),
                new GovernmentEngine(),
                new DiplomacyEngine(),
                new MilitaryEngine(),
                new CrimeJusticeEngine(),
                new DiseaseEngine(),
                new MigrationEngine(),
                new EcologyEngine(),
                new TechnologyEngine(),
                new ConstructionEngine(),
                new DialogueEngine(),
                new HistoryEngine(),
                new PlayerSystemsEngine()
        );
        this.tickCounter = 0L;
    }

    public CanonicalWorldState state() { return state; }
    public SimulationScheduler scheduler() { return scheduler; }
    public int workerCount() { return workerCount; }

    public void tickHour() {
        advanceTicks(TICKS_PER_HOUR);
    }

    public void tickDay() {
        advanceTicks(SimulationTime.TICKS_PER_DAY);
    }

    public void advanceTo(SimulationTime target) {
        long remaining = state.time().ticksUntil(target);
        if (remaining < 0) {
            throw new IllegalArgumentException("target before current time");
        }
        while (remaining > 0) {
            long step = Math.min(remaining, TICKS_PER_HOUR);
            advanceTicks(step);
            remaining -= step;
        }
    }

    public int catchUpBounded(long maxSteps) {
        int steps = 0;
        while (steps < maxSteps) {
            advanceTicks(TICKS_PER_HOUR);
            steps++;
        }
        return steps;
    }

    private void advanceTicks(long ticks) {
        state.setTime(state.time().plusTicks(ticks));
        runPhasedTick();
    }

    private void runPhasedTick() {
        SimulationContext ctx = new SimulationContext(state.seed(), state.time(), tickCounter++);
        Set<RegionCoord> regions = collectRegions();

        List<RegionalWork> phase1 = runPhase1(ctx, regions);
        commitRegionalWork(phase1);

        for (SimulationSubsystem subsystem : subsystems) {
            subsystem.phase2Global(state, ctx, scheduler);
        }

        runPhase3(ctx, regions);
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

        List<Callable<RegionalWork>> tasks = new ArrayList<>();
        for (RegionCoord region : sorted) {
            tasks.add(() -> {
                RegionalWork work = new RegionalWork(region);
                for (SimulationSubsystem subsystem : subsystems) {
                    subsystem.phase1Regional(state, work, ctx, scheduler);
                }
                return work;
            });
        }
        return invokeAll(tasks);
    }

    private void commitRegionalWork(List<RegionalWork> works) {
        works.sort(Comparator.comparing(w -> w.region()));
        for (RegionalWork work : works) {
            work.applyCommits();
        }
    }

    private void runPhase3(SimulationContext ctx, Set<RegionCoord> regions) {
        List<RegionCoord> sorted = new ArrayList<>(regions);
        sorted.sort(RegionCoord::compareTo);

        List<Callable<Void>> tasks = new ArrayList<>();
        for (RegionCoord region : sorted) {
            tasks.add(() -> {
                for (SimulationSubsystem subsystem : subsystems) {
                    subsystem.phase3Independent(state, ctx, scheduler);
                }
                return null;
            });
        }
        invokeAll(tasks);
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
