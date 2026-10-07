package com.livingmods.simulation.engine;

import com.livingmods.simulation.CanonicalWorldState;
import com.livingmods.simulation.tick.RegionalWork;
import com.livingmods.simulation.tick.SimulationContext;
import com.livingmods.simulation.tick.SimulationScheduler;

public interface SimulationSubsystem {
    String name();

    /** Phase 1: parallel read-only regional calculations; enqueue commits on {@link RegionalWork}. */
    default void phase1Regional(CanonicalWorldState state, RegionalWork work, SimulationContext ctx, SimulationScheduler scheduler) {}

    /** Phase 2: ordered global commits (after all regional work applied). */
    default void phase2Global(CanonicalWorldState state, SimulationContext ctx, SimulationScheduler scheduler) {}

    /** Phase 3: parallel independent work (no cross-region ordering required). */
    default void phase3Independent(CanonicalWorldState state, SimulationContext ctx, SimulationScheduler scheduler) {}
}
