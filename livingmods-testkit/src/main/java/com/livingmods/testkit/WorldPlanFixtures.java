package com.livingmods.testkit;

import com.livingmods.common.config.LivingModsConfig;
import com.livingmods.worldgen.WorldPlanner;
import com.livingmods.worldgen.plan.WorldPlan;

public final class WorldPlanFixtures {
    private WorldPlanFixtures() {}

    public static WorldPlan smallPlan(long seed) {
        LivingModsConfig config = LivingModsConfig.defaults().withWorkers(2);
        return new WorldPlanner(config).plan(seed);
    }
}
