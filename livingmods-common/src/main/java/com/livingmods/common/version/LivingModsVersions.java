package com.livingmods.common.version;

/** Distinct version axes — never conflate these. */
public final class LivingModsVersions {
    /** Bumped for typed PlayerActionRequest/Response (M4 player agency). */
    public static final int PROTOCOL_VERSION = 4;
    /** Bumped for full plan persistence + territory map (RELEASE BLOCK B). */
    public static final int WORLDGEN_VERSION = 2;
    /**
     * Schema 5: full player reputation (standing/ruled/legal/knowledge/policies),
     * emergent task lifecycle, player offender on crimes.
     * Schema 4: DynamicPhysicalState. Reads ≥3.
     */
    public static final int CANONICAL_SAVE_SCHEMA = 5;
    /**
     * Initial-world chunk materialization provenance only.
     * Do not conflate with DynamicPhysicalState intent revisions.
     */
    public static final int PHYSICAL_CONTENT_REVISION = 2;
    public static final String MOD_VERSION = "0.1.0";
    public static final String SIDECAR_VERSION = "0.1.0";

    private LivingModsVersions() {}
}
