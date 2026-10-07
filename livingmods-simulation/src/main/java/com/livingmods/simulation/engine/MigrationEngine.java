package com.livingmods.simulation.engine;

import com.livingmods.common.geo.BlockPos2;
import com.livingmods.common.id.MigrationGroupId;
import com.livingmods.common.model.ResourceType;
import com.livingmods.simulation.CanonicalWorldState;
import com.livingmods.simulation.state.MarketState;
import com.livingmods.simulation.state.MigrationGroupState;
import com.livingmods.simulation.state.SettlementState;
import com.livingmods.simulation.state.StockpileState;
import com.livingmods.simulation.tick.RegionalWork;
import com.livingmods.simulation.tick.SimulationContext;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class MigrationEngine implements SimulationSubsystem {
    @Override
    public String name() { return "migration"; }

    @Override
    public void phase1Regional(CanonicalWorldState state, RegionalWork work, SimulationContext ctx) {
        if (!ctx.schedule().runDemography()) return;

        List<MigrationGroupState> groups = new ArrayList<>();
        for (MigrationGroupState g : state.migrations().values()) {
            if (!g.arrived()) {
                SettlementState s = state.settlements().get(g.source());
                if (s != null && s.region().equals(work.region())) {
                    groups.add(g);
                }
            }
        }
        groups.sort(Comparator.comparing(g -> g.id().value()));

        for (MigrationGroupState g : groups) {
            SettlementState dest = state.settlements().get(g.destination());
            if (dest == null) continue;
            BlockPos2 target = dest.center();
            BlockPos2 cur = g.position();
            int stepX = Integer.compare(target.x(), cur.x());
            int stepZ = Integer.compare(target.z(), cur.z());
            work.enqueueCommit(() -> {
                g.setPosition(cur.add(stepX * 16, stepZ * 16));
                if (g.position().distanceTo(target) < 32) {
                    g.setArrived(true);
                    dest.setHousingUnits(dest.housingUnits() + g.population() / 4);
                }
            });
        }
    }

    @Override
    public void phase2Global(CanonicalWorldState state, SimulationContext ctx) {
        if (!ctx.schedule().runDemography()) return;

        List<SettlementState> settlements = new ArrayList<>(state.settlements().values());
        settlements.sort(Comparator.comparing(s -> s.id().value()));

        for (SettlementState source : settlements) {
            StockpileState sp = state.stockpiles().get(source.id());
            MarketState m = state.markets().get(source.id());
            boolean famine = sp != null && sp.get(ResourceType.GRAIN) < 5;
            boolean warZone = state.wars().values().stream().anyMatch(war ->
                    war.active() && source.ownerKingdom().map(k -> war.participants().contains(k)).orElse(false));
            if (!famine && !warZone) continue;

            SettlementState dest = settlements.stream()
                    .filter(s -> !s.id().equals(source.id()))
                    .filter(s -> {
                        StockpileState dsp = state.stockpiles().get(s.id());
                        return dsp != null && dsp.get(ResourceType.GRAIN) > 20;
                    })
                    .findFirst()
                    .orElse(null);
            if (dest == null) continue;

            MigrationGroupId id = MigrationGroupId.deterministic(state.seed(), state.migrations().size());
            String reason = famine ? "famine" : "war_refugees";
            MigrationGroupState group = new MigrationGroupState(
                    id, source.id(), dest.id(), 8, source.center(), reason);
            state.migrations().put(id, group);
            break;
        }
    }
}
