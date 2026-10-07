package com.livingmods.simulation;

import com.livingmods.common.event.CivilizationEventType;
import com.livingmods.common.id.CitizenId;
import com.livingmods.common.id.KingdomId;
import com.livingmods.simulation.engine.GovernmentEngine;
import com.livingmods.simulation.state.CitizenState;
import com.livingmods.simulation.state.KingdomState;
import com.livingmods.simulation.support.TestWorldPlans;
import com.livingmods.simulation.tick.SimulationContext;
import com.livingmods.simulation.tick.SimulationScheduler;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SuccessionTest {
    @Test
    void rulerDeathTriggersSuccession() {
        var plan = TestWorldPlans.twoKingdomPlan(99L);
        CanonicalWorldState state = InitialStateFactory.fromWorldPlan(plan);
        KingdomId kingdomId = plan.kingdoms().getFirst().id();
        KingdomState kingdom = state.kingdoms().get(kingdomId);
        CitizenId oldRuler = kingdom.rulerId();
        CitizenState ruler = state.citizens().get(oldRuler);
        ruler.setAlive(false);
        ruler.setRuler(false);

        GovernmentEngine gov = new GovernmentEngine();
        SimulationScheduler scheduler = new SimulationScheduler();
        SimulationContext ctx = new SimulationContext(state.seed(), state.time(), 0);
        gov.phase2Global(state, ctx, scheduler);

        assertNotEquals(oldRuler, kingdom.rulerId());
        CitizenState successor = state.citizens().get(kingdom.rulerId());
        assertTrue(successor.alive());
        assertTrue(successor.ruler());
        assertTrue(state.historySnapshot().stream()
                .anyMatch(e -> e.type() == CivilizationEventType.SUCCESSION_RESOLVED));
    }
}
