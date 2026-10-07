package com.livingmods.sidecar;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.logging.Logger;

public final class SidecarMain {
    private static final Logger LOG = SidecarLogging.logger(SidecarMain.class);

    private SidecarMain() {}

    public static void main(String[] args) throws Exception {
        String worldIdRaw = null;
        int port = 27564;
        Path saveDir = Path.of("livingmods-sidecar-data");
        int workers = LivingModsConfigHolder.DEFAULT_WORKERS;

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--world-id" -> worldIdRaw = args[++i];
                case "--port" -> port = Integer.parseInt(args[++i]);
                case "--save-dir" -> saveDir = Path.of(args[++i]);
                case "--workers" -> workers = Integer.parseInt(args[++i]);
                default -> throw new IllegalArgumentException("Unknown arg: " + args[i]);
            }
        }
        if (worldIdRaw == null) {
            throw new IllegalArgumentException("--world-id is required");
        }
        UUID worldId = UUID.fromString(worldIdRaw);
        Files.createDirectories(saveDir);
        SidecarLogging.init(saveDir);

        LOG.info("Starting LivingMods sidecar world=" + worldId + " port=" + port + " workers=" + workers);
        try (SidecarSimulationHost host = new SidecarSimulationHost(worldId, saveDir, workers)) {
            DiagnosticsExporter diagnostics = new DiagnosticsExporter(host.engine(), host.state());
            try (SidecarServer server = new SidecarServer(host, diagnostics, port)) {
                server.start();
                Thread.currentThread().join();
            }
        }
    }

    /** Avoids pulling common config into static init before logging. */
    private static final class LivingModsConfigHolder {
        static final int DEFAULT_WORKERS = com.livingmods.common.config.LivingModsConfig.defaults().simulationWorkerThreads();
    }
}
