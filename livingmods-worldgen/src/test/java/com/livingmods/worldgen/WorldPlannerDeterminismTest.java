package com.livingmods.worldgen;

import com.livingmods.common.config.LivingModsConfig;
import com.livingmods.worldgen.plan.PlannedSettlement;
import com.livingmods.worldgen.plan.WorldPlan;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class WorldPlannerDeterminismTest {

    @Test
    void sameSeedProducesIdenticalPlan() {
        LivingModsConfig config = LivingModsConfig.defaults().withWorkers(2);
        WorldPlanner planner = new WorldPlanner(config);

        long seed = 0x123456789ABCDEFL;
        WorldPlan a = planner.plan(seed);
        WorldPlan b = planner.plan(seed);

        assertEquals(a.contentHash(), b.contentHash());
        assertEquals(a.settlements().size(), b.settlements().size());

        List<Long> centersA = settlementCenters(a);
        List<Long> centersB = settlementCenters(b);
        assertEquals(centersA, centersB);
    }

    @Test
    void differentSeedsProduceDifferentPlans() {
        LivingModsConfig config = LivingModsConfig.defaults();
        WorldPlanner planner = new WorldPlanner(config);

        WorldPlan a = planner.plan(0x1111111111111111L);
        WorldPlan b = planner.plan(0x2222222222222222L);

        assertNotEquals(a.contentHash(), b.contentHash());
    }

    private static List<Long> settlementCenters(WorldPlan plan) {
        List<Long> centers = new ArrayList<>();
        for (PlannedSettlement s : plan.settlements().values()) {
            centers.add(s.center().packed());
        }
        centers.sort(Long::compareTo);
        return centers;
    }
}
