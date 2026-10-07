package com.livingmods.simulation.engine;

import com.livingmods.simulation.CanonicalWorldState;
import com.livingmods.simulation.tick.RegionalWork;
import com.livingmods.simulation.tick.SimulationContext;
import com.livingmods.simulation.tick.SimulationScheduler;

public interface SimulationSubsystem {
    String name();

    /** Phase 1: parallel read-only regional calculations; enqueue commits on {@link RegionalWork}. */
    default void phase1Regional(CanonicalWorldState state, RegionalWork work, SimulationContext ctx) {}

    /** Phase 2: ordered global commits (after all regional work applied). */
    default void phase2Global(CanonicalWorldState state, SimulationContext ctx) {}

    /**
     * Phase 3: global independent work. Invoked once per tick (not per region).
     * Must not rely on parallel regional fan-out mutating shared state.
     */
    default void phase3Independent(CanonicalWorldState state, SimulationContext ctx) {}

    /** @deprecated Use {@link #phase1Regional(CanonicalWorldState, RegionalWork, SimulationContext)}. */
    @Deprecated
    default void phase1Regional(
            CanonicalWorldState state,
            RegionalWork work,
            SimulationContext ctx,
            SimulationScheduler scheduler
    ) {
        phase1Regional(state, work, ctx);
    }

    /** @deprecated Use {@link #phase2Global(CanonicalWorldState, SimulationContext)}. */
    @Deprecated
    default void phase2Global(
            CanonicalWorldState state,
            SimulationContext ctx,
            SimulationScheduler scheduler
    ) {
        phase2Global(state, ctx);
    }

    /** @deprecated Use {@link #phase3Independent(CanonicalWorldState, SimulationContext)}. */
    @Deprecated
    default void phase3Independent(
            CanonicalWorldState state,
            SimulationContext ctx,
            SimulationScheduler scheduler
    ) {
        phase3Independent(state, ctx);
    }
}
