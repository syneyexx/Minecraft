package com.livingmods.simulation.engine;

import com.livingmods.common.event.HistoricalEvent;
import com.livingmods.simulation.CanonicalWorldState;
import com.livingmods.simulation.tick.RegionalWork;
import com.livingmods.simulation.tick.SimulationContext;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Maintains bounded historical log ordering. */
public final class HistoryEngine implements SimulationSubsystem {
    @Override
    public String name() { return "history"; }

    @Override
    public void phase1Regional(CanonicalWorldState state, RegionalWork work, SimulationContext ctx) {}

    @Override
    public void phase3Independent(CanonicalWorldState state, SimulationContext ctx) {
        if (!ctx.schedule().runGovernment()) return;
        List<HistoricalEvent> events = new ArrayList<>(state.historySnapshot());
        events.sort(Comparator.comparingLong(e -> e.when().absoluteTicks()));
        while (state.history().size() > CanonicalWorldState.MAX_HISTORY_EVENTS) {
            state.history().removeFirst();
        }
    }

    public List<HistoricalEvent> recent(CanonicalWorldState state, int limit) {
        List<HistoricalEvent> events = new ArrayList<>(state.historySnapshot());
        events.sort(Comparator.<HistoricalEvent>comparingLong(e -> e.when().absoluteTicks()).reversed());
        if (events.size() > limit) {
            return events.subList(0, limit);
        }
        return events;
    }
}
