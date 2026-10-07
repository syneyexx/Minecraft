package com.livingmods.simulation.engine;

import com.livingmods.common.event.CivilizationEventType;
import com.livingmods.common.event.HistoricalEvent;
import com.livingmods.common.id.HistoricalEventId;
import com.livingmods.common.model.Profession;
import com.livingmods.simulation.CanonicalWorldState;
import com.livingmods.simulation.state.CitizenState;
import com.livingmods.simulation.state.KingdomState;
import com.livingmods.simulation.state.SettlementState;
import com.livingmods.simulation.tick.SimulationContext;
import com.livingmods.simulation.tick.RegionalWork;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class GovernmentEngine implements SimulationSubsystem {
    @Override
    public String name() { return "government"; }

    @Override
    public void phase1Regional(CanonicalWorldState state, RegionalWork work, SimulationContext ctx) {}

    @Override
    public void phase2Global(CanonicalWorldState state, SimulationContext ctx) {
        if (!ctx.schedule().runGovernment()) return;

        for (KingdomState kingdom : state.kingdoms().values()) {
            double taxIncome = 0.0;
            for (var entry : state.settlements().entrySet()) {
                SettlementState s = entry.getValue();
                if (s.ownerKingdom().isEmpty() || !s.ownerKingdom().get().equals(kingdom.id())) continue;
                int pop = countAlive(state, s.id());
                taxIncome += pop * kingdom.taxRate() * 0.1;
            }
            kingdom.setTreasury(kingdom.treasury() + taxIncome);
            kingdom.setLegitimacy(clamp(kingdom.legitimacy() + 0.01, 0, 100));

            CitizenState ruler = state.citizens().get(kingdom.rulerId());
            if (ruler == null || !ruler.alive()) {
                resolveSuccession(state, kingdom, ctx);
            }
        }
    }

    public static void resolveSuccession(CanonicalWorldState state, KingdomState kingdom, SimulationContext ctx) {
        List<CitizenState> candidates = new ArrayList<>();
        for (CitizenState c : state.citizens().values()) {
            if (!c.alive()) continue;
            SettlementState s = state.settlements().get(c.settlementId());
            if (s == null || s.ownerKingdom().isEmpty() || !s.ownerKingdom().get().equals(kingdom.id())) continue;
            if (c.profession() == Profession.NOBLE || c.profession() == Profession.GOVERNMENT_OFFICIAL) {
                candidates.add(c);
            }
        }
        candidates.sort(Comparator
                .comparingInt((CitizenState c) -> c.profession() == Profession.NOBLE ? 0 : 1)
                .thenComparing(c -> c.id().value()));

        if (candidates.isEmpty()) {
            for (CitizenState c : state.citizens().values()) {
                if (c.alive() && state.settlements().get(c.settlementId()) != null) {
                    candidates.add(c);
                    break;
                }
            }
        }
        if (candidates.isEmpty()) return;

        CitizenState successor = candidates.getFirst();
        for (CitizenState c : state.citizens().values()) {
            if (c.ruler()) c.setRuler(false);
        }
        successor.setRuler(true);
        successor.setProfession(Profession.RULER);
        kingdom.setRulerId(successor.id());

        SettlementState capital = state.settlements().get(kingdom.capitalId());
        if (capital != null) {
            capital.setRulerId(successor.id());
        }

        state.appendHistory(new HistoricalEvent(
                HistoricalEventId.deterministic(state.seed(), state.history().size()),
                CivilizationEventType.SUCCESSION_RESOLVED,
                ctx.time(),
                "Succession in " + kingdom.name(),
                successor.givenName() + " " + successor.familyName() + " ascends.",
                capital != null ? Optional.of(capital.center()) : Optional.empty(),
                Map.of("kingdom", kingdom.id().toString())
        ));
    }

    private static int countAlive(CanonicalWorldState state, com.livingmods.common.id.SettlementId id) {
        int n = 0;
        for (CitizenState c : state.citizens().values()) {
            if (c.alive() && c.settlementId().equals(id)) n++;
        }
        return n;
    }

    private static double clamp(double v, double min, double max) {
        return Math.max(min, Math.min(max, v));
    }
}
