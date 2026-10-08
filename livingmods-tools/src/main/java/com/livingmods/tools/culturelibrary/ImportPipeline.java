package com.livingmods.tools.culturelibrary;

import com.livingmods.worldgen.structure.ImportStatus;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Resumable BuildPaste import pipeline.
 * Discovers candidates publicly; structure payloads are SOURCE_UNAVAILABLE without in-game paste.
 * Authored MineLife assets remain the production library.
 */
public final class ImportPipeline {
    public record Result(
            int discovered,
            int downloaded,
            int imported,
            int duplicates,
            int invalid,
            int unsupported,
            int unavailable,
            int quarantined
    ) {
        public String summary() {
            return "discovered=" + discovered
                    + " downloaded=" + downloaded
                    + " imported=" + imported
                    + " duplicates=" + duplicates
                    + " invalid=" + invalid
                    + " unsupported=" + unsupported
                    + " unavailable=" + unavailable
                    + " quarantined=" + quarantined;
        }
    }

    private final Path cacheDir;
    private final Path stateFile;
    private final Path resourcesRoot;
    private final Map<String, String> state = new LinkedHashMap<>();

    public ImportPipeline(Path cacheDir, Path stateFile, Path resourcesRoot) throws IOException {
        this.cacheDir = cacheDir;
        this.stateFile = stateFile;
        this.resourcesRoot = resourcesRoot;
        Files.createDirectories(cacheDir);
        loadState();
    }

    public Result importCulture(String culture, Path planFile) throws Exception {
        BuildPasteClient client = new BuildPasteClient(cacheDir);
        List<String> queries = loadQueries(planFile, culture);
        int discovered = 0;
        int unavailable = 0;
        int duplicates = 0;
        List<String> seenIds = new ArrayList<>();
        for (String query : queries) {
            if ("done".equals(state.get("query:" + culture + ":" + query))) {
                continue;
            }
            List<BuildPasteClient.Candidate> batch = client.search(query, 20);
            if (batch.isEmpty()) {
                batch = client.searchHtmlFallback(query, 20);
            }
            discovered += batch.size();
            int processed = 0;
            for (BuildPasteClient.Candidate c : batch) {
                if (seenIds.contains(c.sourceId()) || state.containsKey("asset:" + c.sourceId())) {
                    duplicates++;
                    state.put("asset:" + c.sourceId(), ImportStatus.DUPLICATE.name());
                    continue;
                }
                seenIds.add(c.sourceId());
                BuildPasteClient.DownloadResult dl = client.downloadStructure(c);
                state.put("asset:" + c.sourceId(), dl.status());
                if ("SOURCE_UNAVAILABLE".equals(dl.status())) {
                    unavailable++;
                }
                processed++;
                if (processed % 5 == 0) {
                    persistState();
                }
            }
            state.put("query:" + culture + ":" + query, "done");
            persistState();
        }
        state.put("culture:" + culture, "complete");
        persistState();
        return new Result(discovered, 0, 0, duplicates, 0, 0, unavailable, 0);
    }

    /**
     * Local/manual import: copy developer-supplied {@code .mls} files into the culture
     * structure library and append manifest entries. No network. Resumable via state file.
     */
    public Result importLocalDirectory(String culture, Path inbox) throws Exception {
        if (!Files.isDirectory(inbox)) {
            Files.createDirectories(inbox);
            return new Result(0, 0, 0, 0, 0, 0, 0, 0);
        }
        Path cultureDir = resourcesRoot.resolve("cultures/" + culture + "/structures");
        Files.createDirectories(cultureDir);
        Path manifestPath = resourcesRoot.resolve("cultures/" + culture + "/structure_manifest.json");
        List<String> entries = new ArrayList<>();
        if (Files.isRegularFile(manifestPath)) {
            String existing = Files.readString(manifestPath, StandardCharsets.UTF_8);
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\{[^{}]*\"assetId\"[^{}]*\\}").matcher(existing);
            while (m.find()) entries.add(m.group());
        }
        int imported = 0, duplicates = 0, invalid = 0;
        try (var stream = Files.list(inbox)) {
            List<Path> files = stream.filter(p -> p.getFileName().toString().endsWith(".mls"))
                    .sorted().toList();
            for (Path file : files) {
                String name = file.getFileName().toString().replace(".mls", "");
                String assetId = culture + "/local_" + name + "/v0";
                if (state.containsKey("local:" + assetId) || entries.stream().anyMatch(e -> e.contains(assetId))) {
                    duplicates++;
                    continue;
                }
                try {
                    byte[] bytes = Files.readAllBytes(file);
                    var content = com.livingmods.worldgen.structure.MlsStructureFormat.read(bytes);
                    String contentHash = com.livingmods.worldgen.structure.MlsStructureFormat.contentHash(bytes);
                    String geometryHash = com.livingmods.worldgen.structure.MlsStructureFormat.geometryHash(content);
                    String fileName = "local_" + name + "_v0.mls";
                    Files.write(cultureDir.resolve(fileName), bytes);
                    String contentPath = "assets/livingmods/cultures/" + culture + "/structures/" + fileName;
                    entries.add("{\n"
                            + "      \"assetId\": \"" + assetId + "\",\n"
                            + "      \"source\": \"local_import\",\n"
                            + "      \"sourceId\": \"" + name + "\",\n"
                            + "      \"cultureKey\": \"" + culture + "\",\n"
                            + "      \"canonicalRole\": \"HOUSE\",\n"
                            + "      \"archetype\": \"local_" + name + "\",\n"
                            + "      \"variant\": \"v0\",\n"
                            + "      \"width\": " + content.width() + ",\n"
                            + "      \"depth\": " + content.depth() + ",\n"
                            + "      \"height\": " + content.height() + ",\n"
                            + "      \"blockCount\": " + content.blocks().size() + ",\n"
                            + "      \"entranceFacing\": \""
                            + com.livingmods.worldgen.structure.MlsStructureFormat.facingName(content.entranceFacing())
                            + "\",\n"
                            + "      \"entranceX\": " + content.entranceX() + ",\n"
                            + "      \"entranceY\": " + content.entranceY() + ",\n"
                            + "      \"entranceZ\": " + content.entranceZ() + ",\n"
                            + "      \"allowedRotations\": [0, 90, 180, 270],\n"
                            + "      \"mirrorAllowed\": false,\n"
                            + "      \"contentPath\": \"" + contentPath + "\",\n"
                            + "      \"contentHash\": \"" + contentHash + "\",\n"
                            + "      \"geometryHash\": \"" + geometryHash + "\",\n"
                            + "      \"uniquenessGroup\": \"house:local_" + name + "\",\n"
                            + "      \"status\": \"IMPORTED_SANITIZED\",\n"
                            + "      \"sanitized\": true\n"
                            + "    }");
                    state.put("local:" + assetId, "IMPORTED_SANITIZED");
                    imported++;
                } catch (Exception e) {
                    invalid++;
                    state.put("local:" + assetId, "INVALID");
                }
            }
        }
        Files.writeString(manifestPath, "{\n  \"assets\": [\n    "
                + String.join(",\n    ", entries) + "\n  ]\n}\n", StandardCharsets.UTF_8);
        persistState();
        return new Result(imported + duplicates + invalid, imported, imported, duplicates, invalid, 0, 0, 0);
    }

    public Result resume() throws Exception {
        // Resume incomplete cultures from query plans.
        String[] cultures = {
                "avalon", "nordheim", "sahari", "yamato", "helvetia", "celtara",
                "qin", "varangian", "amaru", "atlantea", "steppeborn", "ironvale", "wizard_trees"
        };
        int discovered = 0, unavailable = 0, duplicates = 0;
        for (String culture : cultures) {
            if ("complete".equals(state.get("culture:" + culture))) continue;
            Path plan = Path.of("livingmods-tools/src/main/resources/culture-library/query_plans/"
                    + culture + ".json");
            Result r = importCulture(culture, plan);
            discovered += r.discovered();
            unavailable += r.unavailable();
            duplicates += r.duplicates();
        }
        return new Result(discovered, 0, 0, duplicates, 0, 0, unavailable, 0);
    }

    private List<String> loadQueries(Path planFile, String culture) throws IOException {
        List<String> queries = new ArrayList<>();
        if (planFile != null && Files.isRegularFile(planFile)) {
            String text = Files.readString(planFile, StandardCharsets.UTF_8);
            // crude: pull all "query" string values and bare query lines in arrays
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("\"([^\"]+)\"").matcher(text);
            while (m.find()) {
                String s = m.group(1);
                if (s.equals(culture) || s.equals("queries") || s.equals("desiredCount")
                        || s.equals("minimumCount") || s.equals("HOUSE") || s.equals("TAVERN")
                        || s.length() < 3 || s.equals(s.toUpperCase()) && s.length() < 20) {
                    continue;
                }
                if (s.contains(" ") || s.contains("house") || s.contains("temple") || s.contains("castle")
                        || s.contains("bridge") || s.contains("market") || s.contains("barn")
                        || s.contains("yurt") || s.contains("pagoda") || s.contains("forge")) {
                    queries.add(s);
                }
            }
        }
        if (queries.isEmpty()) {
            queries.add(culture + " house");
            queries.add(culture + " village");
            queries.add("medieval " + culture);
        }
        return queries;
    }

    private void loadState() throws IOException {
        if (!Files.isRegularFile(stateFile)) return;
        for (String line : Files.readAllLines(stateFile, StandardCharsets.UTF_8)) {
            int eq = line.indexOf('=');
            if (eq > 0) {
                state.put(line.substring(0, eq), line.substring(eq + 1));
            }
        }
    }

    private void persistState() throws IOException {
        Files.createDirectories(stateFile.getParent());
        StringBuilder sb = new StringBuilder();
        sb.append("discovered=").append(countPrefix("asset:")).append('\n');
        for (Map.Entry<String, String> e : state.entrySet()) {
            sb.append(e.getKey()).append('=').append(e.getValue()).append('\n');
        }
        Files.writeString(stateFile, sb.toString(), StandardCharsets.UTF_8);
    }

    private int countPrefix(String prefix) {
        int n = 0;
        for (String k : state.keySet()) if (k.startsWith(prefix)) n++;
        return n;
    }
}
