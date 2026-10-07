package com.livingmods.common.config;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Tunable user-facing configuration with validated ranges.
 * Loaded from {@code config/livingmods.properties} when present.
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
        int civilizationRadiusBlocks,
        boolean frontierEnabled,
        long catchUpBudgetSteps,
        int mapDetailLevel,
        boolean debugMode
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
                true,
                4096L,
                1,
                false
        );
    }

    public LivingModsConfig {
        surfaceKingdomCount = clampInt(surfaceKingdomCount, 1, 64);
        settlementsPerMajorRealm = clampInt(settlementsPerMajorRealm, 1, 200);
        planningRegionSizeChunks = clampInt(planningRegionSizeChunks, 16, 256);
        simulationWorkerThreads = clampInt(simulationWorkerThreads, 1, 128);
        maximumRegionalJobs = clampInt(maximumRegionalJobs, 1, 512);
        simulationBudgetMillis = clampLong(simulationBudgetMillis, 5L, 5_000L);
        physicalCitizenProjectionCap = clampInt(physicalCitizenProjectionCap, 0, 512);
        physicalWildlifeProjectionCap = clampInt(physicalWildlifeProjectionCap, 0, 512);
        civilizationDensityScale = clampDouble(civilizationDensityScale, 0.1, 4.0);
        ipcRequestTimeoutMillis = clampInt(ipcRequestTimeoutMillis, 100, 60_000);
        civilizationRadiusBlocks = clampInt(civilizationRadiusBlocks, 1024, 100_000);
        catchUpBudgetSteps = clampLong(catchUpBudgetSteps, 16L, 100_000L);
        mapDetailLevel = clampInt(mapDetailLevel, 0, 3);
    }

    public static LivingModsConfig loadOrDefaults(Path configDir) {
        Path file = configDir.resolve("livingmods.properties");
        if (!Files.isRegularFile(file)) {
            LivingModsConfig defaults = defaults();
            try {
                Files.createDirectories(configDir);
                defaults.save(file);
            } catch (IOException ignored) {
            }
            return defaults;
        }
        Properties props = new Properties();
        try (Reader reader = Files.newBufferedReader(file)) {
            props.load(reader);
            return fromProperties(props);
        } catch (IOException e) {
            return defaults();
        }
    }

    public void save(Path file) throws IOException {
        Properties props = toProperties();
        Files.createDirectories(file.getParent());
        try (Writer writer = Files.newBufferedWriter(file)) {
            props.store(writer, "LivingMods user configuration");
        }
    }

    public static LivingModsConfig fromProperties(Properties props) {
        LivingModsConfig d = defaults();
        return new LivingModsConfig(
                parseInt(props, "surfaceKingdomCount", d.surfaceKingdomCount),
                parseInt(props, "settlementsPerMajorRealm", d.settlementsPerMajorRealm),
                parseBool(props, "enableWizardTrees", d.enableWizardTrees),
                parseInt(props, "planningRegionSizeChunks", d.planningRegionSizeChunks),
                parseInt(props, "simulationWorkerThreads", d.simulationWorkerThreads),
                parseInt(props, "maximumRegionalJobs", d.maximumRegionalJobs),
                parseLong(props, "simulationBudgetMillis", d.simulationBudgetMillis),
                parseInt(props, "physicalCitizenProjectionCap", d.physicalCitizenProjectionCap),
                parseInt(props, "physicalWildlifeProjectionCap", d.physicalWildlifeProjectionCap),
                parseDouble(props, "civilizationDensityScale", d.civilizationDensityScale),
                parseBool(props, "mapFogOfWarForStrategicInfo", d.mapFogOfWarForStrategicInfo),
                parseInt(props, "ipcRequestTimeoutMillis", d.ipcRequestTimeoutMillis),
                parseInt(props, "civilizationRadiusBlocks", d.civilizationRadiusBlocks),
                parseBool(props, "frontierEnabled", d.frontierEnabled),
                parseLong(props, "catchUpBudgetSteps", d.catchUpBudgetSteps),
                parseInt(props, "mapDetailLevel", d.mapDetailLevel),
                parseBool(props, "debugMode", d.debugMode)
        );
    }

    public Properties toProperties() {
        Properties props = new Properties();
        props.setProperty("surfaceKingdomCount", String.valueOf(surfaceKingdomCount));
        props.setProperty("settlementsPerMajorRealm", String.valueOf(settlementsPerMajorRealm));
        props.setProperty("enableWizardTrees", String.valueOf(enableWizardTrees));
        props.setProperty("planningRegionSizeChunks", String.valueOf(planningRegionSizeChunks));
        props.setProperty("simulationWorkerThreads", String.valueOf(simulationWorkerThreads));
        props.setProperty("maximumRegionalJobs", String.valueOf(maximumRegionalJobs));
        props.setProperty("simulationBudgetMillis", String.valueOf(simulationBudgetMillis));
        props.setProperty("physicalCitizenProjectionCap", String.valueOf(physicalCitizenProjectionCap));
        props.setProperty("physicalWildlifeProjectionCap", String.valueOf(physicalWildlifeProjectionCap));
        props.setProperty("civilizationDensityScale", String.valueOf(civilizationDensityScale));
        props.setProperty("mapFogOfWarForStrategicInfo", String.valueOf(mapFogOfWarForStrategicInfo));
        props.setProperty("ipcRequestTimeoutMillis", String.valueOf(ipcRequestTimeoutMillis));
        props.setProperty("civilizationRadiusBlocks", String.valueOf(civilizationRadiusBlocks));
        props.setProperty("frontierEnabled", String.valueOf(frontierEnabled));
        props.setProperty("catchUpBudgetSteps", String.valueOf(catchUpBudgetSteps));
        props.setProperty("mapDetailLevel", String.valueOf(mapDetailLevel));
        props.setProperty("debugMode", String.valueOf(debugMode));
        return props;
    }

    public LivingModsConfig withWorkers(int workers) {
        return new LivingModsConfig(
                surfaceKingdomCount, settlementsPerMajorRealm, enableWizardTrees,
                planningRegionSizeChunks, workers, maximumRegionalJobs, simulationBudgetMillis,
                physicalCitizenProjectionCap, physicalWildlifeProjectionCap,
                civilizationDensityScale, mapFogOfWarForStrategicInfo, ipcRequestTimeoutMillis,
                civilizationRadiusBlocks, frontierEnabled, catchUpBudgetSteps, mapDetailLevel, debugMode
        );
    }

    public LivingModsConfig withSurfaceKingdomCount(int count) {
        return new LivingModsConfig(
                count, settlementsPerMajorRealm, enableWizardTrees,
                planningRegionSizeChunks, simulationWorkerThreads, maximumRegionalJobs, simulationBudgetMillis,
                physicalCitizenProjectionCap, physicalWildlifeProjectionCap,
                civilizationDensityScale, mapFogOfWarForStrategicInfo, ipcRequestTimeoutMillis,
                civilizationRadiusBlocks, frontierEnabled, catchUpBudgetSteps, mapDetailLevel, debugMode
        );
    }

    public LivingModsConfig withSettlementsPerMajorRealm(int count) {
        return new LivingModsConfig(
                surfaceKingdomCount, count, enableWizardTrees,
                planningRegionSizeChunks, simulationWorkerThreads, maximumRegionalJobs, simulationBudgetMillis,
                physicalCitizenProjectionCap, physicalWildlifeProjectionCap,
                civilizationDensityScale, mapFogOfWarForStrategicInfo, ipcRequestTimeoutMillis,
                civilizationRadiusBlocks, frontierEnabled, catchUpBudgetSteps, mapDetailLevel, debugMode
        );
    }

    public LivingModsConfig withCivilizationRadiusBlocks(int radius) {
        return new LivingModsConfig(
                surfaceKingdomCount, settlementsPerMajorRealm, enableWizardTrees,
                planningRegionSizeChunks, simulationWorkerThreads, maximumRegionalJobs, simulationBudgetMillis,
                physicalCitizenProjectionCap, physicalWildlifeProjectionCap,
                civilizationDensityScale, mapFogOfWarForStrategicInfo, ipcRequestTimeoutMillis,
                radius, frontierEnabled, catchUpBudgetSteps, mapDetailLevel, debugMode
        );
    }

    public LivingModsConfig withFrontierEnabled(boolean enabled) {
        return new LivingModsConfig(
                surfaceKingdomCount, settlementsPerMajorRealm, enableWizardTrees,
                planningRegionSizeChunks, simulationWorkerThreads, maximumRegionalJobs, simulationBudgetMillis,
                physicalCitizenProjectionCap, physicalWildlifeProjectionCap,
                civilizationDensityScale, mapFogOfWarForStrategicInfo, ipcRequestTimeoutMillis,
                civilizationRadiusBlocks, enabled, catchUpBudgetSteps, mapDetailLevel, debugMode
        );
    }

    public LivingModsConfig withEnableWizardTrees(boolean enabled) {
        return new LivingModsConfig(
                surfaceKingdomCount, settlementsPerMajorRealm, enabled,
                planningRegionSizeChunks, simulationWorkerThreads, maximumRegionalJobs, simulationBudgetMillis,
                physicalCitizenProjectionCap, physicalWildlifeProjectionCap,
                civilizationDensityScale, mapFogOfWarForStrategicInfo, ipcRequestTimeoutMillis,
                civilizationRadiusBlocks, frontierEnabled, catchUpBudgetSteps, mapDetailLevel, debugMode
        );
    }

    public LivingModsConfig withCivilizationDensityScale(double scale) {
        return new LivingModsConfig(
                surfaceKingdomCount, settlementsPerMajorRealm, enableWizardTrees,
                planningRegionSizeChunks, simulationWorkerThreads, maximumRegionalJobs, simulationBudgetMillis,
                physicalCitizenProjectionCap, physicalWildlifeProjectionCap,
                scale, mapFogOfWarForStrategicInfo, ipcRequestTimeoutMillis,
                civilizationRadiusBlocks, frontierEnabled, catchUpBudgetSteps, mapDetailLevel, debugMode
        );
    }

    public LivingModsConfig withCatchUpBudgetSteps(long steps) {
        return new LivingModsConfig(
                surfaceKingdomCount, settlementsPerMajorRealm, enableWizardTrees,
                planningRegionSizeChunks, simulationWorkerThreads, maximumRegionalJobs, simulationBudgetMillis,
                physicalCitizenProjectionCap, physicalWildlifeProjectionCap,
                civilizationDensityScale, mapFogOfWarForStrategicInfo, ipcRequestTimeoutMillis,
                civilizationRadiusBlocks, frontierEnabled, steps, mapDetailLevel, debugMode
        );
    }

    public LivingModsConfig withMapDetailLevel(int level) {
        return new LivingModsConfig(
                surfaceKingdomCount, settlementsPerMajorRealm, enableWizardTrees,
                planningRegionSizeChunks, simulationWorkerThreads, maximumRegionalJobs, simulationBudgetMillis,
                physicalCitizenProjectionCap, physicalWildlifeProjectionCap,
                civilizationDensityScale, mapFogOfWarForStrategicInfo, ipcRequestTimeoutMillis,
                civilizationRadiusBlocks, frontierEnabled, catchUpBudgetSteps, level, debugMode
        );
    }

    public LivingModsConfig withDebugMode(boolean debug) {
        return new LivingModsConfig(
                surfaceKingdomCount, settlementsPerMajorRealm, enableWizardTrees,
                planningRegionSizeChunks, simulationWorkerThreads, maximumRegionalJobs, simulationBudgetMillis,
                physicalCitizenProjectionCap, physicalWildlifeProjectionCap,
                civilizationDensityScale, mapFogOfWarForStrategicInfo, ipcRequestTimeoutMillis,
                civilizationRadiusBlocks, frontierEnabled, catchUpBudgetSteps, mapDetailLevel, debug
        );
    }

    /** Inclusive half-extent of the Core Realm Zone axis-aligned planning box. */
    public int coreRealmHalfExtent() {
        return Math.max(512, civilizationRadiusBlocks);
    }

    private static int clampInt(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }

    private static long clampLong(long v, long min, long max) {
        return Math.max(min, Math.min(max, v));
    }

    private static double clampDouble(double v, double min, double max) {
        return Math.max(min, Math.min(max, v));
    }

    private static int parseInt(Properties p, String key, int fallback) {
        String raw = p.getProperty(key);
        if (raw == null) return fallback;
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static long parseLong(Properties p, String key, long fallback) {
        String raw = p.getProperty(key);
        if (raw == null) return fallback;
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static double parseDouble(Properties p, String key, double fallback) {
        String raw = p.getProperty(key);
        if (raw == null) return fallback;
        try {
            return Double.parseDouble(raw.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static boolean parseBool(Properties p, String key, boolean fallback) {
        String raw = p.getProperty(key);
        if (raw == null) return fallback;
        return Boolean.parseBoolean(raw.trim());
    }
}
