package com.livingmods.common.config;

/**
 * Tunable configuration. Kingdom count and simulation budgets are not hardcoded assumptions.
 */
public record LivingModsConfig(
        int surfaceKingdomCount,
        int settlementsPerMajorRealm,
        boolean enableWizardTrees,
        int planningRegionSizeChunks,
        int simulationWorkerThreads,
        int maximumRegionalJobs,
        long simulationBudgetMillis,
        int physicalCitizenProjectionCap,
        int physicalWildlifeProjectionCap,
        double civilizationDensityScale,
        boolean mapFogOfWarForStrategicInfo,
        int ipcRequestTimeoutMillis
) {
    public static LivingModsConfig defaults() {
        return new LivingModsConfig(
                12,
                26,
                true,
                64,
                Math.max(2, Runtime.getRuntime().availableProcessors() / 2),
                64,
                50L,
                48,
                24,
                1.0,
                true,
                2000
        );
    }

    public LivingModsConfig withWorkers(int workers) {
        return new LivingModsConfig(
                surfaceKingdomCount, settlementsPerMajorRealm, enableWizardTrees,
                planningRegionSizeChunks, workers, maximumRegionalJobs, simulationBudgetMillis,
                physicalCitizenProjectionCap, physicalWildlifeProjectionCap,
                civilizationDensityScale, mapFogOfWarForStrategicInfo, ipcRequestTimeoutMillis
        );
    }
}
