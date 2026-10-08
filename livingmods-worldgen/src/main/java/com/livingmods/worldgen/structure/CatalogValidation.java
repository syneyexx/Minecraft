package com.livingmods.worldgen.structure;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Strict catalog validation for development tooling and fail-loud catalog construction.
 */
public final class CatalogValidation {
    public record Result(
            List<String> errors,
            List<String> warnings,
            int productionCount,
            int uniqueGeometryCount,
            Map<String, List<String>> duplicateIdGroups,
            Map<String, List<String>> duplicatePathGroups,
            Map<String, List<String>> duplicateGeometryGroups
    ) {
        public boolean ok() {
            return errors.isEmpty();
        }
    }

    private CatalogValidation() {}

    public static Result validate(List<StructureAsset> assets) {
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        Map<String, List<String>> byId = new LinkedHashMap<>();
        Map<String, List<String>> byPath = new LinkedHashMap<>();
        Map<String, List<String>> byGeom = new LinkedHashMap<>();
        Set<String> geomSet = new LinkedHashSet<>();
        int production = 0;

        if (assets == null) {
            return new Result(List.of("null asset list"), List.of(), 0, 0, Map.of(), Map.of(), Map.of());
        }

        for (StructureAsset a : assets) {
            if (a == null) {
                errors.add("null asset entry");
                continue;
            }
            if (!a.isProductionReady()) {
                continue;
            }
            production++;
            byId.computeIfAbsent(a.assetId(), k -> new ArrayList<>()).add(a.assetId());
            if (!a.contentPath().isBlank()) {
                byPath.computeIfAbsent(a.contentPath(), k -> new ArrayList<>()).add(a.assetId());
            } else {
                errors.add("missing contentPath: " + a.assetId());
            }
            if (a.width() < 1 || a.depth() < 1 || a.height() < 1) {
                errors.add("invalid dimensions: " + a.assetId());
            }
            if (a.entranceX() < 0 || a.entranceZ() < 0
                    || a.entranceX() >= a.width() || a.entranceZ() >= a.depth()) {
                errors.add("entrance outside footprint: " + a.assetId());
            }
            if (a.allowedRotations().isEmpty()) {
                errors.add("no allowedRotations: " + a.assetId());
            }
            for (int rot : a.allowedRotations()) {
                if (rot != 0 && rot != 90 && rot != 180 && rot != 270) {
                    errors.add("illegal rotation " + rot + " on " + a.assetId());
                }
            }
            if (a.coastalRequired() && a.underground()) {
                warnings.add("coastal+underground unusual: " + a.assetId());
            }
            if (!a.geometryHash().isBlank()) {
                byGeom.computeIfAbsent(a.geometryHash(), k -> new ArrayList<>()).add(a.assetId());
                geomSet.add(a.geometryHash());
            } else {
                warnings.add("missing geometryHash: " + a.assetId());
            }
        }

        Map<String, List<String>> dupIds = filterDups(byId);
        Map<String, List<String>> dupPaths = filterDups(byPath);
        for (Map.Entry<String, List<String>> e : dupIds.entrySet()) {
            errors.add("duplicate assetId: " + e.getKey() + " count=" + e.getValue().size());
        }
        for (Map.Entry<String, List<String>> e : dupPaths.entrySet()) {
            errors.add("duplicate contentPath: " + e.getKey() + " assets=" + e.getValue());
        }

        Map<String, List<String>> dupGeom = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> e : byGeom.entrySet()) {
            if (e.getValue().size() > 1) {
                // Same culture exact geometry clones are errors; shared "*" infrastructure may share.
                List<String> ids = e.getValue();
                boolean crossCultureShared = ids.stream().anyMatch(id -> id.startsWith("structures_"));
                if (!crossCultureShared) {
                    // Within-culture geometry clones: error when same culture prefix.
                    Map<String, Integer> cultureCounts = new LinkedHashMap<>();
                    for (String id : ids) {
                        String culture = id.contains("/") ? id.substring(0, id.indexOf('/')) : "*";
                        cultureCounts.merge(culture, 1, Integer::sum);
                    }
                    for (Map.Entry<String, Integer> c : cultureCounts.entrySet()) {
                        if (c.getValue() > 1 && !c.getKey().equals("*") && !c.getKey().startsWith("structures_")) {
                            errors.add("exact duplicate geometry within culture " + c.getKey()
                                    + " hash=" + e.getKey() + " assets=" + ids);
                            dupGeom.put(e.getKey(), ids);
                            break;
                        }
                    }
                }
            }
        }

        return new Result(errors, warnings, production, geomSet.size(), dupIds, dupPaths, dupGeom);
    }

    private static Map<String, List<String>> filterDups(Map<String, List<String>> map) {
        Map<String, List<String>> out = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> e : map.entrySet()) {
            if (e.getValue().size() > 1) {
                out.put(e.getKey(), List.copyOf(e.getValue()));
            }
        }
        return out;
    }
}
