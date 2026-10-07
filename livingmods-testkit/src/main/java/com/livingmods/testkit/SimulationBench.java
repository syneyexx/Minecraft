package com.livingmods.testkit;

import com.livingmods.simulation.CanonicalWorldState;
import com.livingmods.simulation.InitialStateFactory;
import com.livingmods.simulation.SimulationEngine;
import com.livingmods.worldgen.plan.WorldPlan;

public final class SimulationBench {
    private SimulationBench() {}

    public static long runTicks(WorldPlan plan, int hours, int workers) {
        CanonicalWorldState state = InitialStateFactory.fromWorldPlan(plan);
        SimulationEngine engine = new SimulationEngine(state, workers);
        for (int i = 0; i < hours; i++) {
            engine.tickHour();
        }
        engine.shutdown();
        return state.contentHash();
    }
}
