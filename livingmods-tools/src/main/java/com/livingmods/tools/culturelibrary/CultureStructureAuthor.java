package com.livingmods.tools.culturelibrary;

import com.livingmods.common.culture.CultureDefinition;
import com.livingmods.common.culture.CultureRegistry;
import com.livingmods.common.model.BuildingRole;
import com.livingmods.common.model.SettlementTier;
import com.livingmods.common.model.WealthClass;
import com.livingmods.common.util.Hashing;
import com.livingmods.worldgen.structure.FoundationMode;
import com.livingmods.worldgen.structure.ImportStatus;
import com.livingmods.worldgen.structure.InteriorClass;
import com.livingmods.worldgen.structure.MlsStructureFormat;
import com.livingmods.worldgen.structure.StructureCatalogVersions;
import com.livingmods.worldgen.structure.StructureSizeClass;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Authors culture-distinct MineLife structure assets (MLS1) into the resource tree.
 * Used when BuildPaste payloads are not publicly downloadable.
 * Geometry differs by culture silhouette — not palette-only variants.
 */
public final class CultureStructureAuthor {
    private record Spec(
            BuildingRole role,
            String archetype,
            SettlementTier minTier,
            SettlementTier maxTier,
            WealthClass wealth,
            int variants,
            boolean uniquePerSettlement,
            boolean defensive,
            boolean underground
    ) {}

    private final Path resourcesRoot;

    public CultureStructureAuthor(Path resourcesRoot) {
        this.resourcesRoot = resourcesRoot;
    }

    public int authorAll() throws IOException {
        CultureRegistry registry = new CultureRegistry();
        int total = 0;
        List<String> indexLines = new ArrayList<>();
        indexLines.add("# LivingMods structure catalog index — paths relative to classpath root");
        for (CultureDefinition culture : registry.all()) {
            total += authorCulture(culture, indexLines);
        }
        total += authorShared(indexLines);
        Path index = resourcesRoot.resolve("structures/catalog_index.txt");
        Files.createDirectories(index.getParent());
        Files.writeString(index, String.join("\n", indexLines) + "\n", StandardCharsets.UTF_8);
        return total;
    }

    private int authorCulture(CultureDefinition culture, List<String> indexLines) throws IOException {
        Path cultureDir = resourcesRoot.resolve("cultures/" + culture.key());
        Path structuresDir = cultureDir.resolve("structures");
        Files.createDirectories(structuresDir);
        writeCultureJson(culture, cultureDir.resolve("culture.json"));

        List<Spec> specs = culture.underground() ? wizardSpecs() : surfaceSpecs(culture.key());
        List<String> manifestEntries = new ArrayList<>();
        int count = 0;
        for (Spec spec : specs) {
            for (int v = 0; v < spec.variants(); v++) {
                long seed = Hashing.mix(Hashing.hashString(culture.key()),
                        Hashing.mix(Hashing.hashString(spec.archetype()), v));
                AuthoredBuilding built = build(culture, spec, v, seed);
                String fileName = spec.role().name().toLowerCase(Locale.ROOT) + "_"
                        + spec.archetype() + "_v" + v + ".mls";
                Path mlsPath = structuresDir.resolve(fileName);
                Files.write(mlsPath, built.bytes());
                String assetId = culture.key() + "/" + spec.archetype() + "/v" + v;
                String contentPath = "assets/livingmods/cultures/" + culture.key()
                        + "/structures/" + fileName;
                manifestEntries.add(toManifestJson(assetId, culture.key(), spec, built, contentPath, v));
                count++;
            }
        }
        Path manifest = cultureDir.resolve("structure_manifest.json");
        Files.writeString(manifest, "{\n  \"assets\": [\n    "
                + String.join(",\n    ", manifestEntries) + "\n  ]\n}\n", StandardCharsets.UTF_8);
        indexLines.add("assets/livingmods/cultures/" + culture.key() + "/structure_manifest.json");
        return count;
    }

    private int authorShared(List<String> indexLines) throws IOException {
        int count = 0;
        count += authorBucket("structures/shared", "*", sharedSpecs(), indexLines);
        count += authorBucket("structures/ruins", "*", ruinSpecs(), indexLines);
        count += authorBucket("structures/bandit", "*", banditSpecs(), indexLines);
        count += authorBucket("structures/infrastructure", "*", infraSpecs(), indexLines);
        return count;
    }

    private int authorBucket(String rel, String cultureKey, List<Spec> specs, List<String> indexLines)
            throws IOException {
        Path dir = resourcesRoot.resolve(rel);
        Files.createDirectories(dir);
        List<String> entries = new ArrayList<>();
        CultureDefinition avalon = new CultureRegistry().get("avalon").orElseThrow();
        int count = 0;
        for (Spec spec : specs) {
            for (int v = 0; v < spec.variants(); v++) {
                long seed = Hashing.mix(Hashing.hashString(rel + spec.archetype()), v);
                AuthoredBuilding built = build(avalon, spec, v, seed);
                // Neutral materials for shared
                if (!cultureKey.equals("*") || rel.contains("bandit") || rel.contains("ruins")
                        || rel.contains("infrastructure") || rel.contains("shared")) {
                    // rebuild already culture-neutral enough via oak/cobble defaults in shared palette override
                }
                String fileName = spec.role().name().toLowerCase(Locale.ROOT) + "_"
                        + spec.archetype() + "_v" + v + ".mls";
                Files.write(dir.resolve(fileName), built.bytes());
                String assetId = rel.replace('/', '_') + "/" + spec.archetype() + "/v" + v;
                String contentPath = "assets/livingmods/" + rel + "/" + fileName;
                entries.add(toManifestJson(assetId, cultureKey, spec, built, contentPath, v));
                count++;
            }
        }
        Path manifest = dir.resolve("structure_manifest.json");
        Files.writeString(manifest, "{\n  \"assets\": [\n    "
                + String.join(",\n    ", entries) + "\n  ]\n}\n", StandardCharsets.UTF_8);
        indexLines.add("assets/livingmods/" + rel + "/structure_manifest.json");
        return count;
    }

    private record AuthoredBuilding(
            byte[] bytes,
            int width,
            int depth,
            int height,
            int blockCount,
            String entranceFacing,
            int entranceX,
            int entranceY,
            int entranceZ,
            FoundationMode foundation,
            String contentHash
    ) {}

    private AuthoredBuilding build(CultureDefinition culture, Spec spec, int variant, long seed) throws IOException {
        Silhouette sil = silhouetteFor(culture, spec, variant, seed);
        Materials mat = materialsFor(culture, spec);
        List<String> palette = new ArrayList<>();
        Map<String, Integer> index = new LinkedHashMap<>();
        List<MlsStructureFormat.BlockPlacement> blocks = new ArrayList<>();

        int w = sil.width;
        int d = sil.depth;
        int h = sil.height;
        // Foundation
        for (int x = 0; x < w; x++) {
            for (int z = 0; z < d; z++) {
                put(blocks, index, palette, x, 0, z, mat.foundation);
            }
        }
        // Floor
        for (int x = 1; x < w - 1; x++) {
            for (int z = 1; z < d - 1; z++) {
                put(blocks, index, palette, x, 1, z, mat.floor);
            }
        }
        // Walls with culture silhouette cutouts
        for (int y = 1; y < h - 1; y++) {
            for (int x = 0; x < w; x++) {
                for (int z = 0; z < d; z++) {
                    boolean edge = x == 0 || z == 0 || x == w - 1 || z == d - 1;
                    if (!edge) continue;
                    if (sil.courtyard && x > sil.courtMinX && x < sil.courtMaxX
                            && z > sil.courtMinZ && z < sil.courtMaxZ) {
                        continue;
                    }
                    // Roundhouse approximation for celtara
                    if (sil.round && !inEllipse(x, z, w, d)) continue;
                    // Longhouse: thicker gable ends
                    String block = mat.wall;
                    if (sil.timberFrame && (x + z + y + variant) % 3 == 0) {
                        block = mat.accent;
                    }
                    if (sil.stoneBase && y <= 2) {
                        block = mat.foundation;
                    }
                    put(blocks, index, palette, x, y, z, block);
                }
            }
        }
        // Interior columns / partitions by role
        if (spec.role() == BuildingRole.TEMPLE || spec.role() == BuildingRole.PALACE
                || spec.role() == BuildingRole.CASTLE_KEEP) {
            for (int y = 1; y < h - 2; y++) {
                put(blocks, index, palette, w / 3, y, d / 3, mat.accent);
                put(blocks, index, palette, 2 * w / 3, y, 2 * d / 3, mat.accent);
            }
        }
        if (spec.role() == BuildingRole.SMITHY || "forge_hall".equals(spec.archetype())) {
            put(blocks, index, palette, w / 2, 1, d / 2, "minecraft:furnace[facing=south]");
            put(blocks, index, palette, w / 2, 1, d / 2 + 1, "minecraft:anvil");
        }
        if (spec.role() == BuildingRole.TAVERN) {
            put(blocks, index, palette, 2, 1, 2, "minecraft:barrel");
            put(blocks, index, palette, 3, 1, 2, "minecraft:crafting_table");
        }
        if (spec.role() == BuildingRole.SCHOOL) {
            put(blocks, index, palette, w / 2, 1, d / 2, "minecraft:lectern[facing=south]");
        }
        if (spec.role() == BuildingRole.CLINIC) {
            put(blocks, index, palette, 2, 1, 2, "minecraft:white_bed[facing=south,part=foot]");
        }
        // Door opening south facade
        int doorX = w / 2;
        int doorZ = sil.round ? d / 2 + (d / 4) : 0;
        if (doorZ >= d) doorZ = 0;
        for (int y = 1; y <= 2; y++) {
            removeAt(blocks, doorX, y, doorZ);
        }
        put(blocks, index, palette, doorX, 1, doorZ, mat.door);
        // Windows
        for (int i = 0; i < sil.windowSlots; i++) {
            int wx = 1 + (i * 3 + variant) % Math.max(1, w - 2);
            int wz = (i % 2 == 0) ? 0 : d - 1;
            if (sil.round && !inEllipse(wx, wz, w, d)) continue;
            removeAt(blocks, wx, 2, wz);
            put(blocks, index, palette, wx, 2, wz, mat.window);
        }
        // Roof
        placeRoof(blocks, index, palette, sil, mat, w, d, h);

        // Special: pagoda layers / dome / yurt / stepped temple
        if (sil.pagodaLayers > 0) {
            for (int layer = 0; layer < sil.pagodaLayers; layer++) {
                int inset = layer + 1;
                int ry = h - 1 + layer;
                for (int x = inset; x < w - inset; x++) {
                    for (int z = inset; z < d - inset; z++) {
                        boolean edge = x == inset || z == inset || x == w - inset - 1 || z == d - inset - 1;
                        if (edge) put(blocks, index, palette, x, ry, z, mat.roof);
                    }
                }
            }
            h = h + sil.pagodaLayers;
        }
        if (sil.dome) {
            int cx = w / 2;
            int cz = d / 2;
            int r = Math.min(w, d) / 3;
            for (int x = cx - r; x <= cx + r; x++) {
                for (int z = cz - r; z <= cz + r; z++) {
                    int dy = (int) Math.round(Math.sqrt(Math.max(0, r * r - (x - cx) * (x - cx) - (z - cz) * (z - cz))));
                    if (dy > 0 && x >= 0 && z >= 0 && x < w && z < d) {
                        put(blocks, index, palette, x, h - 2 + dy / 2, z, mat.accent);
                    }
                }
            }
        }
        if (sil.stepPyramid) {
            int tiers = Math.min(5, Math.min(w, d) / 4);
            for (int t = 0; t < tiers; t++) {
                int inset = t * 2;
                for (int x = inset; x < w - inset; x++) {
                    for (int z = inset; z < d - inset; z++) {
                        put(blocks, index, palette, x, h - 1 + t, z, mat.foundation);
                    }
                }
            }
            h = h + tiers;
        }
        if (sil.yurt) {
            // Clear rectangular roof and place ring
            int cx = w / 2;
            int cz = d / 2;
            int r = Math.min(w, d) / 2 - 1;
            for (int x = 0; x < w; x++) {
                for (int z = 0; z < d; z++) {
                    double dist = Math.hypot(x - cx, z - cz);
                    if (dist <= r && dist >= r - 1) {
                        for (int y = 1; y < h - 1; y++) {
                            put(blocks, index, palette, x, y, z, mat.wall);
                        }
                    }
                    if (dist <= r - 1) {
                        put(blocks, index, palette, x, h - 1, z, mat.roof);
                    }
                }
            }
        }

        int facing = 2; // south
        int maxY = 0;
        for (MlsStructureFormat.BlockPlacement b : blocks) {
            maxY = Math.max(maxY, b.y());
        }
        final int finalH = Math.max(h, maxY + 1);
        final int finalW = w;
        final int finalD = d;
        // Drop any accidental out-of-footprint blocks
        blocks.removeIf(b -> b.x() < 0 || b.z() < 0 || b.x() >= finalW || b.z() >= finalD
                || b.y() < 0 || b.y() >= finalH);
        int doorZClamped = Math.min(Math.max(0, doorZ), finalD - 1);
        MlsStructureFormat.StructureContent content = new MlsStructureFormat.StructureContent(
                finalW, finalH, finalD, doorX, 1, doorZClamped, facing, sil.foundation, palette, blocks);
        byte[] bytes = MlsStructureFormat.write(content);
        return new AuthoredBuilding(bytes, finalW, finalD, finalH, blocks.size(), "south", doorX, 1, doorZClamped,
                sil.foundation, MlsStructureFormat.contentHash(bytes));
    }

    private void placeRoof(
            List<MlsStructureFormat.BlockPlacement> blocks,
            Map<String, Integer> index,
            List<String> palette,
            Silhouette sil,
            Materials mat,
            int w, int d, int h
    ) {
        int roofY = h - 1;
        if (sil.flatRoof) {
            for (int x = 0; x < w; x++) {
                for (int z = 0; z < d; z++) {
                    put(blocks, index, palette, x, roofY, z, mat.roof);
                }
            }
            return;
        }
        if (sil.steepGable) {
            int peak = Math.max(2, Math.min(w, d) / 2);
            for (int layer = 0; layer < peak; layer++) {
                for (int x = layer; x < w - layer; x++) {
                    for (int z = 0; z < d; z++) {
                        put(blocks, index, palette, x, roofY + layer, z, mat.roof);
                    }
                }
            }
            return;
        }
        // Default hip-ish roof
        int peak = Math.max(1, Math.min(w, d) / 3);
        for (int layer = 0; layer < peak; layer++) {
            for (int x = layer; x < w - layer; x++) {
                for (int z = layer; z < d - layer; z++) {
                    boolean edge = x == layer || z == layer || x == w - layer - 1 || z == d - layer - 1
                            || layer == peak - 1;
                    if (edge) put(blocks, index, palette, x, roofY + layer, z, mat.roof);
                }
            }
        }
    }

    private static boolean inEllipse(int x, int z, int w, int d) {
        double cx = (w - 1) / 2.0;
        double cz = (d - 1) / 2.0;
        double rx = w / 2.0;
        double rz = d / 2.0;
        double nx = (x - cx) / rx;
        double nz = (z - cz) / rz;
        return nx * nx + nz * nz <= 1.05;
    }

    private static void put(
            List<MlsStructureFormat.BlockPlacement> blocks,
            Map<String, Integer> index,
            List<String> palette,
            int x, int y, int z,
            String block
    ) {
        removeAt(blocks, x, y, z);
        int pi = index.computeIfAbsent(block, b -> {
            palette.add(b);
            return palette.size() - 1;
        });
        blocks.add(new MlsStructureFormat.BlockPlacement(x, y, z, pi));
    }

    private static void removeAt(List<MlsStructureFormat.BlockPlacement> blocks, int x, int y, int z) {
        blocks.removeIf(b -> b.x() == x && b.y() == y && b.z() == z);
    }

    private record Silhouette(
            int width, int depth, int height,
            boolean steepGable, boolean flatRoof, boolean courtyard,
            boolean round, boolean timberFrame, boolean stoneBase,
            boolean dome, boolean stepPyramid, boolean yurt,
            int pagodaLayers, int windowSlots,
            int courtMinX, int courtMaxX, int courtMinZ, int courtMaxZ,
            FoundationMode foundation
    ) {}

    private record Materials(String wall, String floor, String roof, String foundation, String accent, String door, String window) {}

    private Silhouette silhouetteFor(CultureDefinition culture, Spec spec, int variant, long seed) {
        String key = culture.key();
        int baseW = switch (spec.role()) {
            case WELL, WAYSTONE, MARKET_STALL -> 5 + (variant % 2);
            case HOUSE, FARMHOUSE -> 8 + (variant % 4);
            case TOWNHOUSE, SHOP, GUARDHOUSE, CLINIC -> 9 + (variant % 3);
            case TAVERN, SMITHY, WORKSHOP, BARN, SCHOOL -> 11 + (variant % 4);
            case TEMPLE, MARKET_HALL, WAREHOUSE, BARRACKS, MANOR -> 14 + (variant % 5);
            case PALACE, CASTLE_KEEP -> 20 + (variant % 6);
            case TOWER, GATEHOUSE -> 6 + (variant % 3);
            case DOCK, BRIDGE -> 12 + (variant % 4);
            default -> 10 + (variant % 3);
        };
        int baseD = baseW;
        boolean steep = false, flat = false, court = false, round = false, timber = false, stoneBase = false;
        boolean dome = false, step = false, yurt = false;
        int pagoda = 0;
        FoundationMode foundation = FoundationMode.CUT_AND_FILL;
        switch (key) {
            case "nordheim" -> {
                steep = true;
                stoneBase = true;
                baseW = Math.max(baseW, 12);
                baseD = Math.max(7, baseW / 2 + variant % 3); // elongated longhouse
                timber = true;
            }
            case "avalon" -> {
                steep = true;
                timber = true;
                stoneBase = true;
            }
            case "sahari" -> {
                flat = true;
                court = true;
                dome = spec.role() == BuildingRole.TEMPLE || spec.role() == BuildingRole.PALACE;
                foundation = FoundationMode.FLAT;
            }
            case "yamato" -> {
                timber = true;
                pagoda = (spec.role() == BuildingRole.TEMPLE || "pagoda".equals(spec.archetype())) ? 2 + variant % 2 : 0;
                baseW += 1;
            }
            case "helvetia" -> {
                steep = true;
                stoneBase = true;
                timber = true;
                foundation = FoundationMode.HILLSIDE;
            }
            case "amaru" -> {
                flat = true;
                step = spec.role() == BuildingRole.TEMPLE || "step_pyramid".equals(spec.archetype());
                foundation = FoundationMode.TERRACED;
            }
            case "varangian" -> {
                steep = true;
                timber = true;
                stoneBase = true;
                baseW = Math.max(baseW, 11);
            }
            case "celtara" -> {
                round = spec.role() == BuildingRole.HOUSE || spec.role() == BuildingRole.FARMHOUSE
                        || "roundhouse".equals(spec.archetype()) || "clan_hall".equals(spec.archetype());
                timber = true;
            }
            case "qin" -> {
                court = true;
                pagoda = (spec.role() == BuildingRole.TEMPLE || "pagoda".equals(spec.archetype())) ? 3 : 0;
                timber = true;
            }
            case "atlantea" -> {
                flat = true;
                dome = spec.role() == BuildingRole.TEMPLE || spec.role() == BuildingRole.PALACE;
                foundation = FoundationMode.WATERFRONT;
            }
            case "steppeborn" -> {
                yurt = spec.role() == BuildingRole.HOUSE || "yurt".equals(spec.archetype())
                        || "ger".equals(spec.archetype());
                flat = !yurt;
                foundation = FoundationMode.FLAT;
            }
            case "ironvale" -> {
                flat = true;
                stoneBase = true;
                foundation = variant % 2 == 0 ? FoundationMode.UNDERGROUND : FoundationMode.CUT_AND_FILL;
            }
            case "wizard_trees" -> {
                foundation = FoundationMode.UNDERGROUND;
                timber = true;
            }
            default -> steep = true;
        }
        int h = switch (spec.role()) {
            case TOWER -> 12 + variant;
            case PALACE, CASTLE_KEEP -> 10 + variant % 4;
            case TEMPLE -> 9 + variant % 3;
            default -> 6 + variant % 3;
        };
        int courtMinX = court ? baseW / 4 : 0;
        int courtMaxX = court ? 3 * baseW / 4 : 0;
        int courtMinZ = court ? baseD / 4 : 0;
        int courtMaxZ = court ? 3 * baseD / 4 : 0;
        return new Silhouette(baseW, baseD, h, steep, flat, court, round, timber, stoneBase,
                dome, step, yurt, pagoda, 3 + variant % 3,
                courtMinX, courtMaxX, courtMinZ, courtMaxZ, foundation);
    }

    private Materials materialsFor(CultureDefinition culture, Spec spec) {
        var a = culture.architecture();
        String wall = mc(a.primaryBlock());
        String floor = mc(a.secondaryBlock());
        String roof = mc(a.roofBlock());
        String foundation = mc(a.secondaryBlock());
        String accent = mc(a.accentBlock());
        String door = "minecraft:oak_door[facing=south,half=lower,hinge=left,open=false]";
        if (wall.contains("spruce")) door = "minecraft:spruce_door[facing=south,half=lower,hinge=left,open=false]";
        if (wall.contains("dark_oak")) door = "minecraft:dark_oak_door[facing=south,half=lower,hinge=left,open=false]";
        if (wall.contains("cherry")) door = "minecraft:cherry_door[facing=south,half=lower,hinge=left,open=false]";
        if (wall.contains("jungle")) door = "minecraft:jungle_door[facing=south,half=lower,hinge=left,open=false]";
        if (wall.contains("birch")) door = "minecraft:birch_door[facing=south,half=lower,hinge=left,open=false]";
        String window = "minecraft:glass_pane";
        if (culture.underground()) window = "minecraft:amethyst_block";
        return new Materials(wall, floor, roof, foundation, accent, door, window);
    }

    private static String mc(String block) {
        if (block == null || block.isBlank()) return "minecraft:oak_planks";
        return block.contains(":") ? block : "minecraft:" + block;
    }

    private String toManifestJson(String assetId, String cultureKey, Spec spec, AuthoredBuilding built,
                                  String contentPath, int variant) {
        StructureSizeClass size = StructureSizeClass.fromDimensions(built.width(), built.depth());
        boolean unique = spec.uniquePerSettlement()
                || spec.role() == BuildingRole.PALACE
                || spec.role() == BuildingRole.CASTLE_KEEP;
        return "{\n"
                + "      \"assetId\": \"" + assetId + "\",\n"
                + "      \"source\": \"minelife_authored\",\n"
                + "      \"sourceId\": \"" + assetId + "\",\n"
                + "      \"sourceUrl\": \"\",\n"
                + "      \"sourceAuthor\": \"MineLife\",\n"
                + "      \"sourceTitle\": \"" + spec.archetype() + "\",\n"
                + "      \"cultureKey\": \"" + cultureKey + "\",\n"
                + "      \"canonicalRole\": \"" + spec.role().name() + "\",\n"
                + "      \"archetype\": \"" + spec.archetype() + "\",\n"
                + "      \"variant\": \"v" + variant + "\",\n"
                + "      \"settlementTierMin\": \"" + spec.minTier().name() + "\",\n"
                + "      \"settlementTierMax\": \"" + spec.maxTier().name() + "\",\n"
                + "      \"wealthClass\": \"" + spec.wealth().name() + "\",\n"
                + "      \"sizeClass\": \"" + size.name() + "\",\n"
                + "      \"width\": " + built.width() + ",\n"
                + "      \"depth\": " + built.depth() + ",\n"
                + "      \"height\": " + built.height() + ",\n"
                + "      \"blockCount\": " + built.blockCount() + ",\n"
                + "      \"entranceFacing\": \"" + built.entranceFacing() + "\",\n"
                + "      \"entranceX\": " + built.entranceX() + ",\n"
                + "      \"entranceY\": " + built.entranceY() + ",\n"
                + "      \"entranceZ\": " + built.entranceZ() + ",\n"
                + "      \"entranceConfidence\": 0.9,\n"
                + "      \"allowedRotations\": [0, 90, 180, 270],\n"
                + "      \"mirrorAllowed\": true,\n"
                + "      \"weight\": 1.0,\n"
                + "      \"tags\": [\"" + spec.archetype() + "\"],\n"
                + "      \"biomeTags\": [],\n"
                + "      \"coastalRequired\": false,\n"
                + "      \"underground\": " + spec.underground() + ",\n"
                + "      \"defensive\": " + spec.defensive() + ",\n"
                + "      \"uniquePerSettlement\": " + unique + ",\n"
                + "      \"uniquePerKingdom\": " + (spec.role() == BuildingRole.PALACE) + ",\n"
                + "      \"requiredClearance\": 1,\n"
                + "      \"terrainTolerance\": 3,\n"
                + "      \"foundationMode\": \"" + built.foundation().name() + "\",\n"
                + "      \"anchor\": \"center\",\n"
                + "      \"interiorClass\": \"" + InteriorClass.FULL_INTERIOR.name() + "\",\n"
                + "      \"hasInterior\": true,\n"
                + "      \"occupationCapacityHint\": 0,\n"
                + "      \"residentialSlotsHint\": 0,\n"
                + "      \"workSlotsHint\": 0,\n"
                + "      \"contentPath\": \"" + contentPath + "\",\n"
                + "      \"sourceHash\": \"" + built.contentHash() + "\",\n"
                + "      \"contentHash\": \"" + built.contentHash() + "\",\n"
                + "      \"importRevision\": " + StructureCatalogVersions.CONTENT_REVISION + ",\n"
                + "      \"status\": \"" + ImportStatus.AUTHORED.name() + "\",\n"
                + "      \"sanitized\": false\n"
                + "    }";
    }

    private void writeCultureJson(CultureDefinition c, Path path) throws IOException {
        String json = "{\n  \"key\": \"" + c.key() + "\",\n  \"displayName\": \"" + c.displayName()
                + "\",\n  \"underground\": " + c.underground() + ",\n  \"roofStyle\": \""
                + c.architecture().roofStyle().name() + "\",\n  \"layoutStyle\": \""
                + c.architecture().layoutStyle().name() + "\"\n}\n";
        Files.writeString(path, json, StandardCharsets.UTF_8);
    }

    private static List<Spec> surfaceSpecs(String culture) {
        List<Spec> list = new ArrayList<>();
        // Residential ~28
        add(list, BuildingRole.HOUSE, "poor_hut", SettlementTier.HAMLET, SettlementTier.VILLAGE, WealthClass.POOR, 3);
        add(list, BuildingRole.HOUSE, cultureHouse(culture), SettlementTier.HAMLET, SettlementTier.CITY, WealthClass.COMMON, 6);
        add(list, BuildingRole.HOUSE, "medium_house", SettlementTier.VILLAGE, SettlementTier.CITY, WealthClass.COMMON, 4);
        add(list, BuildingRole.HOUSE, "artisan_house", SettlementTier.TOWN, SettlementTier.CITY, WealthClass.COMFORTABLE, 3);
        add(list, BuildingRole.HOUSE, "merchant_house", SettlementTier.TOWN, SettlementTier.CAPITAL, WealthClass.COMFORTABLE, 3);
        add(list, BuildingRole.TOWNHOUSE, "townhouse", SettlementTier.TOWN, SettlementTier.CAPITAL, WealthClass.COMFORTABLE, 4);
        add(list, BuildingRole.MANOR, cultureManor(culture), SettlementTier.CITY, SettlementTier.CAPITAL, WealthClass.WEALTHY, 3);
        add(list, BuildingRole.PALACE, culturePalace(culture), SettlementTier.CAPITAL, SettlementTier.CAPITAL, WealthClass.ROYAL, 2);
        // Agriculture ~10
        add(list, BuildingRole.FARMHOUSE, "farmhouse", SettlementTier.HAMLET, SettlementTier.TOWN, WealthClass.COMMON, 3);
        add(list, BuildingRole.BARN, "barn", SettlementTier.HAMLET, SettlementTier.TOWN, WealthClass.POOR, 3);
        add(list, BuildingRole.MILL, "mill", SettlementTier.VILLAGE, SettlementTier.CITY, WealthClass.COMMON, 2);
        add(list, BuildingRole.WAREHOUSE, "granary", SettlementTier.TOWN, SettlementTier.CAPITAL, WealthClass.COMMON, 2);
        // Commerce / craft ~18
        add(list, BuildingRole.MARKET_STALL, "market_stall", SettlementTier.VILLAGE, SettlementTier.CITY, WealthClass.POOR, 3);
        add(list, BuildingRole.MARKET_HALL, "market_hall", SettlementTier.TOWN, SettlementTier.CAPITAL, WealthClass.COMFORTABLE, 2);
        add(list, BuildingRole.SHOP, cultureShop(culture), SettlementTier.TOWN, SettlementTier.CITY, WealthClass.COMMON, 3);
        add(list, BuildingRole.TAVERN, "tavern", SettlementTier.VILLAGE, SettlementTier.CAPITAL, WealthClass.COMMON, 3);
        add(list, BuildingRole.SMITHY, "blacksmith", SettlementTier.VILLAGE, SettlementTier.CITY, WealthClass.COMMON, 2);
        add(list, BuildingRole.WORKSHOP, "workshop", SettlementTier.VILLAGE, SettlementTier.CITY, WealthClass.COMMON, 3);
        add(list, BuildingRole.WAREHOUSE, "warehouse", SettlementTier.TOWN, SettlementTier.CAPITAL, WealthClass.COMFORTABLE, 2);
        // Civic / religious ~14
        add(list, BuildingRole.TEMPLE, cultureTemple(culture), SettlementTier.VILLAGE, SettlementTier.CAPITAL, WealthClass.COMFORTABLE, 3);
        add(list, BuildingRole.SCHOOL, "school", SettlementTier.TOWN, SettlementTier.CAPITAL, WealthClass.COMFORTABLE, 2);
        add(list, BuildingRole.CLINIC, "clinic", SettlementTier.TOWN, SettlementTier.CITY, WealthClass.COMMON, 2);
        add(list, BuildingRole.WELL, "well", SettlementTier.HAMLET, SettlementTier.CITY, WealthClass.POOR, 2);
        add(list, BuildingRole.MONUMENT, "monument", SettlementTier.CITY, SettlementTier.CAPITAL, WealthClass.NOBLE, 2);
        add(list, BuildingRole.PRISON, "prison", SettlementTier.CITY, SettlementTier.CAPITAL, WealthClass.COMMON, 1);
        // Military ~14
        add(list, BuildingRole.GUARDHOUSE, "guardhouse", SettlementTier.VILLAGE, SettlementTier.CITY, WealthClass.COMMON, 2);
        add(list, BuildingRole.BARRACKS, "barracks", SettlementTier.TOWN, SettlementTier.CAPITAL, WealthClass.COMMON, 2);
        add(list, BuildingRole.TOWER, "watchtower", SettlementTier.TOWN, SettlementTier.CAPITAL, WealthClass.COMMON, 3);
        add(list, BuildingRole.GATEHOUSE, "gatehouse", SettlementTier.TOWN, SettlementTier.CAPITAL, WealthClass.COMMON, 2);
        add(list, BuildingRole.CASTLE_KEEP, cultureCastle(culture), SettlementTier.CITY, SettlementTier.CAPITAL, WealthClass.NOBLE, 2);
        add(list, BuildingRole.WALL_SEGMENT, cultureWall(culture), SettlementTier.TOWN, SettlementTier.CAPITAL, WealthClass.COMMON, 3);
        // Infrastructure / special ~8
        add(list, BuildingRole.DOCK, "dock", SettlementTier.TOWN, SettlementTier.CAPITAL, WealthClass.COMMON, 2);
        add(list, BuildingRole.BRIDGE, cultureBridge(culture), SettlementTier.VILLAGE, SettlementTier.CAPITAL, WealthClass.COMMON, 2);
        add(list, BuildingRole.WAYSTONE, "waystation", SettlementTier.HAMLET, SettlementTier.TOWN, WealthClass.POOR, 2);
        add(list, BuildingRole.RUIN, "ruin_fragment", SettlementTier.HAMLET, SettlementTier.CAPITAL, WealthClass.POOR, 2);
        // Culture specials
        addCultureSpecials(list, culture);
        return list;
    }

    private static void addCultureSpecials(List<Spec> list, String culture) {
        switch (culture) {
            case "nordheim" -> {
                add(list, BuildingRole.HOUSE, "longhouse", SettlementTier.VILLAGE, SettlementTier.CAPITAL, WealthClass.COMMON, 3);
                add(list, BuildingRole.TEMPLE, "stave_temple", SettlementTier.TOWN, SettlementTier.CAPITAL, WealthClass.COMFORTABLE, 2);
            }
            case "sahari" -> {
                add(list, BuildingRole.HOUSE, "courtyard_house", SettlementTier.VILLAGE, SettlementTier.CITY, WealthClass.COMMON, 3);
                add(list, BuildingRole.WAREHOUSE, "caravanserai", SettlementTier.TOWN, SettlementTier.CAPITAL, WealthClass.COMFORTABLE, 2);
            }
            case "yamato" -> {
                add(list, BuildingRole.HOUSE, "minka", SettlementTier.HAMLET, SettlementTier.TOWN, WealthClass.COMMON, 3);
                add(list, BuildingRole.HOUSE, "machiya", SettlementTier.TOWN, SettlementTier.CITY, WealthClass.COMFORTABLE, 2);
                add(list, BuildingRole.TEMPLE, "pagoda", SettlementTier.TOWN, SettlementTier.CAPITAL, WealthClass.COMFORTABLE, 2);
            }
            case "helvetia" -> {
                add(list, BuildingRole.HOUSE, "chalet", SettlementTier.HAMLET, SettlementTier.TOWN, WealthClass.COMMON, 3);
                add(list, BuildingRole.TAVERN, "mountain_inn", SettlementTier.VILLAGE, SettlementTier.CITY, WealthClass.COMMON, 2);
            }
            case "amaru" -> {
                add(list, BuildingRole.TEMPLE, "step_pyramid", SettlementTier.TOWN, SettlementTier.CAPITAL, WealthClass.NOBLE, 2);
                add(list, BuildingRole.HOUSE, "terrace_house", SettlementTier.VILLAGE, SettlementTier.CITY, WealthClass.COMMON, 3);
            }
            case "varangian" -> {
                add(list, BuildingRole.WAREHOUSE, "trading_hall", SettlementTier.TOWN, SettlementTier.CAPITAL, WealthClass.COMFORTABLE, 2);
                add(list, BuildingRole.HOUSE, "log_house", SettlementTier.HAMLET, SettlementTier.TOWN, WealthClass.COMMON, 3);
            }
            case "celtara" -> {
                add(list, BuildingRole.HOUSE, "roundhouse", SettlementTier.HAMLET, SettlementTier.TOWN, WealthClass.COMMON, 4);
                add(list, BuildingRole.TEMPLE, "druid_grove", SettlementTier.VILLAGE, SettlementTier.CITY, WealthClass.COMMON, 2);
            }
            case "qin" -> {
                add(list, BuildingRole.HOUSE, "siheyuan", SettlementTier.TOWN, SettlementTier.CITY, WealthClass.COMFORTABLE, 3);
                add(list, BuildingRole.TEMPLE, "pagoda", SettlementTier.TOWN, SettlementTier.CAPITAL, WealthClass.COMFORTABLE, 2);
            }
            case "atlantea" -> {
                add(list, BuildingRole.HOUSE, "coastal_villa", SettlementTier.TOWN, SettlementTier.CAPITAL, WealthClass.WEALTHY, 2);
                add(list, BuildingRole.TOWER, "lighthouse", SettlementTier.TOWN, SettlementTier.CAPITAL, WealthClass.COMMON, 2);
            }
            case "steppeborn" -> {
                add(list, BuildingRole.HOUSE, "yurt", SettlementTier.HAMLET, SettlementTier.CITY, WealthClass.COMMON, 4);
                add(list, BuildingRole.HOUSE, "ger", SettlementTier.VILLAGE, SettlementTier.CITY, WealthClass.COMMON, 3);
                add(list, BuildingRole.PALACE, "khan_hall", SettlementTier.CITY, SettlementTier.CAPITAL, WealthClass.ROYAL, 2);
            }
            case "ironvale" -> {
                add(list, BuildingRole.SMITHY, "forge_hall", SettlementTier.TOWN, SettlementTier.CAPITAL, WealthClass.COMMON, 3);
                add(list, BuildingRole.MINE_ENTRANCE, "mine_hall", SettlementTier.HAMLET, SettlementTier.CITY, WealthClass.POOR, 3);
                add(list, BuildingRole.HOUSE, "stout_house", SettlementTier.HAMLET, SettlementTier.CITY, WealthClass.COMMON, 3);
            }
            default -> {
            }
        }
    }

    private static List<Spec> wizardSpecs() {
        List<Spec> list = new ArrayList<>();
        addU(list, BuildingRole.HOUSE, "root_dwelling", 6);
        addU(list, BuildingRole.HOUSE, "fungal_dwelling", 5);
        addU(list, BuildingRole.MANOR, "root_hall", 3);
        addU(list, BuildingRole.SCHOOL, "arcane_library", 3);
        addU(list, BuildingRole.WORKSHOP, "alchemy_chamber", 3);
        addU(list, BuildingRole.TEMPLE, "ritual_chamber", 3);
        addU(list, BuildingRole.WAREHOUSE, "fungal_farm", 3);
        addU(list, BuildingRole.WAREHOUSE, "arcane_vault", 2);
        addU(list, BuildingRole.GATEHOUSE, "portal_chamber", 2);
        addU(list, BuildingRole.PALACE, "council_cavern", 2);
        addU(list, BuildingRole.GUARDHOUSE, "guard_cavern", 3);
        addU(list, BuildingRole.MARKET_HALL, "cavern_market", 3);
        addU(list, BuildingRole.CLINIC, "spore_clinic", 2);
        addU(list, BuildingRole.TOWER, "crystal_spire", 3);
        addU(list, BuildingRole.WELL, "glow_well", 2);
        addU(list, BuildingRole.MONUMENT, "mycel_monument", 2);
        addU(list, BuildingRole.BARRACKS, "root_barracks", 2);
        addU(list, BuildingRole.TAVERN, "glowcap_tavern", 2);
        return list;
    }

    private static List<Spec> sharedSpecs() {
        List<Spec> list = new ArrayList<>();
        add(list, BuildingRole.WELL, "shared_well", SettlementTier.HAMLET, SettlementTier.CITY, WealthClass.POOR, 4);
        add(list, BuildingRole.WAYSTONE, "road_shrine", SettlementTier.HAMLET, SettlementTier.TOWN, WealthClass.POOR, 3);
        add(list, BuildingRole.MARKET_STALL, "generic_stall", SettlementTier.VILLAGE, SettlementTier.CITY, WealthClass.POOR, 4);
        return list;
    }

    private static List<Spec> ruinSpecs() {
        List<Spec> list = new ArrayList<>();
        add(list, BuildingRole.RUIN, "abandoned_house", SettlementTier.HAMLET, SettlementTier.CAPITAL, WealthClass.POOR, 6);
        add(list, BuildingRole.RUIN, "ruined_tower", SettlementTier.HAMLET, SettlementTier.CAPITAL, WealthClass.POOR, 4);
        add(list, BuildingRole.RUIN, "ruined_temple", SettlementTier.VILLAGE, SettlementTier.CAPITAL, WealthClass.POOR, 3);
        add(list, BuildingRole.RUIN, "ruined_fort", SettlementTier.TOWN, SettlementTier.CAPITAL, WealthClass.POOR, 3);
        return list;
    }

    private static List<Spec> banditSpecs() {
        List<Spec> list = new ArrayList<>();
        add(list, BuildingRole.HOUSE, "bandit_hut", SettlementTier.HAMLET, SettlementTier.TOWN, WealthClass.POOR, 4);
        add(list, BuildingRole.TOWER, "bandit_tower", SettlementTier.HAMLET, SettlementTier.TOWN, WealthClass.POOR, 3);
        add(list, BuildingRole.WAREHOUSE, "stolen_cache", SettlementTier.HAMLET, SettlementTier.TOWN, WealthClass.POOR, 3);
        add(list, BuildingRole.WALL_SEGMENT, "bandit_palisade", SettlementTier.HAMLET, SettlementTier.TOWN, WealthClass.POOR, 3);
        return list;
    }

    private static List<Spec> infraSpecs() {
        List<Spec> list = new ArrayList<>();
        add(list, BuildingRole.BRIDGE, "footbridge", SettlementTier.HAMLET, SettlementTier.CITY, WealthClass.POOR, 3);
        add(list, BuildingRole.BRIDGE, "stone_bridge", SettlementTier.TOWN, SettlementTier.CAPITAL, WealthClass.COMMON, 3);
        add(list, BuildingRole.BRIDGE, "wooden_bridge", SettlementTier.VILLAGE, SettlementTier.CITY, WealthClass.COMMON, 3);
        add(list, BuildingRole.DOCK, "pier", SettlementTier.TOWN, SettlementTier.CAPITAL, WealthClass.COMMON, 3);
        add(list, BuildingRole.WAYSTONE, "checkpoint", SettlementTier.VILLAGE, SettlementTier.CITY, WealthClass.COMMON, 3);
        add(list, BuildingRole.WAREHOUSE, "caravan_stop", SettlementTier.VILLAGE, SettlementTier.CITY, WealthClass.COMMON, 3);
        return list;
    }

    private static void add(List<Spec> list, BuildingRole role, String archetype,
                            SettlementTier min, SettlementTier max, WealthClass wealth, int variants) {
        list.add(new Spec(role, archetype, min, max, wealth, variants, false, false, false));
    }

    private static void addU(List<Spec> list, BuildingRole role, String archetype, int variants) {
        list.add(new Spec(role, archetype, SettlementTier.HAMLET, SettlementTier.CAPITAL,
                WealthClass.COMMON, variants, false, false, true));
    }

    private static String cultureHouse(String c) {
        return switch (c) {
            case "nordheim" -> "timber_house";
            case "sahari" -> "sandstone_house";
            case "yamato" -> "minka";
            case "helvetia" -> "chalet";
            case "amaru" -> "jungle_house";
            case "varangian" -> "log_house";
            case "celtara" -> "roundhouse";
            case "qin" -> "courtyard_house";
            case "atlantea" -> "harbor_house";
            case "steppeborn" -> "yurt";
            case "ironvale" -> "stout_house";
            default -> "common_house";
        };
    }

    private static String cultureManor(String c) {
        return switch (c) {
            case "avalon" -> "knight_manor";
            case "qin" -> "magistrate_hall";
            case "sahari" -> "oasis_manor";
            default -> "manor";
        };
    }

    private static String culturePalace(String c) {
        return switch (c) {
            case "sahari" -> "desert_palace";
            case "yamato" -> "castle_palace";
            case "qin" -> "imperial_hall";
            case "steppeborn" -> "khan_hall";
            case "atlantea" -> "sea_palace";
            default -> "palace";
        };
    }

    private static String cultureTemple(String c) {
        return switch (c) {
            case "nordheim" -> "stave_temple";
            case "avalon" -> "church";
            case "sahari" -> "domed_temple";
            case "yamato", "qin" -> "pagoda";
            case "amaru" -> "step_pyramid";
            case "celtara" -> "druid_grove";
            case "atlantea" -> "sea_temple";
            default -> "temple";
        };
    }

    private static String cultureCastle(String c) {
        return switch (c) {
            case "yamato" -> "japanese_castle";
            case "ironvale" -> "mountain_fortress";
            case "helvetia" -> "alpine_fort";
            case "varangian" -> "wooden_fort";
            case "celtara" -> "hillfort";
            default -> "castle";
        };
    }

    private static String cultureWall(String c) {
        return switch (c) {
            case "nordheim", "celtara", "varangian" -> "palisade_segment";
            case "sahari" -> "sandstone_wall";
            case "ironvale" -> "deepslate_wall";
            case "qin" -> "monumental_wall";
            default -> "stone_wall_segment";
        };
    }

    private static String cultureBridge(String c) {
        return switch (c) {
            case "yamato" -> "arched_bridge";
            case "helvetia" -> "stone_bridge";
            case "nordheim" -> "timber_bridge";
            default -> "bridge";
        };
    }

    private static String cultureShop(String c) {
        return switch (c) {
            case "sahari" -> "bazaar_shop";
            case "qin" -> "market_house";
            default -> "shop";
        };
    }
}
