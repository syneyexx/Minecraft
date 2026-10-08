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
