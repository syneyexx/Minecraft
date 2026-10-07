package com.livingmods.worldgen.persist;

import com.livingmods.common.config.LivingModsConfig;
import com.livingmods.common.version.LivingModsVersions;
import com.livingmods.worldgen.WorldPlanner;
import com.livingmods.worldgen.plan.WorldPlan;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Compact on-disk cache for a {@link WorldPlan} (seed + content hash).
 * Full plan is regenerated deterministically from seed when versions match.
 */
public final class WorldPlanStore {
    private static final int FILE_MAGIC = 0x4C4D5050; // LMPP

    private WorldPlanStore() {}

    public static Path planFile(Path worldDir) {
        return worldDir.resolve("livingmods/worldplan/plan.bin");
    }

    public static void save(Path worldDir, WorldPlan plan) throws IOException {
        Path file = planFile(worldDir);
        Files.createDirectories(file.getParent());
        try (DataOutputStream out = new DataOutputStream(Files.newOutputStream(file))) {
            out.writeInt(FILE_MAGIC);
            out.writeInt(LivingModsVersions.WORLDGEN_VERSION);
            out.writeLong(plan.seed());
            out.writeLong(plan.contentHash());
            out.writeInt(plan.kingdoms().size());
            out.writeInt(plan.settlements().size());
            out.writeInt(plan.roads().size());
        }
    }

    public static CachedPlanMeta loadMeta(Path worldDir) throws IOException {
        Path file = planFile(worldDir);
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try (DataInputStream in = new DataInputStream(Files.newInputStream(file))) {
            int magic = in.readInt();
            if (magic != FILE_MAGIC) {
                throw new IOException("Bad world plan magic");
            }
            int version = in.readInt();
            long seed = in.readLong();
            long hash = in.readLong();
            int kingdoms = in.readInt();
            int settlements = in.readInt();
            int roads = in.readInt();
            return new CachedPlanMeta(version, seed, hash, kingdoms, settlements, roads);
        }
    }

    public static WorldPlan loadOrGenerate(Path worldDir, LivingModsConfig config) throws IOException {
        return loadOrGenerate(worldDir, config, readLevelSeed(worldDir));
    }

    public static WorldPlan loadOrGenerate(Path worldDir, LivingModsConfig config, long seedHint) throws IOException {
        CachedPlanMeta meta = loadMeta(worldDir);
        WorldPlanner planner = new WorldPlanner(config);
        if (meta == null) {
            long seed = seedHint != 0 ? seedHint : readLevelSeed(worldDir);
            WorldPlan plan = planner.plan(seed);
            save(worldDir, plan);
            return plan;
        }
        if (meta.worldgenVersion != LivingModsVersions.WORLDGEN_VERSION) {
            WorldPlan plan = planner.plan(meta.seed);
            save(worldDir, plan);
            return plan;
        }
        WorldPlan plan = planner.plan(meta.seed);
        if (plan.contentHash() != meta.contentHash) {
            save(worldDir, plan);
        }
        return plan;
    }

    private static long readLevelSeed(Path worldDir) throws IOException {
        Path seedFile = worldDir.resolve("livingmods/worldplan/seed.dat");
        if (Files.isRegularFile(seedFile) && Files.size(seedFile) >= 8) {
            try (DataInputStream in = new DataInputStream(Files.newInputStream(seedFile))) {
                return in.readLong();
            }
        }
        return 0x4C494769L; // fallback — mod overwrites on first server start
    }

    public record CachedPlanMeta(int worldgenVersion, long seed, long contentHash, int kingdoms, int settlements, int roads) {}
}
