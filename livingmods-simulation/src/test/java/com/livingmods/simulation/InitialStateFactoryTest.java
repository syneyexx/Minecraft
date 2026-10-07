package com.livingmods.simulation;

import com.livingmods.common.model.DiplomaticRelation;
import com.livingmods.common.model.Profession;
import com.livingmods.simulation.support.TestWorldPlans;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InitialStateFactoryTest {
    @Test
    void factoryMatchesWorldPlanPopulationAndDiplomacy() {
        var plan = TestWorldPlans.twoKingdomPlan(1234L);
        CanonicalWorldState state = InitialStateFactory.fromWorldPlan(plan);

        int plannedPop = plan.settlements().values().stream().mapToInt(s -> s.plannedPopulation()).sum();
        long alive = state.citizens().values().stream().filter(c -> c.alive()).count();
        assertEquals(plannedPop, alive);

        assertEquals(plan.kingdoms().size(), state.kingdoms().size());
        assertEquals(plan.settlements().size(), state.settlements().size());

        var kingdoms = plan.kingdoms();
        assertEquals(
                DiplomaticRelation.NEUTRAL,
                state.diplomacy().relation(kingdoms.get(0).id(), kingdoms.get(1).id()));

        assertFalse(state.history().isEmpty());
        assertTrue(state.citizens().values().stream().anyMatch(c -> c.profession() == Profession.RULER));
    }
}
