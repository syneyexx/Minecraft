package com.livingmods.worldgen.structure;

/**
 * Structure catalog content revision — distinct from protocol / worldgen / save schema.
 */
public final class StructureCatalogVersions {
    /** Bumped when packaged culture structure libraries change selection identity. */
    public static final int CONTENT_REVISION = 2;
    public static final String MANIFEST_ROOT = "assets/livingmods/structures";
    public static final String CULTURE_ROOT = "assets/livingmods/cultures";

    private StructureCatalogVersions() {}
}
