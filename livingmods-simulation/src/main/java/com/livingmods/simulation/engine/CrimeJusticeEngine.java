package com.livingmods.simulation.engine;

import com.livingmods.common.util.DeterministicRandom;
import com.livingmods.simulation.CanonicalWorldState;
import com.livingmods.simulation.state.CitizenState;
import com.livingmods.simulation.state.SettlementState;
import com.livingmods.simulation.tick.RegionalWork;
import com.livingmods.simulation.tick.SimulationContext;

import java.util.ArrayList;
import java.util.List;

public final class CrimeJusticeEngine implements SimulationSubsystem {
    @Override
    public String name() { return "crime_justice"; }

    @Override
    public void phase1Regional(CanonicalWorldState state, RegionalWork work, SimulationContext ctx) {
        if (!ctx.schedule().runGovernment()) return;

        DeterministicRandom rng = ctx.forkRegion(work.region().x() * 31L + work.region().z());
        List<CitizenState> accused = new ArrayList<>();

        for (CitizenState c : state.citizens().values()) {
            if (!c.alive() || c.incarcerated()) continue;
            SettlementState s = state.settlements().get(c.settlementId());
            if (s == null || !s.region().equals(work.region())) continue;
            if (rng.chance(0.001)) {
                accused.add(c);
            }
        }

        work.enqueueCommit(() -> {
            for (CitizenState c : accused) {
                c.setCrimeStrikes(c.crimeStrikes() + 1);
                if (c.crimeStrikes() >= 3) {
                    c.setIncarcerated(true);
                    c.setWealth(Math.max(0, c.wealth() - 5));
                } else {
                    c.setWealth(Math.max(0, c.wealth() - 1));
                }
            }
        });
    }
}
