package com.livingmods.tools;

import com.livingmods.common.config.LivingModsConfig;
import com.livingmods.testkit.SimulationBench;
import com.livingmods.testkit.WorldPlanFixtures;
import com.livingmods.worldgen.WorldPlanner;
import com.livingmods.worldgen.plan.WorldPlan;

public final class LivingModsTools {
    private LivingModsTools() {}

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            printUsage();
            return;
        }
        switch (args[0]) {
            case "plan" -> planCommand(args);
            case "bench" -> benchCommand(args);
            default -> printUsage();
        }
    }

    private static void planCommand(String[] args) {
        long seed = args.length > 1 ? Long.parseLong(args[1]) : 42L;
        WorldPlan plan = new WorldPlanner(LivingModsConfig.defaults()).plan(seed);
        System.out.printf("seed=%d kingdoms=%d settlements=%d roads=%d hash=%016x%n",
                plan.seed(), plan.kingdoms().size(), plan.settlements().size(), plan.roads().size(), plan.contentHash());
    }

    private static void benchCommand(String[] args) {
        long seed = args.length > 1 ? Long.parseLong(args[1]) : 42L;
        int hours = args.length > 2 ? Integer.parseInt(args[2]) : 24;
        WorldPlan plan = WorldPlanFixtures.smallPlan(seed);
        long hash = SimulationBench.runTicks(plan, hours, 2);
        System.out.printf("bench complete hours=%d contentHash=%016x%n", hours, hash);
    }

    private static void printUsage() {
        System.out.println("LivingModsTools commands:");
        System.out.println("  plan [seed]");
        System.out.println("  bench [seed] [hours]");
    }
}
