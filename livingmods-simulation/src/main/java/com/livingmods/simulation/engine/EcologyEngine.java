package com.livingmods.simulation.engine;

import com.livingmods.common.geo.RegionCoord;
import com.livingmods.common.id.SpeciesId;
import com.livingmods.simulation.CanonicalWorldState;
import com.livingmods.simulation.state.EcologyState;
import com.livingmods.simulation.tick.RegionalWork;
import com.livingmods.simulation.tick.SimulationContext;
import com.livingmods.simulation.tick.SimulationScheduler;

public final class EcologyEngine implements SimulationSubsystem {
    private static final SpeciesId PREY = SpeciesId.deterministic(1L, 0);
    private static final SpeciesId PREDATOR = SpeciesId.deterministic(1L, 1);

    @Override
    public String name() { return "ecology"; }

    @Override
    public void phase1Regional(CanonicalWorldState state, RegionalWork work, SimulationContext ctx, SimulationScheduler scheduler) {
        if (!scheduler.shouldRunDemography(ctx.time())) return;

        RegionCoord region = work.region();
        EcologyState.SpeciesCohort prey = state.ecology().cohort(region, PREY);
        EcologyState.SpeciesCohort predator = state.ecology().cohort(region, PREDATOR);

        double preyGrowth = prey.population() * 0.02;
        double predation = predator.population() * 0.5;
        double newPrey = Math.max(0, prey.population() + preyGrowth - predation);
        double newPred = predator.population() + predation * 0.1 - predator.population() * 0.01;

        work.enqueueCommit(() -> {
            prey.setPopulation(newPrey);
            predator.setPopulation(Math.max(0, newPred));
            prey.setBiomass(newPrey * 1.2);
            predator.setBiomass(newPred * 2.0);
        });
    }
}
