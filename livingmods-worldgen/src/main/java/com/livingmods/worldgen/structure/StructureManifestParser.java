package com.livingmods.worldgen.structure;

import com.livingmods.common.model.BuildingRole;
import com.livingmods.common.model.SettlementTier;
import com.livingmods.common.model.WealthClass;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Minimal JSON manifest parser (array of asset objects).
 * Avoids third-party JSON deps in worldgen.
 */
public final class StructureManifestParser {
    private StructureManifestParser() {}

    public static List<StructureAsset> parse(byte[] bytes) {
        String text = new String(bytes, StandardCharsets.UTF_8).trim();
        List<StructureAsset> out = new ArrayList<>();
        if (text.isEmpty()) return out;
        // Accept either {"assets":[...]} or bare [...]
        int arrStart = text.indexOf('[');
        int arrEnd = text.lastIndexOf(']');
        if (arrStart < 0 || arrEnd <= arrStart) return out;
        String arr = text.substring(arrStart + 1, arrEnd);
        int depth = 0;
        int objStart = -1;
        for (int i = 0; i < arr.length(); i++) {
            char c = arr.charAt(i);
            if (c == '{') {
                if (depth == 0) objStart = i;
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0 && objStart >= 0) {
                    parseObject(arr.substring(objStart, i + 1)).ifPresent(out::add);
                    objStart = -1;
                }
            }
        }
        return out;
    }

    private static java.util.Optional<StructureAsset> parseObject(String obj) {
        try {
            String assetId = req(obj, "assetId");
            BuildingRole role = BuildingRole.valueOf(str(obj, "canonicalRole", "HOUSE").toUpperCase(Locale.ROOT));
            int width = integer(obj, "width", 8);
            int depth = integer(obj, "depth", 8);
            int height = integer(obj, "height", 6);
            ImportStatus status = enumOr(obj, "status", ImportStatus.class, ImportStatus.AUTHORED);
            StructureAsset asset = new StructureAsset(
                    assetId,
                    str(obj, "source", "authored"),
                    str(obj, "sourceId", ""),
                    str(obj, "sourceUrl", ""),
                    str(obj, "sourceAuthor", ""),
                    str(obj, "sourceTitle", ""),
                    str(obj, "cultureKey", "*"),
                    role,
                    str(obj, "archetype", role.name().toLowerCase(Locale.ROOT)),
                    str(obj, "variant", "v0"),
                    enumOr(obj, "settlementTierMin", SettlementTier.class, SettlementTier.HAMLET),
                    enumOr(obj, "settlementTierMax", SettlementTier.class, SettlementTier.CAPITAL),
                    enumOr(obj, "wealthClass", WealthClass.class, WealthClass.COMMON),
                    enumOr(obj, "sizeClass", StructureSizeClass.class,
                            StructureSizeClass.fromDimensions(width, depth)),
                    width,
                    depth,
                    height,
                    integer(obj, "blockCount", 0),
                    str(obj, "entranceFacing", "south"),
                    integer(obj, "entranceX", width / 2),
                    integer(obj, "entranceY", 1),
                    integer(obj, "entranceZ", 0),
                    dbl(obj, "entranceConfidence", 0.8),
                    intList(obj, "allowedRotations"),
                    bool(obj, "mirrorAllowed", true),
                    dbl(obj, "weight", 1.0),
                    stringList(obj, "tags"),
                    stringList(obj, "biomeTags"),
                    bool(obj, "coastalRequired", false),
                    bool(obj, "underground", false),
                    bool(obj, "defensive", false),
                    bool(obj, "uniquePerSettlement", false),
                    bool(obj, "uniquePerKingdom", false),
                    integer(obj, "requiredClearance", 1),
                    integer(obj, "terrainTolerance", 3),
                    enumOr(obj, "foundationMode", FoundationMode.class, FoundationMode.CUT_AND_FILL),
                    str(obj, "anchor", "center"),
                    enumOr(obj, "interiorClass", InteriorClass.class, InteriorClass.FULL_INTERIOR),
                    bool(obj, "hasInterior", true),
                    integer(obj, "occupationCapacityHint", 0),
                    integer(obj, "residentialSlotsHint", 0),
                    integer(obj, "workSlotsHint", 0),
                    str(obj, "contentPath", ""),
                    str(obj, "sourceHash", ""),
                    str(obj, "contentHash", ""),
                    str(obj, "geometryHash", ""),
                    str(obj, "uniquenessGroup", ""),
                    integer(obj, "importRevision", StructureCatalogVersions.CONTENT_REVISION),
                    status,
                    bool(obj, "sanitized", false)
            );
            return java.util.Optional.of(asset);
        } catch (Exception e) {
            System.err.println("structure_manifest_parse_error: " + e.getMessage());
            return java.util.Optional.empty();
        }
    }

    private static String req(String obj, String key) {
        String v = str(obj, key, null);
        if (v == null || v.isBlank()) throw new IllegalArgumentException("missing " + key);
        return v;
    }

    private static String str(String obj, String key, String def) {
        String pattern = "\"" + key + "\"";
        int i = obj.indexOf(pattern);
        if (i < 0) return def;
        int colon = obj.indexOf(':', i + pattern.length());
        if (colon < 0) return def;
        int q1 = obj.indexOf('"', colon + 1);
        if (q1 < 0) return def;
        int q2 = obj.indexOf('"', q1 + 1);
        if (q2 < 0) return def;
        return obj.substring(q1 + 1, q2);
    }

    private static int integer(String obj, String key, int def) {
        Double d = number(obj, key);
        return d == null ? def : d.intValue();
    }

    private static double dbl(String obj, String key, double def) {
        Double d = number(obj, key);
        return d == null ? def : d;
    }

    private static Double number(String obj, String key) {
        String pattern = "\"" + key + "\"";
        int i = obj.indexOf(pattern);
        if (i < 0) return null;
        int colon = obj.indexOf(':', i + pattern.length());
        if (colon < 0) return null;
        int j = colon + 1;
        while (j < obj.length() && Character.isWhitespace(obj.charAt(j))) j++;
        int k = j;
        while (k < obj.length() && "-+.0123456789eE".indexOf(obj.charAt(k)) >= 0) k++;
        if (k == j) return null;
        try {
            return Double.parseDouble(obj.substring(j, k));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static boolean bool(String obj, String key, boolean def) {
        String pattern = "\"" + key + "\"";
        int i = obj.indexOf(pattern);
        if (i < 0) return def;
        int colon = obj.indexOf(':', i + pattern.length());
        if (colon < 0) return def;
        String rest = obj.substring(colon + 1).trim();
        if (rest.startsWith("true")) return true;
        if (rest.startsWith("false")) return false;
        return def;
    }

    private static <E extends Enum<E>> E enumOr(String obj, String key, Class<E> type, E def) {
        String v = str(obj, key, null);
        if (v == null) return def;
        try {
            return Enum.valueOf(type, v.toUpperCase(Locale.ROOT));
        } catch (Exception e) {
            return def;
        }
    }

    private static List<String> stringList(String obj, String key) {
        String pattern = "\"" + key + "\"";
        int i = obj.indexOf(pattern);
        if (i < 0) return List.of();
        int lb = obj.indexOf('[', i);
        int rb = obj.indexOf(']', lb);
        if (lb < 0 || rb < 0) return List.of();
        String inner = obj.substring(lb + 1, rb);
        List<String> out = new ArrayList<>();
        int q = 0;
        while (q < inner.length()) {
            int q1 = inner.indexOf('"', q);
            if (q1 < 0) break;
            int q2 = inner.indexOf('"', q1 + 1);
            if (q2 < 0) break;
            out.add(inner.substring(q1 + 1, q2));
            q = q2 + 1;
        }
        return out;
    }

    private static List<Integer> intList(String obj, String key) {
        String pattern = "\"" + key + "\"";
        int i = obj.indexOf(pattern);
        if (i < 0) return List.of(0, 90, 180, 270);
        int lb = obj.indexOf('[', i);
        int rb = obj.indexOf(']', lb);
        if (lb < 0 || rb < 0) return List.of(0, 90, 180, 270);
        String inner = obj.substring(lb + 1, rb);
        List<Integer> out = new ArrayList<>();
        for (String part : inner.split(",")) {
            part = part.trim();
            if (part.isEmpty()) continue;
            try {
                out.add(Integer.parseInt(part));
            } catch (NumberFormatException ignored) {
            }
        }
        return out.isEmpty() ? List.of(0, 90, 180, 270) : out;
    }
}
