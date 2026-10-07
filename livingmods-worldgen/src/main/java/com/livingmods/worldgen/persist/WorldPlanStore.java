package com.livingmods.worldgen.persist;

import com.livingmods.common.config.LivingModsConfig;
import com.livingmods.common.version.LivingModsVersions;
import com.livingmods.worldgen.WorldPlanner;
import com.livingmods.worldgen.plan.WorldPlan;
import com.livingmods.worldgen.terrain.TerrainProvider;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Versioned compact binary persistence for the <em>full</em> immutable world plan
 * (kingdoms, territories, settlements, roads, districts, lots, buildings, resources,
 * ruins, camps, bridges, walls/gates).
 * <p>
 * On load of an existing world: <b>LOAD</b> the plan — do <b>not</b> regenerate into a
 * different plan when algorithms change. Old worlds keep their civilization plan.
 * New worldgen versions may only extend empty frontier regions in a future migration;
 * they must not silently move existing cities.
 * <p>
 * A small meta header (seed/hash/version/counts) remains at the front for quick checks.
 */
public final class WorldPlanStore {
    private static final int FILE_MAGIC = 0x4C4D5050; // LMPP
    /** Format 1 = metadata only (legacy). Format 2 = full plan payload. */
    public static final int FORMAT_FULL = 2;
    public static final int FORMAT_META_ONLY = 1;

    private WorldPlanStore() {}

    public static Path planFile(Path worldDir) {
        return worldDir.resolve("livingmods/worldplan/plan.bin");
    }

    public static Path metaFile(Path worldDir) {
        return worldDir.resolve("livingmods/worldplan/meta.bin");
    }

    public static void save(Path worldDir, WorldPlan plan) throws IOException {
        Path file = planFile(worldDir);
        Files.createDirectories(file.getParent());
        try (DataOutputStream out = new DataOutputStream(Files.newOutputStream(file))) {
            out.writeInt(FILE_MAGIC);
            out.writeInt(FORMAT_FULL);
            out.writeInt(LivingModsVersions.WORLDGEN_VERSION);
            out.writeLong(plan.seed());
            out.writeLong(plan.contentHash());
            out.writeInt(plan.kingdoms().size());
            out.writeInt(plan.settlements().size());
            out.writeInt(plan.roads().size());
            PlanBinaryCodec.writePlan(out, plan);
        }
        // Optional sidecar meta for quick checks without parsing full payload.
        Path meta = metaFile(worldDir);
        try (DataOutputStream out = new DataOutputStream(Files.newOutputStream(meta))) {
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
        Path meta = metaFile(worldDir);
        if (Files.isRegularFile(meta)) {
            try (DataInputStream in = new DataInputStream(Files.newInputStream(meta))) {
                int magic = in.readInt();
                if (magic != FILE_MAGIC) throw new IOException("Bad world plan meta magic");
                int version = in.readInt();
                long seed = in.readLong();
                long hash = in.readLong();
                int kingdoms = in.readInt();
                int settlements = in.readInt();
                int roads = in.readInt();
                return new CachedPlanMeta(version, seed, hash, kingdoms, settlements, roads, FORMAT_FULL);
            }
        }
        Path file = planFile(worldDir);
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try (DataInputStream in = new DataInputStream(Files.newInputStream(file))) {
            int magic = in.readInt();
            if (magic != FILE_MAGIC) {
                throw new IOException("Bad world plan magic");
            }
            int formatOrVersion = in.readInt();
            int version;
            int format;
            if (formatOrVersion == FORMAT_FULL || formatOrVersion == FORMAT_META_ONLY) {
                format = formatOrVersion;
                version = in.readInt();
            } else {
                // Legacy: magic + worldgenVersion + seed + hash + counts (no format field).
                format = FORMAT_META_ONLY;
                version = formatOrVersion;
            }
            long seed = in.readLong();
            long hash = in.readLong();
            int kingdoms = in.readInt();
            int settlements = in.readInt();
            int roads = in.readInt();
            return new CachedPlanMeta(version, seed, hash, kingdoms, settlements, roads, format);
        }
    }

    /**
     * Load persisted full plan if present. Never regenerates over an existing full plan
     * merely because the planning algorithm or WORLDGEN_VERSION changed.
     */
    public static WorldPlan loadFull(Path worldDir) throws IOException {
        Path file = planFile(worldDir);
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try (DataInputStream in = new DataInputStream(Files.newInputStream(file))) {
            int magic = in.readInt();
            if (magic != FILE_MAGIC) {
                throw new IOException("Bad world plan magic");
            }
            int formatOrVersion = in.readInt();
            int worldgenVersion;
            int format;
            if (formatOrVersion == FORMAT_FULL || formatOrVersion == FORMAT_META_ONLY) {
                format = formatOrVersion;
                worldgenVersion = in.readInt();
            } else {
                format = FORMAT_META_ONLY;
                worldgenVersion = formatOrVersion;
            }
            // Skip meta header fields already written before payload.
            in.readLong(); // seed
            in.readLong(); // hash
            in.readInt();  // kingdoms
            in.readInt();  // settlements
            in.readInt();  // roads
            if (format != FORMAT_FULL) {
                return null; // legacy metadata-only — cannot restore immutable plan
            }
            return PlanBinaryCodec.readPlan(in, worldgenVersion);
        }
    }

    public static WorldPlan loadOrGenerate(Path worldDir, LivingModsConfig config) throws IOException {
        return loadOrGenerate(worldDir, config, readLevelSeed(worldDir), null);
    }

    public static WorldPlan loadOrGenerate(Path worldDir, LivingModsConfig config, long seedHint) throws IOException {
        return loadOrGenerate(worldDir, config, seedHint, null);
    }

    public static WorldPlan loadOrGenerate(
            Path worldDir, LivingModsConfig config, long seedHint, TerrainProvider terrain
    ) throws IOException {
        WorldPlan existing = null;
        try {
            existing = loadFull(worldDir);
        } catch (IOException ex) {
            // Corrupt payload — fall through to regenerate only when unreadable.
            existing = null;
        }
        if (existing != null) {
            // Preserve: do not regenerate into a different plan when algorithms change.
            return existing;
        }

        CachedPlanMeta meta = null;
        try {
            meta = loadMeta(worldDir);
        } catch (IOException ignored) {
            meta = null;
        }

        long seed = seedHint != 0 ? seedHint : (meta != null ? meta.seed : readLevelSeed(worldDir));
        WorldPlanner planner = terrain != null
                ? new WorldPlanner(config, terrain)
                : new WorldPlanner(config);
        WorldPlan plan = planner.plan(seed);
        save(worldDir, plan);
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

    public record CachedPlanMeta(
            int worldgenVersion,
            long seed,
            long contentHash,
            int kingdoms,
            int settlements,
            int roads,
            int format
    ) {
        public CachedPlanMeta(int worldgenVersion, long seed, long contentHash,
                              int kingdoms, int settlements, int roads) {
            this(worldgenVersion, seed, contentHash, kingdoms, settlements, roads, FORMAT_META_ONLY);
        }
    }
}
