package com.livingmods.tools.culturelibrary;

import com.livingmods.common.culture.CultureRegistry;
import com.livingmods.common.model.BuildingRole;
import com.livingmods.common.model.SettlementTier;
import com.livingmods.common.model.WealthClass;
import com.livingmods.worldgen.structure.CatalogValidation;
import com.livingmods.worldgen.structure.ImportStatus;
import com.livingmods.worldgen.structure.StructureAsset;
import com.livingmods.worldgen.structure.StructureCatalog;
import com.livingmods.worldgen.structure.StructureSizeClass;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Production-quality coverage report. GOOD requires distinct geometry, not mere manifest rows.
 */
public final class CoverageReport {
    private CoverageReport() {}

    public static String write(StructureCatalog catalog, Path mdOut, Path jsonOut) throws IOException {
        StringBuilder md = new StringBuilder();
        StringBuilder json = new StringBuilder();
        md.append("# Culture Structure Coverage (M6.1)\n\n");
        md.append("Status requires distinct geometry and role breadth — not row count alone.\n\n");
        json.append("{\n  \"cultures\": {\n");

        CatalogValidation.Result validation = CatalogValidation.validate(catalog.all());
        md.append("## Catalog validation\n");
        md.append("- production: ").append(validation.productionCount()).append('\n');
        md.append("- uniqueGeometry: ").append(validation.uniqueGeometryCount()).append('\n');
        md.append("- errors: ").append(validation.errors().size()).append('\n');
        md.append("- warnings: ").append(validation.warnings().size()).append("\n\n");
        for (String e : validation.errors()) {
            md.append("- ERROR: ").append(e).append('\n');
        }

        CultureRegistry reg = new CultureRegistry();
        boolean firstCulture = true;
        // Roles that must have at least one intentional planner path in M6.1
        Set<BuildingRole> requiredReachable = EnumSet.of(
                BuildingRole.HOUSE, BuildingRole.TOWNHOUSE, BuildingRole.MANOR, BuildingRole.PALACE,
                BuildingRole.FARMHOUSE, BuildingRole.BARN, BuildingRole.MILL, BuildingRole.WAREHOUSE,
                BuildingRole.MARKET_STALL, BuildingRole.MARKET_HALL, BuildingRole.SHOP, BuildingRole.TAVERN,
                BuildingRole.SMITHY, BuildingRole.WORKSHOP, BuildingRole.TEMPLE, BuildingRole.SCHOOL,
                BuildingRole.CLINIC, BuildingRole.WELL, BuildingRole.MONUMENT, BuildingRole.PRISON,
                BuildingRole.GUARDHOUSE, BuildingRole.BARRACKS, BuildingRole.TOWER, BuildingRole.GATEHOUSE,
                BuildingRole.CASTLE_KEEP, BuildingRole.WALL_SEGMENT, BuildingRole.DOCK, BuildingRole.BRIDGE,
                BuildingRole.WAYSTONE, BuildingRole.RUIN, BuildingRole.SAWMILL, BuildingRole.MINE_ENTRANCE
        );

        for (var culture : reg.all()) {
            if (!firstCulture) json.append(",\n");
            firstCulture = false;

            int total = 0;
            Set<String> uniqueIds = new HashSet<>();
            Set<String> uniquePaths = new HashSet<>();
            Set<String> contentHashes = new HashSet<>();
            Set<String> geometryHashes = new HashSet<>();
            int authored = 0, sanitized = 0, exact = 0, unavailable = 0;
            int coastal = 0, underground = 0, missingContent = 0;
            int tiny = 0, small = 0, medium = 0, large = 0;
            Map<BuildingRole, Integer> byRole = new EnumMap<>(BuildingRole.class);
            Map<SettlementTier, Integer> byTierMin = new EnumMap<>(SettlementTier.class);
            Map<WealthClass, Integer> byWealth = new EnumMap<>(WealthClass.class);
            Set<BuildingRole> rolesPresent = new LinkedHashSet<>();

            for (StructureAsset a : catalog.all()) {
                if (!culture.key().equals(a.cultureKey())) continue;
                total++;
                uniqueIds.add(a.assetId());
                uniquePaths.add(a.contentPath());
                if (!a.contentHash().isBlank()) contentHashes.add(a.contentHash());
                if (!a.geometryHash().isBlank()) geometryHashes.add(a.geometryHash());
                byRole.merge(a.canonicalRole(), 1, Integer::sum);
                rolesPresent.add(a.canonicalRole());
                byTierMin.merge(a.settlementTierMin(), 1, Integer::sum);
                byWealth.merge(a.wealthClass(), 1, Integer::sum);
                switch (a.status()) {
                    case AUTHORED, VALIDATED -> authored++;
                    case IMPORTED_SANITIZED -> sanitized++;
                    case IMPORTED_EXACT -> exact++;
                    case SOURCE_UNAVAILABLE -> unavailable++;
                    default -> {
                    }
                }
                if (a.coastalRequired()) coastal++;
                if (a.underground()) underground++;
                if (a.contentPath().isBlank()) missingContent++;
                if (a.sizeClass() == StructureSizeClass.TINY) tiny++;
                else if (a.sizeClass() == StructureSizeClass.SMALL) small++;
                else if (a.sizeClass() == StructureSizeClass.MEDIUM) medium++;
                else large++;
            }

            int reachableRoles = 0;
            int unreachableRoles = 0;
            Set<BuildingRole> missingRoles = new LinkedHashSet<>();
            for (BuildingRole role : requiredReachable) {
                if (byRole.getOrDefault(role, 0) > 0) reachableRoles++;
                else {
                    // Optional for some cultures
                    if (role == BuildingRole.MINE_ENTRANCE && !"ironvale".equals(culture.key())) continue;
                    if (role == BuildingRole.DOCK && culture.underground()) continue;
                    if (culture.underground() && (role == BuildingRole.FARMHOUSE || role == BuildingRole.BARN
                            || role == BuildingRole.MILL || role == BuildingRole.BRIDGE
                            || role == BuildingRole.WALL_SEGMENT || role == BuildingRole.SAWMILL)) {
                        continue;
                    }
                    unreachableRoles++;
                    missingRoles.add(role);
                }
            }

            String status = classify(total, geometryHashes.size(), contentHashes.size(),
                    uniqueIds.size(), uniquePaths.size(), missingRoles.size(), culture.underground());

            md.append("## ").append(culture.key().toUpperCase(Locale.ROOT)).append("\n");
            md.append("- manifestEntries: ").append(total).append('\n');
            md.append("- uniqueAssetIds: ").append(uniqueIds.size()).append('\n');
            md.append("- uniqueContentPaths: ").append(uniquePaths.size()).append('\n');
            md.append("- uniqueContentHash: ").append(contentHashes.size()).append('\n');
            md.append("- uniqueGeometryHash: ").append(geometryHashes.size()).append('\n');
            md.append("- authored/exact/sanitized/unavailable: ")
                    .append(authored).append('/').append(exact).append('/')
                    .append(sanitized).append('/').append(unavailable).append('\n');
            md.append("- size tiny/small/medium/large: ")
                    .append(tiny).append('/').append(small).append('/')
                    .append(medium).append('/').append(large).append('\n');
            md.append("- coastal/underground/missingContent: ")
                    .append(coastal).append('/').append(underground).append('/').append(missingContent).append('\n');
            md.append("- roleCoverage: ").append(rolesPresent.size()).append(" roles\n");
            md.append("- missingRequiredRoles: ").append(missingRoles).append('\n');
            md.append("- STATUS: ").append(status).append("\n\n");
            for (BuildingRole role : BuildingRole.values()) {
                int n = byRole.getOrDefault(role, 0);
                if (n == 0) continue;
                md.append(String.format("  %-18s %3d%n", role.name(), n));
            }
            md.append('\n');

            json.append("    \"").append(culture.key()).append("\": {")
                    .append("\"total\":").append(total)
                    .append(",\"uniqueAssetIds\":").append(uniqueIds.size())
                    .append(",\"uniqueContentPaths\":").append(uniquePaths.size())
                    .append(",\"uniqueContentHash\":").append(contentHashes.size())
                    .append(",\"uniqueGeometryHash\":").append(geometryHashes.size())
                    .append(",\"authored\":").append(authored)
                    .append(",\"exact\":").append(exact)
                    .append(",\"sanitized\":").append(sanitized)
                    .append(",\"sourceUnavailable\":").append(unavailable)
                    .append(",\"reachableRoleSlots\":").append(reachableRoles)
                    .append(",\"missingRequiredRoles\":").append(missingRoles.size())
                    .append(",\"status\":\"").append(status).append("\"")
                    .append("}");
        }
        json.append("\n  },\n  \"validationErrors\":").append(validation.errors().size()).append("\n}\n");
        Files.writeString(mdOut, md.toString(), StandardCharsets.UTF_8);
        Files.writeString(jsonOut, json.toString(), StandardCharsets.UTF_8);
        return "coverage_cultures=" + reg.all().size()
                + " catalog_assets=" + catalog.size()
                + " unique_geometry=" + validation.uniqueGeometryCount()
                + " validation_errors=" + validation.errors().size();
    }

    private static String classify(
            int total, int uniqueGeom, int uniqueContent, int uniqueIds, int uniquePaths,
            int missingRoles, boolean underground
    ) {
        if (uniqueIds != total || uniquePaths != total) return "INCOMPLETE";
        if (uniqueGeom < total * 0.95) return "INCOMPLETE";
        int target = underground ? 100 : 100;
        if (total < 50 || uniqueGeom < 45) return "INCOMPLETE";
        if (total < target || uniqueGeom < target - 5 || missingRoles > 6) return "PARTIAL";
        if (total >= target && uniqueGeom >= target - 2 && missingRoles <= 2
                && uniqueContent >= total - 2) {
            return "GOOD";
        }
        if (total >= target && uniqueGeom >= target - 2 && missingRoles == 0) {
            return "RELEASE_READY";
        }
        return "PARTIAL";
    }
}
