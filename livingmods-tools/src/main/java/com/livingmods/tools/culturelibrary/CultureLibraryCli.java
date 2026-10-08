package com.livingmods.tools.culturelibrary;

import com.livingmods.worldgen.structure.StructureAsset;
import com.livingmods.worldgen.structure.StructureCatalog;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * CLI for culture structure library operations.
 *
 * <pre>
 * culture-library author [--out path]
 * culture-library search --query "viking house" [--limit 20]
 * culture-library import --culture avalon [--plan path]
 * culture-library validate [--root path]
 * culture-library report [--root path]
 * culture-library resume
 * culture-library names [--out path]
 * </pre>
 */
public final class CultureLibraryCli {
    private CultureLibraryCli() {}

    public static void run(String[] args) throws Exception {
        if (args.length == 0) {
            usage();
            return;
        }
        String cmd = args[0];
        Path repoRoot = Path.of(".").toAbsolutePath().normalize();
        // Gradle :livingmods-tools:run often starts with cwd under the tools module.
        if (repoRoot.getFileName() != null && repoRoot.getFileName().toString().equals("livingmods-tools")) {
            repoRoot = repoRoot.getParent();
        }
        if (!Files.isDirectory(repoRoot.resolve("livingmods-worldgen"))) {
            Path walk = repoRoot;
            for (int i = 0; i < 4 && walk != null; i++) {
                if (Files.isDirectory(walk.resolve("livingmods-worldgen"))) {
                    repoRoot = walk;
                    break;
                }
                walk = walk.getParent();
            }
        }
        // Catalog must live in worldgen resources so sidecar/worldgen planning can load it
        // (NeoForge Jar-in-Jars worldgen, so materialization sees the same assets).
        Path resources = repoRoot.resolve("livingmods-worldgen/src/main/resources/assets/livingmods");
        Path cache = repoRoot.resolve(".livingmods-cache/buildpaste");
        Path importState = cache.resolve("import-state.json");

        switch (cmd) {
            case "author" -> {
                Path out = flagPath(args, "--out", resources);
                CultureStructureAuthor author = new CultureStructureAuthor(out);
                int n = author.authorAll();
                System.out.println("authored_assets=" + n + " root=" + out);
            }
            case "search" -> {
                String query = flag(args, "--query", "medieval house");
                int limit = Integer.parseInt(flag(args, "--limit", "20"));
                BuildPasteClient client = new BuildPasteClient(cache);
                List<BuildPasteClient.Candidate> found = client.search(query, limit);
                if (found.isEmpty()) {
                    found = client.searchHtmlFallback(query, limit);
                }
                System.out.println("query=" + query + " found=" + found.size());
                for (BuildPasteClient.Candidate c : found) {
                    System.out.printf("  %s  %s  premium=%s  %s%n", c.sourceId(), c.title(), c.premium(), c.url());
                }
            }
            case "import" -> {
                String culture = flag(args, "--culture", "avalon");
                Path plan = flagPath(args, "--plan",
                        repoRoot.resolve("livingmods-tools/src/main/resources/culture-library/query_plans/"
                                + culture + ".json"));
                ImportPipeline pipeline = new ImportPipeline(cache, importState, resources);
                ImportPipeline.Result result = pipeline.importCulture(culture, plan);
                System.out.println(result.summary());
            }
            case "validate" -> {
                Path root = flagPath(args, "--root", resources);
                StructureCatalog catalog = StructureCatalog.loadFromDirectory(root);
                var validation = com.livingmods.worldgen.structure.CatalogValidation.validate(catalog.all());
                int bad = validation.errors().size();
                for (String err : validation.errors()) {
                    System.out.println("ERROR " + err);
                }
                for (String warn : validation.warnings()) {
                    System.out.println("WARN " + warn);
                }
                for (StructureAsset a : catalog.all()) {
                    if (a.contentPath().isBlank()) {
                        System.out.println("MISSING_CONTENT_PATH " + a.assetId());
                        bad++;
                        continue;
                    }
                    var content = catalog.loadContent(a.assetId());
                    if (content.isEmpty()) {
                        System.out.println("MISSING_CONTENT " + a.assetId() + " path=" + a.contentPath());
                        bad++;
                    } else if (!a.geometryHash().isBlank()) {
                        String gh = com.livingmods.worldgen.structure.MlsStructureFormat.geometryHash(content.get());
                        if (!gh.equals(a.geometryHash())) {
                            System.out.println("GEOMETRY_HASH_MISMATCH " + a.assetId());
                            bad++;
                        }
                    }
                }
                System.out.println("validated assets=" + catalog.size()
                        + " uniqueGeometry=" + validation.uniqueGeometryCount()
                        + " problems=" + bad);
            }
            case "import-local" -> {
                Path dir = flagPath(args, "--dir", cache.resolve("local-inbox"));
                String culture = flag(args, "--culture", "avalon");
                ImportPipeline pipeline = new ImportPipeline(cache, importState, resources);
                System.out.println(pipeline.importLocalDirectory(culture, dir).summary());
            }
            case "report" -> {
                Path root = flagPath(args, "--root", resources);
                StructureCatalog catalog = StructureCatalog.loadFromDirectory(root);
                Path reportDir = repoRoot.resolve("livingmods-tools/build/culture-library-reports");
                Files.createDirectories(reportDir);
                String coverage = CoverageReport.write(catalog, reportDir.resolve("coverage.md"),
                        reportDir.resolve("coverage.json"));
                String distinct = DistinctnessReport.write(catalog, reportDir.resolve("distinctness.md"));
                System.out.println(coverage);
                System.out.println(distinct);
                System.out.println("reports=" + reportDir);
            }
            case "resume" -> {
                ImportPipeline pipeline = new ImportPipeline(cache, importState, resources);
                System.out.println(pipeline.resume().summary());
            }
            case "names" -> {
                Path out = flagPath(args, "--out", resources.resolve("cultures"));
                int n = NamingDataAuthor.writeAll(out);
                System.out.println("naming_files=" + n + " root=" + out);
            }
            default -> usage();
        }
    }

    private static void usage() {
        System.out.println("culture-library commands:");
        System.out.println("  author [--out path]");
        System.out.println("  search --query TEXT [--limit N]");
        System.out.println("  import --culture KEY [--plan path]");
        System.out.println("  import-local --culture KEY --dir path");
        System.out.println("  validate [--root path]");
        System.out.println("  report [--root path]");
        System.out.println("  resume");
        System.out.println("  names [--out path]");
    }

    private static String flag(String[] args, String name, String def) {
        for (int i = 0; i < args.length - 1; i++) {
            if (name.equals(args[i])) return args[i + 1];
        }
        return def;
    }

    private static Path flagPath(String[] args, String name, Path def) {
        String v = flag(args, name, null);
        return v == null ? def : Path.of(v);
    }
}
