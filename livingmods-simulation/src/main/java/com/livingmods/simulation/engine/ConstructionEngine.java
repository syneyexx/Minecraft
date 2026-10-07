package com.livingmods.simulation.engine;

import com.livingmods.simulation.CanonicalWorldState;
import com.livingmods.simulation.state.SettlementState;
import com.livingmods.simulation.tick.RegionalWork;
import com.livingmods.simulation.tick.SimulationContext;
import com.livingmods.simulation.tick.SimulationScheduler;

public final class ConstructionEngine implements SimulationSubsystem {
    @Override
    public String name() { return "construction"; }

    @Override
    public void phase1Regional(CanonicalWorldState state, RegionalWork work, SimulationContext ctx, SimulationScheduler scheduler) {
        if (!scheduler.shouldRunGovernment(ctx.time())) return;

        for (SettlementState s : state.settlements().values()) {
            if (!s.region().equals(work.region())) continue;
            double deficit = s.developmentDeficit();
            double capacity = s.physicalCapacity();
            if (deficit <= 0) continue;

            double buildRate = Math.min(deficit, capacity * 0.05 + 1.0);
            work.enqueueCommit(() -> {
                s.setDevelopmentDeficit(Math.max(0, deficit - buildRate));
                s.setPhysicalCapacity(capacity + buildRate * 0.1);
            });
        }
    }
}
