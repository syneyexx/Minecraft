package com.livingmods.common.version;

/** Distinct version axes — never conflate these. */
public final class LivingModsVersions {
    public static final int PROTOCOL_VERSION = 1;
    /** Bumped for full plan persistence + territory map (RELEASE BLOCK B). */
    public static final int WORLDGEN_VERSION = 2;
    /** Citizen vertical slice: family graph, housing/work ids, schedule. */
    public static final int CANONICAL_SAVE_SCHEMA = 3;
    /** Bumped when chunk materialization layout/provenance contract changes. */
    public static final int PHYSICAL_CONTENT_REVISION = 2;
    public static final String MOD_VERSION = "0.1.0";
    public static final String SIDECAR_VERSION = "0.1.0";

    private LivingModsVersions() {}
}
