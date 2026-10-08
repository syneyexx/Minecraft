package com.livingmods.common.culture;

import com.livingmods.common.id.CultureId;

import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * Resolves authoritative culture string keys for architecture / materializers.
 * A kingdom UUID string is never a valid culture key.
 */
public final class CultureKeys {
    public static final String DEFAULT = "avalon";

    private CultureKeys() {}

    public static String resolve(CultureId cultureId) {
        if (cultureId == null) {
            return DEFAULT;
        }
        return new CultureRegistry().get(cultureId).map(CultureDefinition::key).orElse(DEFAULT);
    }

    public static String resolve(CultureId cultureId, CultureRegistry registry) {
        if (cultureId == null || registry == null) {
            return DEFAULT;
        }
        return registry.get(cultureId).map(CultureDefinition::key).orElse(DEFAULT);
    }

    public static String sanitize(String maybeKey) {
        if (maybeKey == null || maybeKey.isBlank()) {
            return DEFAULT;
        }
        String key = maybeKey.trim().toLowerCase(Locale.ROOT);
        // Reject UUID-shaped strings accidentally used as culture keys.
        if (looksLikeUuid(key) || key.startsWith("culture:")) {
            return DEFAULT;
        }
        CultureRegistry registry = new CultureRegistry();
        return registry.get(key).map(CultureDefinition::key).orElse(DEFAULT);
    }

    public static Optional<String> trySanitize(String maybeKey, CultureRegistry registry) {
        if (maybeKey == null || maybeKey.isBlank() || looksLikeUuid(maybeKey) || maybeKey.startsWith("culture:")) {
            return Optional.empty();
        }
        return registry.get(maybeKey.trim().toLowerCase(Locale.ROOT)).map(CultureDefinition::key);
    }

    private static boolean looksLikeUuid(String raw) {
        try {
            UUID.fromString(raw.startsWith("culture:") ? raw.substring("culture:".length()) : raw);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
