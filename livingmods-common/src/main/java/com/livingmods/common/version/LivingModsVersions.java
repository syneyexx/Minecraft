package com.livingmods.common.version;

/** Distinct version axes — never conflate these. */
public final class LivingModsVersions {
    /** Bumped for typed PlayerActionRequest/Response (M4 player agency). */
    public static final int PROTOCOL_VERSION = 4;
    /**
     * Bumped for M6 culture structure library: PlannedBuilding.assetId selection
     * participates in new-world geometry. Old plans without assetId keep procedural fallback.
     */
    public static final int WORLDGEN_VERSION = 3;
    /**
     * Schema 6: DynamicStructureRecord.assetId for live construction library identity.
     * Schema 5: full player reputation (standing/ruled/legal/knowledge/policies),
     * emergent task lifecycle, player offender on crimes.
     * Schema 4: DynamicPhysicalState. Reads ≥3.
     */
    public static final int CANONICAL_SAVE_SCHEMA = 6;
    /** Packaged structure catalog content revision (distinct from worldgen/save/protocol). */
    public static final int STRUCTURE_CATALOG_REVISION = 1;
    /**
     * Initial-world chunk materialization provenance only.
     * Do not conflate with DynamicPhysicalState intent revisions.
     */
    public static final int PHYSICAL_CONTENT_REVISION = 2;
    public static final String MOD_VERSION = "0.1.0";
    public static final String SIDECAR_VERSION = "0.1.0";

    private LivingModsVersions() {}
}
