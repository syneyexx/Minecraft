package com.livingmods.tools.culturelibrary;

import com.livingmods.common.culture.CultureRegistry;
import com.livingmods.common.model.BuildingRole;
import com.livingmods.worldgen.structure.ImportStatus;
import com.livingmods.worldgen.structure.StructureAsset;
import com.livingmods.worldgen.structure.StructureCatalog;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;

public final class CoverageReport {
    private CoverageReport() {}

    public static String write(StructureCatalog catalog, Path mdOut, Path jsonOut) throws IOException {
        StringBuilder md = new StringBuilder();
        StringBuilder json = new StringBuilder();
        md.append("# Culture Structure Coverage\n\n");
        json.append("{\n  \"cultures\": {\n");
        CultureRegistry reg = new CultureRegistry();
        boolean firstCulture = true;
        for (var culture : reg.all()) {
            if (!firstCulture) json.append(",\n");
            firstCulture = false;
            Map<BuildingRole, Integer> byRole = new EnumMap<>(BuildingRole.class);
            int total = 0, authored = 0, sanitized = 0, exact = 0;
            int residential = 0, civic = 0, religious = 0, military = 0, agri = 0, commerce = 0, infra = 0;
            int minBlocks = Integer.MAX_VALUE, maxBlocks = 0;
            for (StructureAsset a : catalog.all()) {
                // Culture-owned assets only (shared "*" counted separately).
                if (!culture.key().equals(a.cultureKey())) continue;
                total++;
                byRole.merge(a.canonicalRole(), 1, Integer::sum);
                if (a.status() == ImportStatus.AUTHORED) authored++;
                if (a.status() == ImportStatus.IMPORTED_SANITIZED) sanitized++;
                if (a.status() == ImportStatus.IMPORTED_EXACT) exact++;
                minBlocks = Math.min(minBlocks, a.blockCount());
                maxBlocks = Math.max(maxBlocks, a.blockCount());
                switch (a.canonicalRole()) {
                    case HOUSE, TOWNHOUSE, MANOR, FARMHOUSE, PALACE -> residential++;
                    case TEMPLE -> religious++;
                    case GUARDHOUSE, BARRACKS, TOWER, GATEHOUSE, CASTLE_KEEP, WALL_SEGMENT, PRISON -> military++;
                    case BARN, MILL, MINE_ENTRANCE, SAWMILL -> agri++;
                    case MARKET_STALL, MARKET_HALL, SHOP, TAVERN, WORKSHOP, SMITHY, WAREHOUSE -> commerce++;
                    case BRIDGE, DOCK, WAYSTONE, WELL -> infra++;
                    case SCHOOL, CLINIC, MONUMENT -> civic++;
                    default -> {
                    }
                }
            }
            if (minBlocks == Integer.MAX_VALUE) minBlocks = 0;
            String status = total >= 90 ? "GOOD" : total >= 50 ? "PARTIAL" : "LOW";
            md.append("## ").append(culture.key().toUpperCase(Locale.ROOT)).append("\n");
            md.append("--------------------------------\n");
            for (BuildingRole role : BuildingRole.values()) {
                int n = byRole.getOrDefault(role, 0);
                if (n == 0) continue;
                md.append(String.format("%-18s %3d%n", role.name(), n));
            }
            md.append("TOTAL              ").append(total).append('\n');
            md.append("STATUS             ").append(status).append("\n\n");

            json.append("    \"").append(culture.key()).append("\": {")
                    .append("\"total\":").append(total)
                    .append(",\"status\":\"").append(status).append("\"")
                    .append(",\"residential\":").append(residential)
                    .append(",\"civic\":").append(civic)
                    .append(",\"religious\":").append(religious)
                    .append(",\"military\":").append(military)
                    .append(",\"agriculture\":").append(agri)
                    .append(",\"commerce\":").append(commerce)
                    .append(",\"infrastructure\":").append(infra)
                    .append(",\"authored\":").append(authored)
                    .append(",\"exact\":").append(exact)
                    .append(",\"sanitized\":").append(sanitized)
                    .append(",\"smallestBlocks\":").append(minBlocks)
                    .append(",\"largestBlocks\":").append(maxBlocks)
                    .append("}");
        }
        json.append("\n  }\n}\n");
        Files.writeString(mdOut, md.toString(), StandardCharsets.UTF_8);
        Files.writeString(jsonOut, json.toString(), StandardCharsets.UTF_8);
        return "coverage_total_cultures=" + reg.all().size() + " catalog_assets=" + catalog.size();
    }
}
