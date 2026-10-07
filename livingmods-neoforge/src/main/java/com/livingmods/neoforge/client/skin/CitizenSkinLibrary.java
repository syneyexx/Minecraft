package com.livingmods.neoforge.client.skin;

import com.livingmods.common.util.Hashing;
import com.livingmods.neoforge.LivingModsMod;
import net.minecraft.resources.ResourceLocation;

import java.util.Locale;
import java.util.UUID;

/**
 * Local-only skin library keyed by culture/profession/socialClass/militaryRole/gender/age.
 * Selection is stable from citizenId hash. Missing art falls back to generated baseline textures.
 */
public final class CitizenSkinLibrary {
    private CitizenSkinLibrary() {}

    public static String skinKey(UUID citizenId, String culture, String profession, boolean female, int ageYears) {
        String cultureKey = sanitize(culture == null ? "avalon" : culture);
        String social = socialClass(profession);
        String military = militaryRole(profession);
        String gender = female ? "f" : "m";
        String age = ageYears < 16 ? "child" : ageYears >= 60 ? "elder" : "adult";
        // Hash picks variant bucket for stable diversity within the same axes.
        int variant = 0;
        if (citizenId != null) {
            long h = Hashing.mix(citizenId.getMostSignificantBits(), citizenId.getLeastSignificantBits());
            variant = (int) Math.floorMod(h, 3);
        }
        String professionKey = sanitize(profession == null ? "farmer" : profession.toLowerCase(Locale.ROOT));
        return cultureKey + "/" + social + "/" + professionKey + "/" + military + "/" + gender + "/" + age + "/v" + variant;
    }

    public static ResourceLocation texture(String skinKey) {
        String path = "textures/entity/citizen/" + resolveAssetPath(skinKey) + ".png";
        return ResourceLocation.fromNamespaceAndPath(LivingModsMod.MOD_ID, path);
    }

    /** Map logical key → on-disk baseline asset (culture/gender/age family). */
    public static String resolveAssetPath(String skinKey) {
        if (skinKey == null || skinKey.isBlank()) {
            return "baseline/avalon_m_adult";
        }
        String[] parts = skinKey.split("/");
        String culture = parts.length > 0 ? parts[0] : "avalon";
        String gender = "m";
        String age = "adult";
        for (String p : parts) {
            if ("f".equals(p) || "m".equals(p)) gender = p;
            if ("child".equals(p) || "adult".equals(p) || "elder".equals(p)) age = p;
        }
        return "baseline/" + sanitize(culture) + "_" + gender + "_" + age;
    }

    private static String socialClass(String profession) {
        if (profession == null) return "common";
        return switch (profession.toUpperCase(Locale.ROOT)) {
            case "RULER" -> "royal";
            case "NOBLE" -> "noble";
            case "MERCHANT", "TRADER" -> "comfortable";
            case "CHILD", "UNEMPLOYED" -> "poor";
            default -> "common";
        };
    }

    private static String militaryRole(String profession) {
        if (profession == null) return "none";
        return switch (profession.toUpperCase(Locale.ROOT)) {
            case "GUARD" -> "guard";
            case "SOLDIER" -> "soldier";
            case "RULER" -> "commander";
            case "NOBLE" -> "officer";
            default -> "none";
        };
    }

    private static String sanitize(String raw) {
        return raw.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_]", "_");
    }
}
