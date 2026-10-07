package com.livingmods.neoforge;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

public final class LivingModsWorldIds {
    private LivingModsWorldIds() {}

    /**
     * Load a stable world UUID from {@code world/livingmods/world.id}, or create and persist one.
     * The UUID is not derived solely from the seed.
     */
    public static UUID loadOrCreate(Path worldDir, long seed) throws IOException {
        Path idFile = worldDir.resolve("livingmods/world.id");
        Files.createDirectories(idFile.getParent());
        if (Files.isRegularFile(idFile)) {
            String text = Files.readString(idFile, StandardCharsets.UTF_8).trim();
            if (!text.isEmpty()) {
                return UUID.fromString(text);
            }
        }
        UUID id = UUID.randomUUID();
        Files.writeString(idFile, id.toString() + System.lineSeparator(), StandardCharsets.UTF_8);
        Path seedRef = worldDir.resolve("livingmods/world.seed");
        if (!Files.isRegularFile(seedRef)) {
            try (var out = new java.io.DataOutputStream(Files.newOutputStream(seedRef))) {
                out.writeLong(seed);
            }
        }
        LivingModsMod.LOG.info("Created stable world id {} for seed {}", id, seed);
        return id;
    }

    /** Legacy deterministic id — prefer {@link #loadOrCreate(Path, long)}. */
    public static UUID fromSeed(long seed) {
        return UUID.nameUUIDFromBytes((Long.toString(seed) + ":livingmods").getBytes(StandardCharsets.UTF_8));
    }

    public static UUID fromLevelName(String levelName) {
        return UUID.nameUUIDFromBytes((levelName + ":livingmods").getBytes(StandardCharsets.UTF_8));
    }
}
