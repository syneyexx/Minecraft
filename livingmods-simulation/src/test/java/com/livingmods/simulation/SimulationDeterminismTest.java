package com.livingmods.simulation;

import com.livingmods.simulation.support.TestWorldPlans;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SimulationDeterminismTest {
    @AfterEach
    void tearDown() {
        // engines shut down in test body
    }

    @Test
    void sameSeedAndWorkerCountsProduceIdenticalHash() {
        var plan = TestWorldPlans.twoKingdomPlan(42L);
        CanonicalWorldState a = InitialStateFactory.fromWorldPlan(plan);
        CanonicalWorldState b = InitialStateFactory.fromWorldPlan(plan);

        SimulationEngine oneWorker = new SimulationEngine(a, 1);
        SimulationEngine fourWorkers = new SimulationEngine(b, 4);
        try {
            for (int i = 0; i < 48; i++) {
                oneWorker.tickHour();
                fourWorkers.tickHour();
            }
            assertEquals(oneWorker.state().contentHash(), fourWorkers.state().contentHash());
        } finally {
            oneWorker.shutdown();
            fourWorkers.shutdown();
        }
    }
}
