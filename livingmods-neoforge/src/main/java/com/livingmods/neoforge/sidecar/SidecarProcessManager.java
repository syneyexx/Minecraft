package com.livingmods.neoforge.sidecar;

import com.livingmods.neoforge.LivingModsMod;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
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

    public static synchronized int start(MinecraftServer server, UUID worldId, int port) throws IOException {
        stop(worldId);
        Path worldDir = server.getWorldPath(LevelResource.ROOT).resolve("livingmods/sidecar");
        Files.createDirectories(worldDir);
        Path jar = extractSidecarJar(worldDir);
        Path logFile = worldDir.resolve("livingmods-minecraft.log");
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        ProcessBuilder pb = new ProcessBuilder(
                java,
                "-jar", jar.toAbsolutePath().toString(),
                "--world-id", worldId.toString(),
                "--port", String.valueOf(port),
                "--save-dir", worldDir.toAbsolutePath().toString(),
                "--workers", String.valueOf(Math.max(2, Runtime.getRuntime().availableProcessors() / 2))
        );
        pb.redirectErrorStream(true);
        pb.redirectOutput(ProcessBuilder.Redirect.appendTo(logFile.toFile()));
        Process process = pb.start();
        ProcessHandle handle = new ProcessHandle(worldId, process, port);
        RUNNING.put(worldId, handle);
        LivingModsMod.LOG.info("Started sidecar pid={} port={} world={}", process.pid(), port, worldId);
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

    private record ProcessHandle(UUID worldId, Process process, int port) {}
}
