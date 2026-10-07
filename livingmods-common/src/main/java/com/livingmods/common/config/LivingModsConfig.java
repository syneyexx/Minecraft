package com.livingmods.common.config;

/**
 * Tunable configuration. Kingdom count and simulation budgets are not hardcoded assumptions.
 * <p>
 * <b>Core Realm Zone</b> — disk of radius {@link #civilizationRadiusBlocks()} around world origin
 * where major kingdoms are fully planned (capitals, settlements, roads, urban layouts).
 * <p>
 * <b>Frontier</b> — beyond dense core planning but still within the civilization radius for
 * sparse resources, ruins, and bandit camps when {@link #frontierEnabled()} is true.
 * Planning is lazy/bounded: we do not imply infinite full-world civilization planning.
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
        int ipcRequestTimeoutMillis,
        /**
         * Core Realm Zone radius in blocks (default 12000).
         * Major kingdoms are planned fully inside this disk; frontier content uses the same bound.
         */
        int civilizationRadiusBlocks,
        /** When true, place sparse frontier resources/ruins/camps within civilization radius. */
        boolean frontierEnabled
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
                2000,
                12_000,
                true
        );
    }

    public LivingModsConfig withWorkers(int workers) {
        return new LivingModsConfig(
                surfaceKingdomCount, settlementsPerMajorRealm, enableWizardTrees,
                planningRegionSizeChunks, workers, maximumRegionalJobs, simulationBudgetMillis,
                physicalCitizenProjectionCap, physicalWildlifeProjectionCap,
                civilizationDensityScale, mapFogOfWarForStrategicInfo, ipcRequestTimeoutMillis,
                civilizationRadiusBlocks, frontierEnabled
        );
    }

    public LivingModsConfig withSurfaceKingdomCount(int count) {
        return new LivingModsConfig(
                count, settlementsPerMajorRealm, enableWizardTrees,
                planningRegionSizeChunks, simulationWorkerThreads, maximumRegionalJobs, simulationBudgetMillis,
                physicalCitizenProjectionCap, physicalWildlifeProjectionCap,
                civilizationDensityScale, mapFogOfWarForStrategicInfo, ipcRequestTimeoutMillis,
                civilizationRadiusBlocks, frontierEnabled
        );
    }

    public LivingModsConfig withSettlementsPerMajorRealm(int count) {
        return new LivingModsConfig(
                surfaceKingdomCount, count, enableWizardTrees,
                planningRegionSizeChunks, simulationWorkerThreads, maximumRegionalJobs, simulationBudgetMillis,
                physicalCitizenProjectionCap, physicalWildlifeProjectionCap,
                civilizationDensityScale, mapFogOfWarForStrategicInfo, ipcRequestTimeoutMillis,
                civilizationRadiusBlocks, frontierEnabled
        );
    }

    public LivingModsConfig withCivilizationRadiusBlocks(int radius) {
        return new LivingModsConfig(
                surfaceKingdomCount, settlementsPerMajorRealm, enableWizardTrees,
                planningRegionSizeChunks, simulationWorkerThreads, maximumRegionalJobs, simulationBudgetMillis,
                physicalCitizenProjectionCap, physicalWildlifeProjectionCap,
                civilizationDensityScale, mapFogOfWarForStrategicInfo, ipcRequestTimeoutMillis,
                radius, frontierEnabled
        );
    }

    public LivingModsConfig withFrontierEnabled(boolean enabled) {
        return new LivingModsConfig(
                surfaceKingdomCount, settlementsPerMajorRealm, enableWizardTrees,
                planningRegionSizeChunks, simulationWorkerThreads, maximumRegionalJobs, simulationBudgetMillis,
                physicalCitizenProjectionCap, physicalWildlifeProjectionCap,
                civilizationDensityScale, mapFogOfWarForStrategicInfo, ipcRequestTimeoutMillis,
                civilizationRadiusBlocks, enabled
        );
    }

    public LivingModsConfig withEnableWizardTrees(boolean enabled) {
        return new LivingModsConfig(
                surfaceKingdomCount, settlementsPerMajorRealm, enabled,
                planningRegionSizeChunks, simulationWorkerThreads, maximumRegionalJobs, simulationBudgetMillis,
                physicalCitizenProjectionCap, physicalWildlifeProjectionCap,
                civilizationDensityScale, mapFogOfWarForStrategicInfo, ipcRequestTimeoutMillis,
                civilizationRadiusBlocks, frontierEnabled
        );
    }

    public LivingModsConfig withCivilizationDensityScale(double scale) {
        return new LivingModsConfig(
                surfaceKingdomCount, settlementsPerMajorRealm, enableWizardTrees,
                planningRegionSizeChunks, simulationWorkerThreads, maximumRegionalJobs, simulationBudgetMillis,
                physicalCitizenProjectionCap, physicalWildlifeProjectionCap,
                scale, mapFogOfWarForStrategicInfo, ipcRequestTimeoutMillis,
                civilizationRadiusBlocks, frontierEnabled
        );
    }

    /** Inclusive half-extent of the Core Realm Zone axis-aligned planning box. */
    public int coreRealmHalfExtent() {
        return Math.max(512, civilizationRadiusBlocks);
    }
}
