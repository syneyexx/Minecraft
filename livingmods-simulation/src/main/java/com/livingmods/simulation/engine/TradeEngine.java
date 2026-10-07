package com.livingmods.simulation.engine;

import com.livingmods.common.event.CivilizationEventType;
import com.livingmods.common.event.HistoricalEvent;
import com.livingmods.common.geo.BlockPos2;
import com.livingmods.common.id.HistoricalEventId;
import com.livingmods.common.id.ShipmentId;
import com.livingmods.common.model.ResourceType;
import com.livingmods.simulation.CanonicalWorldState;
import com.livingmods.simulation.state.MarketState;
import com.livingmods.simulation.state.SettlementState;
import com.livingmods.simulation.state.ShipmentState;
import com.livingmods.simulation.state.StockpileState;
import com.livingmods.simulation.state.TradeNetwork;
import com.livingmods.simulation.tick.RegionalWork;
import com.livingmods.simulation.tick.SimulationContext;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Shipments follow RoadId path segments (or sampled corridors). Caravans are projections
 * of {@link ShipmentState}. Bandit attacks destroy cargo and push market shortages.
 */
public final class TradeEngine implements SimulationSubsystem {
    private static final ResourceType[] TRADE_GOODS = {
            ResourceType.GRAIN, ResourceType.FOOD, ResourceType.IRON, ResourceType.TOOLS,
            ResourceType.WEAPONS, ResourceType.WOOD, ResourceType.STONE, ResourceType.TEXTILES,
            ResourceType.MEDICINE, ResourceType.FISH, ResourceType.COAL, ResourceType.CONSTRUCTION
    };

    @Override
    public String name() { return "trade"; }

    @Override
    public void phase1Regional(CanonicalWorldState state, RegionalWork work, SimulationContext ctx) {
        if (!ctx.schedule().runMarkets()) return;

        List<ShipmentState> inRegion = new ArrayList<>();
        for (ShipmentState sh : state.shipments().values()) {
            if (sh.delivered() || sh.looted()) continue;
            if (regionOf(state, sh).equals(work.region())) {
                inRegion.add(sh);
            }
        }
        inRegion.sort(Comparator.comparing(s -> s.id().value()));

        for (ShipmentState sh : inRegion) {
            BlockPos2 pos = sh.currentPosition();
            double hotspot = state.tradeNetwork().hotspotRisk(pos);
            double attackChance = Math.min(0.4, sh.risk() * 0.35 + hotspot * 0.5)
                    / Math.max(1.0, 1.0 + sh.guards() * 0.35);

            if (ctx.forkRegion(work.region().x() * 31L + work.region().z()).chance(attackChance * 0.15)) {
                work.enqueueCommit(() -> lootShipment(state, sh, ctx));
                continue;
            }

            List<BlockPos2> route = sh.route();
            int next = sh.routeIndex() + 1;
            if (next >= route.size()) {
                work.enqueueCommit(() -> deliver(state, sh));
            } else {
                final int ni = next;
                work.enqueueCommit(() -> {
                    sh.setRouteIndex(ni);
                    sh.setRisk(Math.min(0.95, state.tradeNetwork().hotspotRisk(sh.currentPosition())));
                });
            }
        }
    }

    @Override
    public void phase2Global(CanonicalWorldState state, SimulationContext ctx) {
        if (!ctx.schedule().runMarkets()) return;

        List<SettlementState> settlements = new ArrayList<>(state.settlements().values());
        settlements.sort(Comparator.comparing(s -> s.id().value()));
        if (settlements.size() < 2) return;

        int active = 0;
        for (ShipmentState sh : state.shipments().values()) {
            if (!sh.delivered() && !sh.looted()) active++;
        }
        if (active >= Math.max(2, settlements.size())) return;

        for (ResourceType good : TRADE_GOODS) {
            SettlementState source = null;
            SettlementState dest = null;
            double bestSurplus = 0;
            double bestNeed = 0;

            for (SettlementState s : settlements) {
                StockpileState sp = state.stockpiles().get(s.id());
                if (sp == null) continue;
                double qty = sp.get(good);
                if (qty > bestSurplus) {
                    bestSurplus = qty;
                    source = s;
                }
            }
            if (source == null || bestSurplus < 40) continue;

            for (SettlementState s : settlements) {
                if (s.id().equals(source.id())) continue;
                StockpileState sp = state.stockpiles().get(s.id());
                MarketState m = state.markets().get(s.id());
                if (sp == null || m == null) continue;
                double need = m.price(good) / MarketState.basePrice(good) + (30 - sp.get(good)) * 0.05;
                if (need > bestNeed) {
                    bestNeed = need;
                    dest = s;
                }
            }
            if (dest == null || bestNeed < 1.1) continue;

            SettlementState srcFinal = source;
            SettlementState destFinal = dest;
            Optional<TradeNetwork.RoutedPath> routed = state.tradeNetwork().findRoute(
                    srcFinal.id(), destFinal.id(), srcFinal.center(), destFinal.center());
            TradeNetwork.RoutedPath path = routed.orElse(
                    TradeNetwork.sampledCorridor(srcFinal.center(), destFinal.center()));
            // Never ship with only source+dest endpoints
            if (path.waypoints().size() < 3) {
                path = TradeNetwork.sampledCorridor(srcFinal.center(), destFinal.center());
            }

            double qty = Math.min(12.0, bestSurplus * 0.15);
            StockpileState srcStock = state.stockpiles().get(source.id());
            if (srcStock == null || srcStock.get(good) < qty) continue;

            long day = ctx.time().dayIndex();
            int guards = Math.max(1, (int) Math.round(3 + path.length() / 256.0));
            double risk = Math.min(0.8, 0.08 + path.length() / 2000.0
                    + state.tradeNetwork().hotspotRisk(path.waypoints().get(path.waypoints().size() / 2)));
            long eta = day + Math.max(2, path.waypoints().size());

            ShipmentId id = ShipmentId.deterministic(state.seed(), state.shipments().size());
            ShipmentState shipment = new ShipmentState(
                    id, source.id(), dest.id(), good, qty,
                    path.waypoints(), path.roadSegments(),
                    guards, risk, day, eta
            );
            state.shipments().put(id, shipment);
            srcStock.add(good, -qty);
            MarketState srcMarket = state.markets().get(source.id());
            if (srcMarket != null) {
                srcMarket.addExport(good, qty);
                srcMarket.setTransportCostFactor(1.0 + path.length() / 1500.0);
            }
            break;
        }
    }

    private static com.livingmods.common.geo.RegionCoord regionOf(CanonicalWorldState state, ShipmentState sh) {
        BlockPos2 pos = sh.currentPosition();
        int regionBlocks = com.livingmods.common.geo.RegionCoord.DEFAULT_SIZE_CHUNKS * 16;
        return com.livingmods.common.geo.RegionCoord.of(
                Math.floorDiv(pos.x(), regionBlocks),
                Math.floorDiv(pos.z(), regionBlocks)
        );
    }

    private static void deliver(CanonicalWorldState state, ShipmentState sh) {
        if (sh.delivered() || sh.looted()) return;
        StockpileState dest = state.stockpiles().get(sh.destination());
        if (dest != null) {
            dest.add(sh.goods(), sh.quantity());
        }
        MarketState market = state.markets().get(sh.destination());
        if (market != null) {
            market.addImport(sh.goods(), sh.quantity());
            market.setRiskPressure(Math.max(0, market.riskPressure() - 0.05));
        }
        sh.setDelivered(true);
        sh.setQuantity(0);
    }

    private static void lootShipment(CanonicalWorldState state, ShipmentState sh, SimulationContext ctx) {
        if (sh.delivered() || sh.looted()) return;
        sh.setLooted(true);
        sh.setDelivered(true);
        double lost = sh.quantity();
        sh.setQuantity(0);

        MarketState destMarket = state.markets().get(sh.destination());
        if (destMarket != null) {
            destMarket.setRiskPressure(Math.min(2.0, destMarket.riskPressure() + 0.35));
            destMarket.setCrisisSeverity(Math.min(1.0, destMarket.crisisSeverity() + 0.1));
            // Shortage signal → price spike on missing goods
            double spiked = destMarket.price(sh.goods()) * 1.35;
            destMarket.setPrice(sh.goods(), spiked);
            destMarket.setSecurityResponse(Math.min(1.0, destMarket.securityResponse() + 0.25));
        }
        SettlementState dest = state.settlements().get(sh.destination());
        if (dest != null) {
            dest.setSecurity(Math.min(1.0, dest.security() + 0.1));
        }
        SettlementState source = state.settlements().get(sh.source());
        state.appendHistory(new HistoricalEvent(
                HistoricalEventId.deterministic(state.seed(), state.history().size()),
                CivilizationEventType.BANDIT_ACTIVITY_CHANGED,
                ctx.time(),
                "Caravan raided",
                "Bandits seized " + lost + " " + sh.goods().name().toLowerCase()
                        + (source != null ? " from " + source.name() : ""),
                Optional.of(sh.currentPosition()),
                Map.of("shipment", sh.id().toString(), "goods", sh.goods().name())
        ));
    }
}
