package com.livingmods.tools.culturelibrary;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Public BuildPaste discovery client.
 * Search uses the public SvelteKit {@code /search/__data.json} endpoint.
 *
 * <p>M6.1 re-audit (unauthenticated): search/build pages expose titles, slugs, premium flags,
 * and descriptive text mentioning Minecraft NBT changes — not downloadable structure payloads.
 * "download" hits in search results point at external map sites, not BuildPaste block arrays.
 * {@code /build/.../__data.json} returns empty nodes for sampled public builds.
 * Structure block payloads remain gated behind the authenticated in-game {@code /paste} flow.
 * We never bypass login/premium/private endpoints. Use {@code import-local} for manual MLS/NBT.
 */
public final class BuildPasteClient {
    public static final String BASE = "https://buildpaste.net";
    private static final Pattern BUILD_HREF = Pattern.compile("href=\"/build/([^\"]+)\"");
    private static final Pattern BUILD_NAME = Pattern.compile("buildname[^\"]*\"[^>]*>([^<]+)<");
    private static final Pattern BLOCK_COUNT = Pattern.compile("([\\d.]+k?)\\s*blocks");

    public record Candidate(
            String sourceId,
            String slug,
            String title,
            String url,
            int blockCount,
            boolean premium,
            String query
    ) {}

    private final HttpClient http;
    private final Path cacheDir;
    private int retries = 3;

    public BuildPasteClient(Path cacheDir) {
        this.cacheDir = cacheDir;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    public List<Candidate> search(String query, int limit) throws IOException, InterruptedException {
        Files.createDirectories(cacheDir.resolve("search"));
        String enc = URLEncoder.encode(query, StandardCharsets.UTF_8);
        Path cache = cacheDir.resolve("search/" + Integer.toHexString(query.hashCode()) + ".json");
        String body;
        if (Files.isRegularFile(cache)) {
            body = Files.readString(cache, StandardCharsets.UTF_8);
        } else {
            body = get(BASE + "/search/__data.json?q=" + enc);
            Files.writeString(cache, body, StandardCharsets.UTF_8);
            Thread.sleep(400); // conservative pacing
        }
        return parseSearchData(body, query, limit);
    }

    public List<Candidate> searchHtmlFallback(String query, int limit) throws IOException, InterruptedException {
        String enc = URLEncoder.encode(query, StandardCharsets.UTF_8);
        String html = get(BASE + "/search?q=" + enc);
        List<Candidate> out = new ArrayList<>();
        Matcher href = BUILD_HREF.matcher(html);
        while (href.find() && out.size() < limit) {
            String slug = href.group(1);
            if (slug.startsWith("premium-")) continue; // do not target premium
            String id = slug.contains("-") ? slug.substring(slug.lastIndexOf('-') + 1) : slug;
            out.add(new Candidate(id, slug, slug, BASE + "/build/" + slug, 0, false, query));
        }
        return out;
    }

    /**
     * Attempt to download structure payload. Public web does not expose NBT;
     * always returns empty and records SOURCE_UNAVAILABLE.
     */
    public DownloadResult downloadStructure(Candidate candidate) {
        return DownloadResult.unavailable(candidate, "BuildPaste structure payloads require in-game /paste; "
                + "no public NBT download endpoint.");
    }

    public record DownloadResult(Candidate candidate, byte[] bytes, String status, String reason) {
        public static DownloadResult unavailable(Candidate c, String reason) {
            return new DownloadResult(c, null, "SOURCE_UNAVAILABLE", reason);
        }
    }

    private String get(String url) throws IOException, InterruptedException {
        IOException last = null;
        for (int i = 0; i < retries; i++) {
            try {
                HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                        .timeout(Duration.ofSeconds(20))
                        .header("User-Agent", "LivingModsCultureLibrary/0.1 (dev import; respectful)")
                        .GET()
                        .build();
                HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
                if (resp.statusCode() >= 200 && resp.statusCode() < 300) {
                    return resp.body();
                }
                if (resp.statusCode() == 429 || resp.statusCode() >= 500) {
                    Thread.sleep(1000L * (1L << i));
                    continue;
                }
                throw new IOException("HTTP " + resp.statusCode() + " for " + url);
            } catch (IOException e) {
                last = e;
                Thread.sleep(1000L * (1L << i));
            }
        }
        throw last != null ? last : new IOException("request failed: " + url);
    }

    private List<Candidate> parseSearchData(String body, String query, int limit) {
        List<Candidate> out = new ArrayList<>();
        // SvelteKit devalue: extract id/name/blockCount/premium fields via loose regex.
        Matcher ids = Pattern.compile("\"id\":\"([A-Za-z0-9]+)\"").matcher(body);
        Matcher names = Pattern.compile("\"name\":\"([^\"]+)\"").matcher(body);
        List<String> idList = new ArrayList<>();
        List<String> nameList = new ArrayList<>();
        while (ids.find()) idList.add(ids.group(1));
        while (names.find()) nameList.add(names.group(1));
        // Also parse HTML-like slug patterns if present
        Matcher slug = Pattern.compile("/build/([a-z0-9\\-_%]+)").matcher(body);
        Map<String, String> seen = new LinkedHashMap<>();
        while (slug.find() && seen.size() < limit) {
            String s = slug.group(1);
            if (s.startsWith("premium-")) continue;
            seen.putIfAbsent(s, s);
        }
        int i = 0;
        for (String s : seen.keySet()) {
            String id = s.contains("-") ? s.substring(s.lastIndexOf('-') + 1) : s;
            String title = i < nameList.size() ? nameList.get(i) : s;
            out.add(new Candidate(id, s, title, BASE + "/build/" + s, 0, false, query));
            i++;
            if (out.size() >= limit) break;
        }
        if (out.isEmpty()) {
            // fall back to id/name pairs from JSON
            int n = Math.min(limit, Math.min(idList.size(), nameList.size()));
            for (int j = 0; j < n; j++) {
                String id = idList.get(j);
                String title = nameList.get(j);
                String slugGuess = title.toLowerCase().replaceAll("[^a-z0-9]+", "-") + "-" + id;
                out.add(new Candidate(id, slugGuess, title, BASE + "/build/" + slugGuess, 0, false, query));
            }
        }
        return out;
    }
}
