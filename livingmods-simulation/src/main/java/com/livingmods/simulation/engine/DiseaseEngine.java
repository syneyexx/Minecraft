package com.livingmods.simulation.engine;

import com.livingmods.common.event.CivilizationEventType;
import com.livingmods.common.event.HistoricalEvent;
import com.livingmods.common.id.EpidemicId;
import com.livingmods.common.id.HistoricalEventId;
import com.livingmods.common.id.SettlementId;
import com.livingmods.simulation.CanonicalWorldState;
import com.livingmods.simulation.state.CitizenState;
import com.livingmods.simulation.state.EpidemicState;
import com.livingmods.simulation.state.MarketState;
import com.livingmods.simulation.state.SettlementState;
import com.livingmods.simulation.tick.RegionalWork;
import com.livingmods.simulation.tick.SimulationContext;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class DiseaseEngine implements SimulationSubsystem {
    @Override
    public String name() { return "disease"; }

    @Override
    public void phase1Regional(CanonicalWorldState state, RegionalWork work, SimulationContext ctx) {
        if (!ctx.schedule().runDemography()) return;

        List<CitizenState> infected = new ArrayList<>();
        for (EpidemicState ep : state.epidemics().values()) {
            if (!ep.active()) continue;
            for (SettlementId sid : ep.affectedSettlements()) {
                SettlementState s = state.settlements().get(sid);
                if (s == null || !s.region().equals(work.region())) continue;
                for (CitizenState c : state.citizens().values()) {
                    if (!c.alive() || !c.settlementId().equals(sid)) continue;
                    if (ctx.random().chance(ep.transmissionRate() * 0.1)) {
                        infected.add(c);
                    }
                }
            }
        }

        work.enqueueCommit(() -> {
            for (CitizenState c : infected) {
                c.setHealth(c.health() - 5);
                if (ctx.random().chance(0.02)) {
                    c.setAlive(false);
                }
            }
            for (EpidemicState ep : state.epidemics().values()) {
                if (!ep.active()) continue;
                for (SettlementId sid : ep.affectedSettlements()) {
                    MarketState m = state.markets().get(sid);
                    if (m != null) {
                        m.setCrisisSeverity(Math.min(1.0, m.crisisSeverity() + 0.05));
                    }
                }
            }
        });
    }

    @Override
    public void phase2Global(CanonicalWorldState state, SimulationContext ctx) {
        if (!ctx.schedule().runDemography()) return;
        if (!state.epidemics().isEmpty() || state.settlements().isEmpty()) return;
        if (!ctx.random().chance(0.001)) return;

        SettlementId origin = state.settlements().keySet().iterator().next();
        EpidemicId id = EpidemicId.deterministic(state.seed(), state.epidemics().size());
        EpidemicState ep = new EpidemicState(id, "fever", origin, ctx.time().dayIndex());
        state.epidemics().put(id, ep);
        state.appendHistory(new HistoricalEvent(
                HistoricalEventId.deterministic(state.seed(), state.history().size()),
                CivilizationEventType.EPIDEMIC_STARTED,
                ctx.time(),
                "Epidemic",
                "Outbreak near " + origin,
                Optional.empty(),
                Map.of("epidemic", id.toString())
        ));
    }
}
