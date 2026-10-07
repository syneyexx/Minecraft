package com.livingmods.simulation.engine;

import com.livingmods.common.model.Profession;
import com.livingmods.common.model.ResourceType;
import com.livingmods.simulation.CanonicalWorldState;
import com.livingmods.simulation.state.CitizenState;
import com.livingmods.simulation.state.MarketState;
import com.livingmods.simulation.state.SettlementState;
import com.livingmods.simulation.state.StockpileState;
import com.livingmods.simulation.tick.RegionalWork;
import com.livingmods.simulation.tick.SimulationContext;
import com.livingmods.simulation.tick.SimulationScheduler;

public final class EconomyEngine implements SimulationSubsystem {
    private static final double CONSUMPTION_PER_CAPITA = 0.15;
    private static final double PRODUCTION_PER_WORKER = 2.0;

    @Override
    public String name() { return "economy"; }

    @Override
    public void phase1Regional(CanonicalWorldState state, RegionalWork work, SimulationContext ctx, SimulationScheduler scheduler) {
        if (!scheduler.shouldRunMarkets(ctx.time())) return;

        for (SettlementState settlement : state.settlements().values()) {
            if (!settlement.region().equals(work.region())) continue;

            StockpileState stock = state.stockpiles().get(settlement.id());
            MarketState market = state.markets().get(settlement.id());
            if (stock == null || market == null) continue;

            int population = 0;
            int farmers = 0;
            for (CitizenState c : state.citizens().values()) {
                if (!c.alive() || !c.settlementId().equals(settlement.id())) continue;
                population++;
                if (c.profession() == Profession.FARMER) farmers++;
            }

            double grainProd = farmers * PRODUCTION_PER_WORKER;
            double grainCons = population * CONSUMPTION_PER_CAPITA;
            double newGrain = stock.get(ResourceType.GRAIN) + grainProd - grainCons;

            double scarcity = newGrain < population * 0.5 ? 1.0 + (population * 0.5 - newGrain) / Math.max(1, population) : 0.0;
            double base = MarketState.basePrice(ResourceType.GRAIN);
            double newPrice = base * (1.0 + scarcity * 2.0);
            double crisis = scarcity > 0.3 ? scarcity : 0.0;
            final int farmerCount = farmers;

            work.enqueueCommit(() -> {
                stock.set(ResourceType.GRAIN, newGrain);
                market.setPrice(ResourceType.GRAIN, newPrice);
                market.setCrisisSeverity(crisis);
                if (grainCons > 0) {
                    stock.add(ResourceType.WOOD, farmerCount * 0.1);
                }
            });
        }
    }
}
