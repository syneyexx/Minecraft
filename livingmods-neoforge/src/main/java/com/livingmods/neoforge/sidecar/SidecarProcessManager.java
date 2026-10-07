package com.livingmods.neoforge.sidecar;

import com.livingmods.neoforge.LivingModsMod;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/** Starts/stops one sidecar JVM per world using the embedded fat jar. */
public final class SidecarProcessManager {
    private static final Map<UUID, ProcessHandle> RUNNING = new ConcurrentHashMap<>();

    private SidecarProcessManager() {}

    public static boolean enabled() {
        return Boolean.parseBoolean(System.getProperty("livingmods.sidecar.enabled", "true"));
    }

    public static int defaultWorkers() {
        return Math.max(1, Runtime.getRuntime().availableProcessors() / 3);
    }

    public static synchronized int start(
            MinecraftServer server,
            UUID worldId,
            int port,
            Path worldRoot,
            long seed,
            long planHash,
            int planRevision,
            int workers
    ) throws IOException {
        stop(worldId);
        Path saveDir = server.getWorldPath(LevelResource.ROOT).resolve("livingmods/sidecar");
        Files.createDirectories(saveDir);
        Path jar = extractSidecarJar(saveDir);
        Path logFile = saveDir.resolve("livingmods-minecraft.log");
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();

        LaunchConfig config = new LaunchConfig(
                worldRoot.toAbsolutePath(),
                saveDir.toAbsolutePath(),
                seed,
                planHash,
                planRevision,
                Math.max(1, workers)
        );

        List<String> command = new ArrayList<>();
        command.add(java);
        command.add("-jar");
        command.add(jar.toAbsolutePath().toString());
        command.add("--world-id");
        command.add(worldId.toString());
        command.add("--port");
        command.add(String.valueOf(port));
        command.add("--save-dir");
        command.add(config.saveDir().toString());
        command.add("--world-root");
        command.add(config.worldRoot().toString());
        command.add("--seed");
        command.add(Long.toString(config.seed()));
        command.add("--plan-hash");
        command.add(Long.toString(config.planHash()));
        command.add("--plan-revision");
        command.add(Integer.toString(config.planRevision()));
        command.add("--workers");
        command.add(Integer.toString(config.workers()));

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(true);
        pb.redirectOutput(ProcessBuilder.Redirect.appendTo(logFile.toFile()));
        Process process = pb.start();
        ProcessHandle handle = new ProcessHandle(worldId, process, port, config);
        RUNNING.put(worldId, handle);
        LivingModsMod.LOG.info("Started sidecar pid={} port={} world={} seed={} planHash={}",
                process.pid(), port, worldId, seed, planHash);
        return port;
    }

    /** Restart with the same launch identity if a prior process handle exists. */
    public static synchronized int restart(UUID worldId) throws IOException {
        ProcessHandle prior = RUNNING.get(worldId);
        if (prior == null || prior.config() == null) {
            throw new IOException("No launch config for world " + worldId);
        }
        LaunchConfig config = prior.config();
        int port = prior.port();
        stop(worldId);
        Path jar = extractSidecarJar(config.saveDir());
        Path logFile = config.saveDir().resolve("livingmods-minecraft.log");
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();

        List<String> command = new ArrayList<>();
        command.add(java);
        command.add("-jar");
        command.add(jar.toAbsolutePath().toString());
        command.add("--world-id");
        command.add(worldId.toString());
        command.add("--port");
        command.add(String.valueOf(port));
        command.add("--save-dir");
        command.add(config.saveDir().toString());
        command.add("--world-root");
        command.add(config.worldRoot().toString());
        command.add("--seed");
        command.add(Long.toString(config.seed()));
        command.add("--plan-hash");
        command.add(Long.toString(config.planHash()));
        command.add("--plan-revision");
        command.add(Integer.toString(config.planRevision()));
        command.add("--workers");
        command.add(Integer.toString(config.workers()));

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(true);
        pb.redirectOutput(ProcessBuilder.Redirect.appendTo(logFile.toFile()));
        Process process = pb.start();
        RUNNING.put(worldId, new ProcessHandle(worldId, process, port, config));
        LivingModsMod.LOG.info("Restarted sidecar pid={} port={} world={}", process.pid(), port, worldId);
        return port;
    }

    public static void stop(UUID worldId) {
        ProcessHandle handle = RUNNING.remove(worldId);
        if (handle == null) {
            return;
        }
        Process p = handle.process;
        if (p.isAlive()) {
            p.destroy();
            try {
                if (!p.waitFor(5, TimeUnit.SECONDS)) {
                    p.destroyForcibly();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                p.destroyForcibly();
            }
        }
        LivingModsMod.LOG.info("Stopped sidecar for world {}", worldId);
    }

    public static void stopAll() {
        for (UUID id : RUNNING.keySet().toArray(new UUID[0])) {
            stop(id);
        }
    }

    public static boolean isAlive(UUID worldId) {
        ProcessHandle handle = RUNNING.get(worldId);
        return handle != null && handle.process.isAlive();
    }

    public static int port(UUID worldId) {
        ProcessHandle handle = RUNNING.get(worldId);
        return handle == null ? -1 : handle.port;
    }

    public static LaunchConfig launchConfig(UUID worldId) {
        ProcessHandle handle = RUNNING.get(worldId);
        return handle == null ? null : handle.config;
    }

    private static Path extractSidecarJar(Path worldDir) throws IOException {
        Path cached = worldDir.resolve("livingmods-sidecar.jar");
        try (InputStream in = SidecarProcessManager.class.getResourceAsStream("/livingmods-sidecar.jar")) {
            if (in == null) {
                throw new IOException("Embedded sidecar jar missing from mod resources");
            }
            Files.copy(in, cached, StandardCopyOption.REPLACE_EXISTING);
        }
        return cached;
    }

    public record LaunchConfig(
            Path worldRoot,
            Path saveDir,
            long seed,
            long planHash,
            int planRevision,
            int workers
    ) {}

    private record ProcessHandle(UUID worldId, Process process, int port, LaunchConfig config) {}
}
