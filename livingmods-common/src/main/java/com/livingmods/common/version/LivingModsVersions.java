package com.livingmods.common.version;

/** Distinct version axes — never conflate these. */
public final class LivingModsVersions {
    /** Bumped for typed PhysicalIntentPayload + location-based construction queries. */
    public static final int PROTOCOL_VERSION = 3;
    /** Bumped for full plan persistence + territory map (RELEASE BLOCK B). */
    public static final int WORLDGEN_VERSION = 2;
    /** Schema 4: DynamicPhysicalState (intents, dynamic structures, settlement geometry). */
    public static final int CANONICAL_SAVE_SCHEMA = 4;
    /**
     * Initial-world chunk materialization provenance only.
     * Do not conflate with DynamicPhysicalState intent revisions.
     */
    public static final int PHYSICAL_CONTENT_REVISION = 2;
    public static final String MOD_VERSION = "0.1.0";
    public static final String SIDECAR_VERSION = "0.1.0";

    private LivingModsVersions() {}
}
