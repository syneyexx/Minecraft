package com.livingmods.simulation;

import com.livingmods.common.id.SettlementId;
import com.livingmods.common.model.ResourceType;
import com.livingmods.simulation.engine.EconomyEngine;
import com.livingmods.simulation.state.MarketState;
import com.livingmods.simulation.state.StockpileState;
import com.livingmods.simulation.support.TestWorldPlans;
import com.livingmods.simulation.tick.SimulationContext;
import com.livingmods.simulation.tick.SimulationScheduler;
import com.livingmods.simulation.tick.RegionalWork;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class EconomyFamineTest {
    @Test
    void lowGrainRaisesPrice() {
        var plan = TestWorldPlans.twoKingdomPlan(7L);
        CanonicalWorldState state = InitialStateFactory.fromWorldPlan(plan);
        SettlementId sid = plan.settlements().keySet().iterator().next();
        StockpileState stock = state.stockpiles().get(sid);
        MarketState market = state.markets().get(sid);
        stock.set(ResourceType.GRAIN, 1.0);
        state.citizens().values().forEach(c -> {
            if (c.settlementId().equals(sid)) {
                c.setProfession(com.livingmods.common.model.Profession.GUARD);
            }
        });
        double before = market.price(ResourceType.GRAIN);

        EconomyEngine economy = new EconomyEngine();
        SimulationScheduler scheduler = new SimulationScheduler();
        SimulationContext ctx = new SimulationContext(state.seed(), state.time(), 0);
        RegionalWork work = new RegionalWork(state.settlements().get(sid).region());
        economy.phase1Regional(state, work, ctx, scheduler);
        work.applyCommits();

        double after = market.price(ResourceType.GRAIN);
        assertTrue(after > before, "grain price should rise when stockpile is depleted");
    }
}
