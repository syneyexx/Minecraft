package com.livingmods.tools.culturelibrary;

import com.livingmods.common.culture.CultureRegistry;
import com.livingmods.common.model.BuildingRole;
import com.livingmods.worldgen.structure.StructureAsset;
import com.livingmods.worldgen.structure.StructureCatalog;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Cross-culture distinctness using both contentHash and palette-independent geometryHash.
 */
public final class DistinctnessReport {
    private DistinctnessReport() {}

    public static String write(StructureCatalog catalog, Path mdOut) throws IOException {
        StringBuilder md = new StringBuilder();
        md.append("# Culture Distinctness (M6.1)\n\n");
        md.append("geometryHash ignores palette — shared geometry with different blocks is reported.\n\n");

        Map<String, Set<String>> contentByCulture = new HashMap<>();
        Map<String, Set<String>> geomByCulture = new HashMap<>();
        Map<String, Integer> totals = new HashMap<>();
        for (StructureAsset a : catalog.all()) {
            if ("*".equals(a.cultureKey())) continue;
            contentByCulture.computeIfAbsent(a.cultureKey(), k -> new HashSet<>()).add(a.contentHash());
            if (!a.geometryHash().isBlank()) {
                geomByCulture.computeIfAbsent(a.cultureKey(), k -> new HashSet<>()).add(a.geometryHash());
            }
            totals.merge(a.cultureKey(), 1, Integer::sum);
        }

        CultureRegistry reg = new CultureRegistry();
        var cultures = reg.surfaceCultures();

        md.append("## Content-hash exclusivity\n\n");
        md.append("| Culture A | Culture B | Shared contentHash | Exclusive A % |\n");
        md.append("|---|---|---:|---:|\n");
        int sharedContentPairs = 0;
        for (int i = 0; i < cultures.size(); i++) {
            for (int j = i + 1; j < cultures.size(); j++) {
                String a = cultures.get(i).key();
                String b = cultures.get(j).key();
                Set<String> ha = new HashSet<>(contentByCulture.getOrDefault(a, Set.of()));
                Set<String> hb = contentByCulture.getOrDefault(b, Set.of());
                ha.retainAll(hb);
                if (!ha.isEmpty()) sharedContentPairs++;
                int totalA = totals.getOrDefault(a, 0);
                double exclusive = totalA == 0 ? 100.0
                        : 100.0 * (totalA - Math.min(ha.size(), totalA)) / totalA;
                md.append("| ").append(a).append(" | ").append(b).append(" | ")
                        .append(ha.size()).append(" | ")
                        .append(String.format("%.1f", exclusive)).append(" |\n");
            }
        }

        md.append("\n## Geometry-hash exclusivity (palette-independent)\n\n");
        md.append("| Culture A | Culture B | Shared geometryHash | Same-shape different-palette risk |\n");
        md.append("|---|---|---:|---:|\n");
        int sharedGeomPairs = 0;
        for (int i = 0; i < cultures.size(); i++) {
            for (int j = i + 1; j < cultures.size(); j++) {
                String a = cultures.get(i).key();
                String b = cultures.get(j).key();
                Set<String> ha = new HashSet<>(geomByCulture.getOrDefault(a, Set.of()));
                Set<String> hb = geomByCulture.getOrDefault(b, Set.of());
                ha.retainAll(hb);
                if (!ha.isEmpty()) sharedGeomPairs++;
                md.append("| ").append(a).append(" | ").append(b).append(" | ")
                        .append(ha.size()).append(" | ")
                        .append(ha.isEmpty() ? "none" : "REVIEW").append(" |\n");
            }
        }

        md.append("\n## HOUSE geometry exclusivity\n\n");
        for (var culture : cultures) {
            Set<String> houseGeom = new HashSet<>();
            for (StructureAsset a : catalog.all()) {
                if (a.matchesCulture(culture.key()) && a.canonicalRole() == BuildingRole.HOUSE) {
                    houseGeom.add(a.geometryHash().isBlank() ? a.contentHash() : a.geometryHash());
                }
            }
            int overlap = 0;
            for (var other : cultures) {
                if (other.key().equals(culture.key())) continue;
                for (StructureAsset a : catalog.all()) {
                    if (!a.matchesCulture(other.key()) || a.canonicalRole() != BuildingRole.HOUSE) continue;
                    String g = a.geometryHash().isBlank() ? a.contentHash() : a.geometryHash();
                    if (houseGeom.contains(g)) overlap++;
                }
            }
            md.append("- ").append(culture.key()).append(": house_geom=")
                    .append(houseGeom.size()).append(" cross_geom_hits=").append(overlap).append('\n');
        }

        Files.writeString(mdOut, md.toString(), StandardCharsets.UTF_8);
        return "distinctness_shared_content_pairs=" + sharedContentPairs
                + " shared_geometry_pairs=" + sharedGeomPairs;
    }
}
