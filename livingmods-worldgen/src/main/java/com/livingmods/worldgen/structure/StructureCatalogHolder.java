package com.livingmods.worldgen.structure;

/**
 * Process-wide catalog holder. Worldgen/simulation load manifests once;
 * NeoForge may replace with classpath-backed instance at mod init.
 */
public final class StructureCatalogHolder {
    private static volatile StructureCatalog CATALOG = StructureCatalog.empty();

    private StructureCatalogHolder() {}

    public static StructureCatalog get() {
        return CATALOG;
    }

    public static void set(StructureCatalog catalog) {
        CATALOG = catalog == null ? StructureCatalog.empty() : catalog;
    }

    /** Lazy classpath bootstrap used by planners when NeoForge has not injected yet. */
    public static StructureCatalog ensureLoaded() {
        StructureCatalog current = CATALOG;
        if (current.size() > 0) {
            return current;
        }
        synchronized (StructureCatalogHolder.class) {
            if (CATALOG.size() == 0) {
                ClassLoader cl = Thread.currentThread().getContextClassLoader();
                if (cl == null) {
                    cl = StructureCatalogHolder.class.getClassLoader();
                }
                CATALOG = StructureCatalog.loadFromClasspath(cl);
            }
            return CATALOG;
        }
    }
}
