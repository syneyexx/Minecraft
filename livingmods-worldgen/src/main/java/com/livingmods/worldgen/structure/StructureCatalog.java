package com.livingmods.worldgen.structure;

import com.livingmods.common.model.BuildingRole;
import com.livingmods.common.model.SettlementTier;
import com.livingmods.common.model.WealthClass;
import com.livingmods.common.util.Hashing;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Data-driven structure catalog. Startup loads manifests/metadata only.
 * Structure block payloads are loaded lazily via {@link #loadContent(String)}.
 */
public final class StructureCatalog {
    private static final StructureCatalog EMPTY = new StructureCatalog(List.of(), 0L, StructureCatalogVersions.CONTENT_REVISION);

    private final Map<String, StructureAsset> byId;
    private final Map<String, List<StructureAsset>> byCultureRole;
    private final long manifestHash;
    private final int contentRevision;
    private final Map<String, MlsStructureFormat.StructureContent> contentCache = new ConcurrentHashMap<>();
    private static final int MAX_CONTENT_CACHE = 48;

    private volatile Path filesystemRoot;
    private volatile ClassLoader resourceLoader;

    public StructureCatalog(List<StructureAsset> assets, long manifestHash, int contentRevision) {
        Map<String, StructureAsset> ids = new LinkedHashMap<>();
        Map<String, List<StructureAsset>> index = new LinkedHashMap<>();
        List<StructureAsset> sorted = new ArrayList<>(assets == null ? List.of() : assets);
        sorted.sort(Comparator.comparing(StructureAsset::assetId));
        List<String> loadErrors = new ArrayList<>();
        for (StructureAsset a : sorted) {
            if (!a.isProductionReady()) continue;
            if (ids.containsKey(a.assetId())) {
                loadErrors.add("duplicate assetId rejected: " + a.assetId());
                continue; // keep first; never silent last-write-wins
            }
            ids.put(a.assetId(), a);
            String key = indexKey(a.cultureKey(), a.canonicalRole());
            index.computeIfAbsent(key, k -> new ArrayList<>()).add(a);
        }
        if (!loadErrors.isEmpty()) {
            for (String err : loadErrors) {
                System.err.println("[StructureCatalog] " + err);
            }
        }
        for (List<StructureAsset> list : index.values()) {
            list.sort(Comparator.comparing(StructureAsset::assetId));
        }
        this.byId = Collections.unmodifiableMap(ids);
        Map<String, List<StructureAsset>> frozen = new LinkedHashMap<>();
        for (Map.Entry<String, List<StructureAsset>> e : index.entrySet()) {
            frozen.put(e.getKey(), List.copyOf(e.getValue()));
        }
        this.byCultureRole = Collections.unmodifiableMap(frozen);
        this.manifestHash = manifestHash;
        this.contentRevision = contentRevision;
    }

    public static StructureCatalog empty() {
        return EMPTY;
    }

    public StructureCatalog withLoaders(ClassLoader loader, Path filesystemRoot) {
        this.resourceLoader = loader;
        this.filesystemRoot = filesystemRoot;
        return this;
    }

    public int size() {
        return byId.size();
    }

    public long manifestHash() {
        return manifestHash;
    }

    public int contentRevision() {
        return contentRevision;
    }

    public Optional<StructureAsset> get(String assetId) {
        if (assetId == null || assetId.isBlank()) return Optional.empty();
        return Optional.ofNullable(byId.get(assetId));
    }

    public List<StructureAsset> all() {
        return List.copyOf(byId.values());
    }

    public List<StructureAsset> candidates(
            String cultureKey,
            BuildingRole role,
            SettlementTier tier,
            WealthClass wealth,
            StructureSizeClass sizeHint
    ) {
        List<StructureAsset> out = new ArrayList<>();
        collect(out, cultureKey, role, tier, wealth, sizeHint);
        if (out.isEmpty() && cultureKey != null && !cultureKey.equals("*")) {
            collect(out, "*", role, tier, wealth, sizeHint);
        }
        out.sort(Comparator.comparing(StructureAsset::assetId));
        return out;
    }

    private void collect(
            List<StructureAsset> out,
            String cultureKey,
            BuildingRole role,
            SettlementTier tier,
            WealthClass wealth,
            StructureSizeClass sizeHint
    ) {
        List<StructureAsset> list = byCultureRole.getOrDefault(indexKey(cultureKey, role), List.of());
        for (StructureAsset a : list) {
            if (!a.matchesTier(tier)) continue;
            if (wealth != null && a.wealthClass().ordinal() > wealth.ordinal() + 1
                    && a.wealthClass() != wealth) {
                continue;
            }
            if (sizeHint != null && a.sizeClass() != sizeHint) {
                // allow adjacent size classes as soft matches later; hard filter only when exact pool non-empty
                continue;
            }
            out.add(a);
        }
        if (out.isEmpty() && sizeHint != null) {
            for (StructureAsset a : list) {
                if (!a.matchesTier(tier)) continue;
                out.add(a);
            }
        }
    }

    /**
     * Deterministic weighted selection with anti-repetition penalty.
     * {@code recentAssetIds} are prior picks in the same settlement (may be empty).
     */
    public Optional<StructureAsset> select(
            String cultureKey,
            BuildingRole role,
            SettlementTier tier,
            WealthClass wealth,
            StructureSizeClass sizeHint,
            long worldSeed,
            long ordinal,
            List<String> recentAssetIds
    ) {
        List<StructureAsset> c = candidates(cultureKey, role, tier, wealth, sizeHint);
        if (c.isEmpty()) {
            c = candidates(cultureKey, role, tier, wealth, null);
        }
        if (c.isEmpty()) return Optional.empty();

        Map<String, Integer> recentCounts = new LinkedHashMap<>();
        if (recentAssetIds != null) {
            for (String id : recentAssetIds) {
                recentCounts.merge(id, 1, Integer::sum);
            }
        }

        // Geometry / uniqueness-group anti-repetition (not just assetId).
        Map<String, Integer> recentGeom = new LinkedHashMap<>();
        Map<String, Integer> recentGroups = new LinkedHashMap<>();
        if (recentAssetIds != null) {
            for (String id : recentAssetIds) {
                StructureAsset prev = byId.get(id);
                if (prev == null) continue;
                if (!prev.geometryHash().isBlank()) {
                    recentGeom.merge(prev.geometryHash(), 1, Integer::sum);
                }
                recentGroups.merge(prev.uniquenessGroup(), 1, Integer::sum);
            }
        }

        double total = 0;
        double[] weights = new double[c.size()];
        for (int i = 0; i < c.size(); i++) {
            StructureAsset a = c.get(i);
            double w = a.weight();
            int used = recentCounts.getOrDefault(a.assetId(), 0);
            if (used > 0) {
                w *= Math.pow(0.45, used);
            }
            int geomUsed = recentGeom.getOrDefault(a.geometryHash(), 0);
            if (geomUsed > 0) {
                w *= Math.pow(0.35, geomUsed);
            }
            int groupUsed = recentGroups.getOrDefault(a.uniquenessGroup(), 0);
            if (a.uniquePerSettlement() && (used > 0 || groupUsed > 0)) {
                w = 0;
            } else if (groupUsed > 0 && isLandmarkRole(role)) {
                w = 0;
            }
            // Cap identical asset dominance for common residential roles.
            if (isResidential(role) && recentAssetIds != null && !recentAssetIds.isEmpty()) {
                double ratio = used / (double) recentAssetIds.size();
                if (ratio > 0.12) {
                    w *= 0.05;
                }
            }
            weights[i] = Math.max(0, w);
            total += weights[i];
        }
        if (total <= 0) {
            return Optional.of(c.get(Math.floorMod(Hashing.mix(worldSeed, ordinal), c.size())));
        }
        double roll = (Hashing.mix(worldSeed, Hashing.mix(0x4153534554L, ordinal)) & 0xfffffffL)
                / (double) 0xfffffffL * total;
        double acc = 0;
        for (int i = 0; i < c.size(); i++) {
            acc += weights[i];
            if (roll <= acc) return Optional.of(c.get(i));
        }
        return Optional.of(c.get(c.size() - 1));
    }

    public Optional<MlsStructureFormat.StructureContent> loadContent(String assetId) {
        StructureAsset asset = byId.get(assetId);
        if (asset == null || asset.contentPath().isBlank()) {
            return Optional.empty();
        }
        MlsStructureFormat.StructureContent cached = contentCache.get(assetId);
        if (cached != null) {
            return Optional.of(cached);
        }
        try {
            byte[] bytes = readBytes(asset.contentPath());
            if (bytes == null) return Optional.empty();
            MlsStructureFormat.StructureContent content = MlsStructureFormat.read(bytes);
            putCache(assetId, content);
            return Optional.of(content);
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    private void putCache(String assetId, MlsStructureFormat.StructureContent content) {
        if (contentCache.size() >= MAX_CONTENT_CACHE) {
            // Drop an arbitrary oldest entry (LinkedHashMap iteration order not guaranteed on ConcurrentHashMap).
            String first = contentCache.keySet().stream().findFirst().orElse(null);
            if (first != null) contentCache.remove(first);
        }
        contentCache.put(assetId, content);
    }

    private byte[] readBytes(String contentPath) throws IOException {
        String normalized = contentPath.startsWith("/") ? contentPath.substring(1) : contentPath;
        if (filesystemRoot != null) {
            Path p = filesystemRoot.resolve(normalized);
            if (Files.isRegularFile(p)) {
                return Files.readAllBytes(p);
            }
            // also try relative to cultures/structures roots
            Path alt = filesystemRoot.resolve(normalized.replace("assets/livingmods/", ""));
            if (Files.isRegularFile(alt)) {
                return Files.readAllBytes(alt);
            }
        }
        ClassLoader cl = resourceLoader != null ? resourceLoader : Thread.currentThread().getContextClassLoader();
        if (cl != null) {
            try (InputStream in = cl.getResourceAsStream(normalized)) {
                if (in != null) {
                    return in.readAllBytes();
                }
            }
        }
        return null;
    }

    public static StructureCatalog loadFromClasspath(ClassLoader cl) {
        List<StructureAsset> assets = new ArrayList<>();
        long hash = Hashing.mix(StructureCatalogVersions.CONTENT_REVISION, 0xCA7A101L);
        try {
            // Master index first
            try (InputStream in = cl.getResourceAsStream(StructureCatalogVersions.MANIFEST_ROOT + "/catalog_index.txt")) {
                if (in != null) {
                    try (BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                        String line;
                        while ((line = br.readLine()) != null) {
                            line = line.trim();
                            if (line.isEmpty() || line.startsWith("#")) continue;
                            try (InputStream min = cl.getResourceAsStream(line)) {
                                if (min != null) {
                                    List<StructureAsset> part = StructureManifestParser.parse(min.readAllBytes());
                                    assets.addAll(part);
                                    hash = Hashing.mix(hash, Hashing.hashString(line));
                                }
                            }
                        }
                    }
                }
            }
        } catch (IOException ignored) {
            // empty catalog — procedural fallback
        }
        return new StructureCatalog(assets, hash, StructureCatalogVersions.CONTENT_REVISION).withLoaders(cl, null);
    }

    public static StructureCatalog loadFromDirectory(Path root) throws IOException {
        List<StructureAsset> assets = new ArrayList<>();
        long hash = Hashing.mix(StructureCatalogVersions.CONTENT_REVISION, 0xD17C001L);
        Path index = root.resolve("structures/catalog_index.txt");
        if (Files.isRegularFile(index)) {
            for (String line : Files.readAllLines(index, StandardCharsets.UTF_8)) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                Path manifest = root.resolve(line.replace("assets/livingmods/", ""));
                if (!Files.isRegularFile(manifest)) {
                    manifest = root.resolve(line);
                }
                if (Files.isRegularFile(manifest)) {
                    assets.addAll(StructureManifestParser.parse(Files.readAllBytes(manifest)));
                    hash = Hashing.mix(hash, Hashing.hashString(line));
                }
            }
        } else {
            // discover **/structure_manifest.json
            Path cultures = root.resolve("cultures");
            if (Files.isDirectory(cultures)) {
                try (DirectoryStream<Path> ds = Files.newDirectoryStream(cultures)) {
                    List<Path> dirs = new ArrayList<>();
                    for (Path p : ds) dirs.add(p);
                    dirs.sort(Comparator.comparing(p -> p.getFileName().toString()));
                    for (Path cultureDir : dirs) {
                        Path manifest = cultureDir.resolve("structure_manifest.json");
                        if (Files.isRegularFile(manifest)) {
                            assets.addAll(StructureManifestParser.parse(Files.readAllBytes(manifest)));
                            hash = Hashing.mix(hash, Hashing.hashString(manifest.toString()));
                        }
                    }
                }
            }
            for (String shared : List.of("structures/shared", "structures/ruins", "structures/bandit", "structures/infrastructure")) {
                Path manifest = root.resolve(shared).resolve("structure_manifest.json");
                if (Files.isRegularFile(manifest)) {
                    assets.addAll(StructureManifestParser.parse(Files.readAllBytes(manifest)));
                    hash = Hashing.mix(hash, Hashing.hashString(manifest.toString()));
                }
            }
        }
        return new StructureCatalog(assets, hash, StructureCatalogVersions.CONTENT_REVISION)
                .withLoaders(null, root);
    }

    public Map<BuildingRole, Integer> coverageByRole(String cultureKey) {
        EnumMap<BuildingRole, Integer> map = new EnumMap<>(BuildingRole.class);
        for (StructureAsset a : byId.values()) {
            if (!a.matchesCulture(cultureKey)) continue;
            map.merge(a.canonicalRole(), 1, Integer::sum);
        }
        return map;
    }

    private static boolean isResidential(BuildingRole role) {
        return role == BuildingRole.HOUSE || role == BuildingRole.TOWNHOUSE
                || role == BuildingRole.FARMHOUSE || role == BuildingRole.MANOR;
    }

    private static boolean isLandmarkRole(BuildingRole role) {
        return role == BuildingRole.PALACE || role == BuildingRole.CASTLE_KEEP
                || role == BuildingRole.TEMPLE || role == BuildingRole.MONUMENT
                || role == BuildingRole.MARKET_HALL;
    }

    /** Soft filters used by asset-first planners. */
    public List<StructureAsset> selectCandidates(
            String cultureKey,
            BuildingRole role,
            SettlementTier tier,
            WealthClass wealth,
            StructureSizeClass sizeHint,
            boolean requireCoastal,
            boolean requireUnderground
    ) {
        List<StructureAsset> c = candidates(cultureKey, role, tier, wealth, sizeHint);
        if (c.isEmpty()) {
            c = candidates(cultureKey, role, tier, wealth, null);
        }
        List<StructureAsset> filtered = new ArrayList<>();
        for (StructureAsset a : c) {
            if (requireCoastal && !a.coastalRequired() && !a.tags().contains("coastal")) {
                // Prefer coastal-tagged when required, but allow non-coastal as soft fallback later.
                continue;
            }
            if (requireUnderground && !a.underground()) continue;
            if (!requireUnderground && a.underground() && !"wizard_trees".equals(cultureKey)) continue;
            filtered.add(a);
        }
        if (filtered.isEmpty()) {
            return c;
        }
        filtered.sort(Comparator.comparing(StructureAsset::assetId));
        return filtered;
    }

    private static String indexKey(String culture, BuildingRole role) {
        return (culture == null ? "*" : culture.toLowerCase(Locale.ROOT)) + "|" + role.name();
    }
}
