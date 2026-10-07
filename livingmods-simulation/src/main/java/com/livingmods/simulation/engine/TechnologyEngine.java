package com.livingmods.simulation.engine;

import com.livingmods.common.event.CivilizationEventType;
import com.livingmods.common.event.HistoricalEvent;
import com.livingmods.common.id.HistoricalEventId;
import com.livingmods.common.id.SettlementId;
import com.livingmods.simulation.CanonicalWorldState;
import com.livingmods.simulation.state.SettlementState;
import com.livingmods.simulation.state.TechnologyState;
import com.livingmods.simulation.tick.RegionalWork;
import com.livingmods.simulation.tick.SimulationContext;
import com.livingmods.simulation.tick.SimulationScheduler;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public final class TechnologyEngine implements SimulationSubsystem {
    private static final String IRON_WORKING = "iron_working";
    private static final String ADVANCED_AG = "advanced_agriculture";

    @Override
    public String name() { return "technology"; }

    @Override
    public void phase1Regional(CanonicalWorldState state, RegionalWork work, SimulationContext ctx, SimulationScheduler scheduler) {
        if (!scheduler.shouldRunTechnology(ctx.time())) return;

        List<SettlementState> regional = new ArrayList<>();
        for (SettlementState s : state.settlements().values()) {
            if (s.region().equals(work.region())) regional.add(s);
        }
        regional.sort(Comparator.comparing(s -> s.id().value()));

        TechnologyState tech = state.technology();
        for (SettlementState s : regional) {
            Set<String> known = tech.known(s.id());
            Map<String, Double> progress = tech.progress(s.id());
            if (!known.contains(IRON_WORKING)) {
                double p = progress.getOrDefault(IRON_WORKING, 0.0) + 0.05;
                if (p >= 1.0) {
                    work.enqueueCommit(() -> {
                        known.add(IRON_WORKING);
                        progress.remove(IRON_WORKING);
                    });
                } else {
                    double finalP = p;
                    work.enqueueCommit(() -> progress.put(IRON_WORKING, finalP));
                }
            }
        }
    }

    @Override
    public void phase2Global(CanonicalWorldState state, SimulationContext ctx, SimulationScheduler scheduler) {
        if (!scheduler.shouldRunTechnology(ctx.time())) return;

        List<SettlementState> settlements = new ArrayList<>(state.settlements().values());
        settlements.sort(Comparator.comparing(s -> s.id().value()));

        for (SettlementState source : settlements) {
            if (!state.technology().known(source.id()).contains(ADVANCED_AG)) continue;
            for (SettlementState neighbor : settlements) {
                if (neighbor.id().equals(source.id())) continue;
                if (source.center().distanceTo(neighbor.center()) > 512) continue;
                Set<String> known = state.technology().known(neighbor.id());
                if (!known.contains(ADVANCED_AG) && ctx.random().chance(0.1)) {
                    known.add(ADVANCED_AG);
                    state.appendHistory(new HistoricalEvent(
                            HistoricalEventId.deterministic(state.seed(), state.history().size()),
                            CivilizationEventType.TECHNOLOGY_DISCOVERED,
                            ctx.time(),
                            "Knowledge spread",
                            ADVANCED_AG + " reaches " + neighbor.name(),
                            Optional.of(neighbor.center()),
                            Map.of("settlement", neighbor.id().toString())
                    ));
                    return;
                }
            }
        }
    }
}
