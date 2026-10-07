package com.livingmods.simulation.engine;

import com.livingmods.common.geo.BlockPos2;
import com.livingmods.common.id.ShipmentId;
import com.livingmods.common.model.ResourceType;
import com.livingmods.simulation.CanonicalWorldState;
import com.livingmods.simulation.state.SettlementState;
import com.livingmods.simulation.state.ShipmentState;
import com.livingmods.simulation.state.StockpileState;
import com.livingmods.simulation.tick.RegionalWork;
import com.livingmods.simulation.tick.SimulationContext;
import com.livingmods.simulation.tick.SimulationScheduler;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class TradeEngine implements SimulationSubsystem {
    @Override
    public String name() { return "trade"; }

    @Override
    public void phase1Regional(CanonicalWorldState state, RegionalWork work, SimulationContext ctx, SimulationScheduler scheduler) {
        if (!scheduler.shouldRunMarkets(ctx.time())) return;

        List<ShipmentState> inRegion = new ArrayList<>();
        for (ShipmentState sh : state.shipments().values()) {
            if (!sh.delivered() && regionOf(state, sh).equals(work.region())) {
                inRegion.add(sh);
            }
        }
        inRegion.sort(Comparator.comparing(s -> s.id().value()));

        for (ShipmentState sh : inRegion) {
            List<BlockPos2> route = sh.route();
            int next = sh.routeIndex() + 1;
            if (next >= route.size()) {
                work.enqueueCommit(() -> deliver(state, sh));
            } else {
                work.enqueueCommit(() -> sh.setRouteIndex(next));
            }
        }
    }

    @Override
    public void phase2Global(CanonicalWorldState state, SimulationContext ctx, SimulationScheduler scheduler) {
        if (!scheduler.shouldRunMarkets(ctx.time())) return;

        List<SettlementState> settlements = new ArrayList<>(state.settlements().values());
        settlements.sort(Comparator.comparing(s -> s.id().value()));

        for (int i = 0; i < settlements.size(); i++) {
            SettlementState source = settlements.get(i);
            StockpileState sp = state.stockpiles().get(source.id());
            if (sp == null || sp.get(ResourceType.GRAIN) < 50) continue;

            SettlementState dest = settlements.get((i + 1) % settlements.size());
            if (dest.id().equals(source.id())) continue;
            StockpileState destSp = state.stockpiles().get(dest.id());
            if (destSp == null || destSp.get(ResourceType.GRAIN) > 30) continue;

            double qty = 10.0;
            ShipmentId id = ShipmentId.deterministic(state.seed(), state.shipments().size());
            List<BlockPos2> route = List.of(source.center(), dest.center());
            ShipmentState shipment = new ShipmentState(id, source.id(), dest.id(), ResourceType.GRAIN, qty, route);
            state.shipments().put(id, shipment);
            sp.add(ResourceType.GRAIN, -qty);
            break;
        }
    }

    private static com.livingmods.common.geo.RegionCoord regionOf(CanonicalWorldState state, ShipmentState sh) {
        SettlementState s = state.settlements().get(sh.source());
        return s != null ? s.region() : com.livingmods.common.geo.RegionCoord.of(0, 0);
    }

    private static void deliver(CanonicalWorldState state, ShipmentState sh) {
        StockpileState dest = state.stockpiles().get(sh.destination());
        if (dest != null) {
            dest.add(sh.goods(), sh.quantity());
        }
        sh.setDelivered(true);
    }
}
