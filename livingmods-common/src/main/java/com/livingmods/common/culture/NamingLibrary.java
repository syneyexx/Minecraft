package com.livingmods.common.culture;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Optional data-driven naming overlays loaded from
 * {@code assets/livingmods/cultures/&lt;key&gt;/naming.json}.
 * Falls back to {@link CultureDefinition.NamingStyle} bootstrap lists.
 */
public final class NamingLibrary {
    private static final Map<String, CultureDefinition.NamingStyle> CACHE = new ConcurrentHashMap<>();

    private NamingLibrary() {}

    public static CultureDefinition.NamingStyle namingFor(CultureDefinition culture) {
        if (culture == null) {
            return new CultureDefinition.NamingStyle(
                    List.of("New"), List.of("ton"), List.of("Alex"), List.of("Alexa"),
                    List.of("Smith"), List.of("Lord"));
        }
        return CACHE.computeIfAbsent(culture.key(), k -> loadOrDefault(culture));
    }

    private static CultureDefinition.NamingStyle loadOrDefault(CultureDefinition culture) {
        String path = "assets/livingmods/cultures/" + culture.key() + "/naming.json";
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        if (cl == null) cl = NamingLibrary.class.getClassLoader();
        try (InputStream in = cl.getResourceAsStream(path)) {
            if (in == null) {
                return culture.naming();
            }
            String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            List<String> male = arr(text, "maleNames", culture.naming().maleNames());
            List<String> female = arr(text, "femaleNames", culture.naming().femaleNames());
            List<String> family = arr(text, "familyNames", culture.naming().familyNames());
            List<String> prefixes = arr(text, "settlementPrefixes", culture.naming().settlementPrefixes());
            List<String> suffixes = arr(text, "settlementSuffixes", culture.naming().settlementSuffixes());
            List<String> titles = arr(text, "rulerTitles", culture.naming().rulerTitles());
            List<String> dynasty = arr(text, "dynastyPrefixes", culture.naming().dynastyPrefixes());
            List<String> kingdom = arr(text, "kingdomPatterns", culture.naming().kingdomPatterns());
            return new CultureDefinition.NamingStyle(
                    prefixes, suffixes, male, female, family, titles, dynasty, kingdom);
        } catch (Exception e) {
            return culture.naming();
        }
    }

    private static List<String> arr(String json, String key, List<String> fallback) {
        String pattern = "\"" + key + "\"";
        int i = json.indexOf(pattern);
        if (i < 0) return fallback;
        int lb = json.indexOf('[', i);
        int rb = json.indexOf(']', lb);
        if (lb < 0 || rb < 0) return fallback;
        String inner = json.substring(lb + 1, rb);
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
        return out.isEmpty() ? fallback : List.copyOf(out);
    }
}
