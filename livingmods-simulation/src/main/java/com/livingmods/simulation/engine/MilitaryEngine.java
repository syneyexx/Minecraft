package com.livingmods.simulation.engine;

import com.livingmods.common.event.CivilizationEventType;
import com.livingmods.common.event.HistoricalEvent;
import com.livingmods.common.id.HistoricalEventId;
import com.livingmods.common.id.KingdomId;
import com.livingmods.common.id.WarId;
import com.livingmods.common.model.DiplomaticRelation;
import com.livingmods.simulation.CanonicalWorldState;
import com.livingmods.simulation.state.ArmyState;
import com.livingmods.simulation.state.KingdomState;
import com.livingmods.simulation.state.SettlementState;
import com.livingmods.simulation.state.WarState;
import com.livingmods.simulation.tick.RegionalWork;
import com.livingmods.simulation.tick.SimulationContext;
import com.livingmods.simulation.tick.SimulationScheduler;

import java.util.Map;
import java.util.Optional;

public final class MilitaryEngine implements SimulationSubsystem {
    @Override
    public String name() { return "military"; }

    @Override
    public void phase1Regional(CanonicalWorldState state, RegionalWork work, SimulationContext ctx, SimulationScheduler scheduler) {
        if (!scheduler.shouldRunGovernment(ctx.time())) return;

        for (ArmyState army : state.armies().values()) {
            SettlementState s = nearestSettlement(state, army.position());
            if (s == null || !s.region().equals(work.region())) continue;

            work.enqueueCommit(() -> {
                army.setSupply(clamp(army.supply() - 0.02, 0, 1));
                if (army.siegeTarget() != null) {
                    army.setSiegeProgress(army.siegeProgress() + 1);
                    if (army.siegeProgress() > 30) {
                        army.setSiegeTarget(null);
                        army.setSiegeProgress(0);
                    }
                }
            });
        }
    }

    @Override
    public void phase2Global(CanonicalWorldState state, SimulationContext ctx, SimulationScheduler scheduler) {
        if (!scheduler.shouldRunDiplomacyWeekly(ctx.time())) return;

        var kingdoms = state.kingdoms().values().stream().toList();
        if (kingdoms.size() < 2) return;

        KingdomState a = kingdoms.get(0);
        KingdomState b = kingdoms.get(1);
        DiplomaticRelation rel = state.diplomacy().relation(a.id(), b.id());
        if (rel == DiplomaticRelation.HOSTILE || rel == DiplomaticRelation.AT_WAR) {
            boolean already = state.wars().values().stream()
                    .anyMatch(w -> w.active() && w.participants().contains(a.id()) && w.participants().contains(b.id()));
            if (!already && ctx.random().chance(0.05)) {
                WarId warId = WarId.deterministic(state.seed(), state.wars().size());
                WarState war = new WarState(warId, a.id(), b.id(), ctx.time().dayIndex());
                state.wars().put(warId, war);
                state.diplomacy().setRelation(a.id(), b.id(), DiplomaticRelation.AT_WAR);
                state.appendHistory(new HistoricalEvent(
                        HistoricalEventId.deterministic(state.seed(), state.history().size()),
                        CivilizationEventType.WAR_STARTED,
                        ctx.time(),
                        "War declared",
                        a.name() + " wars " + b.name(),
                        Optional.empty(),
                        Map.of("war", warId.toString())
                ));
            }
        }
    }

    private static SettlementState nearestSettlement(CanonicalWorldState state, com.livingmods.common.geo.BlockPos2 pos) {
        SettlementState best = null;
        double bestD = Double.MAX_VALUE;
        for (SettlementState s : state.settlements().values()) {
            double d = s.center().distanceTo(pos);
            if (d < bestD) {
                bestD = d;
                best = s;
            }
        }
        return best;
    }

    private static double clamp(double v, double min, double max) {
        return Math.max(min, Math.min(max, v));
    }
}
