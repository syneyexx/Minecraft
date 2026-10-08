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

public final class DistinctnessReport {
    private DistinctnessReport() {}

    public static String write(StructureCatalog catalog, Path mdOut) throws IOException {
        StringBuilder md = new StringBuilder();
        md.append("# Culture Distinctness\n\n");
        Map<String, Set<String>> hashesByCulture = new HashMap<>();
        Map<String, Integer> totals = new HashMap<>();
        for (StructureAsset a : catalog.all()) {
            if ("*".equals(a.cultureKey())) continue;
            hashesByCulture.computeIfAbsent(a.cultureKey(), k -> new HashSet<>()).add(a.contentHash());
            totals.merge(a.cultureKey(), 1, Integer::sum);
        }
        CultureRegistry reg = new CultureRegistry();
        md.append("| Culture A | Culture B | Shared hashes | Exclusive A % |\n");
        md.append("|---|---|---:|---:|\n");
        var cultures = reg.surfaceCultures();
        int sharedPairs = 0;
        for (int i = 0; i < cultures.size(); i++) {
            for (int j = i + 1; j < cultures.size(); j++) {
                String a = cultures.get(i).key();
                String b = cultures.get(j).key();
                Set<String> ha = new HashSet<>(hashesByCulture.getOrDefault(a, Set.of()));
                Set<String> hb = hashesByCulture.getOrDefault(b, Set.of());
                ha.retainAll(hb);
                int shared = ha.size();
                if (shared > 0) sharedPairs++;
                int totalA = totals.getOrDefault(a, 0);
                double exclusive = totalA == 0 ? 100.0
                        : 100.0 * (totalA - Math.min(shared, totalA)) / totalA;
                md.append("| ").append(a).append(" | ").append(b).append(" | ")
                        .append(shared).append(" | ")
                        .append(String.format("%.1f", exclusive)).append(" |\n");
            }
        }
        // HOUSE exclusivity spotlight
        md.append("\n## HOUSE library exclusivity\n\n");
        for (var culture : cultures) {
            Set<String> houseHashes = new HashSet<>();
            for (StructureAsset a : catalog.all()) {
                if (a.matchesCulture(culture.key()) && a.canonicalRole() == BuildingRole.HOUSE) {
                    houseHashes.add(a.contentHash());
                }
            }
            int overlap = 0;
            for (var other : cultures) {
                if (other.key().equals(culture.key())) continue;
                for (StructureAsset a : catalog.all()) {
                    if (a.matchesCulture(other.key()) && a.canonicalRole() == BuildingRole.HOUSE
                            && houseHashes.contains(a.contentHash())) {
                        overlap++;
                    }
                }
            }
            md.append("- ").append(culture.key()).append(": house_assets=")
                    .append(houseHashes.size()).append(" cross_hits=").append(overlap).append('\n');
        }
        Files.writeString(mdOut, md.toString(), StandardCharsets.UTF_8);
        return "distinctness_shared_pairs_with_overlap=" + sharedPairs;
    }
}
