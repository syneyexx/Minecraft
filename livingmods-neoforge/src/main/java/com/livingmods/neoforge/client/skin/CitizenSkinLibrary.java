package com.livingmods.neoforge.client.skin;

import com.livingmods.common.util.Hashing;
import com.livingmods.neoforge.LivingModsMod;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;

import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * Local-only skin library keyed by culture/social/profession/military/gender/age/variant.
 * Resolution prefers pool paths, then baseline/{culture}_{gender}_{age}.
 */
public final class CitizenSkinLibrary {
    private CitizenSkinLibrary() {}

    public static String skinKey(UUID citizenId, String culture, String profession, boolean female, int ageYears) {
        String cultureKey = sanitize(culture == null ? "avalon" : culture);
        String social = socialClass(profession);
        String military = militaryRole(profession);
        String gender = female ? "f" : "m";
        String age = ageYears < 16 ? "child" : ageYears >= 60 ? "elder" : "adult";
        int variant = 0;
        if (citizenId != null) {
            long h = Hashing.mix(citizenId.getMostSignificantBits(), citizenId.getLeastSignificantBits());
            variant = (int) Math.floorMod(h, 4);
        }
        String professionKey = sanitize(profession == null ? "farmer" : profession.toLowerCase(Locale.ROOT));
        // Pool layout: culture/social/profession/gender/age/vN
        return cultureKey + "/" + social + "/" + professionKey + "/" + gender + "/" + age + "/v" + variant;
    }

    public static ResourceLocation texture(String skinKey) {
        String path = "textures/entity/citizen/" + resolveAssetPath(skinKey) + ".png";
        return ResourceLocation.fromNamespaceAndPath(LivingModsMod.MOD_ID, path);
    }

    /**
     * Map logical key → on-disk asset.
     * Tries full pool path, then social-agnostic profession pool, then baseline.
     */
    public static String resolveAssetPath(String skinKey) {
        if (skinKey == null || skinKey.isBlank()) {
            return "baseline/avalon_m_adult";
        }
        String[] parts = skinKey.split("/");
        String culture = parts.length > 0 ? parts[0] : "avalon";
        String social = parts.length > 1 ? parts[1] : "common";
        String profession = parts.length > 2 ? parts[2] : "farmer";
        String gender = "m";
        String age = "adult";
        String variant = "v0";
        for (String p : parts) {
            if ("f".equals(p) || "m".equals(p)) gender = p;
            if ("child".equals(p) || "adult".equals(p) || "elder".equals(p)) age = p;
            if (p.startsWith("v") && p.length() > 1 && Character.isDigit(p.charAt(1))) variant = p;
        }

        String pool = sanitize(culture) + "/" + sanitize(social) + "/" + sanitize(profession)
                + "/" + gender + "/" + age + "/" + variant;
        if (resourceExists(pool)) {
            return pool;
        }
        // Military / noble specializations may share profession folder under military/noble.
        String altSocial = "military".equals(social) || "noble".equals(social) || "royal".equals(social)
                ? social : "common";
        String alt = sanitize(culture) + "/" + altSocial + "/" + sanitize(profession)
                + "/" + gender + "/" + age + "/" + variant;
        if (!alt.equals(pool) && resourceExists(alt)) {
            return alt;
        }
        // Drop variant then profession to coarser pools.
        String noVariant = sanitize(culture) + "/" + sanitize(social) + "/" + sanitize(profession)
                + "/" + gender + "/" + age + "/v0";
        if (resourceExists(noVariant)) {
            return noVariant;
        }
        return "baseline/" + sanitize(culture) + "_" + gender + "_" + age;
    }

    private static boolean resourceExists(String relativeWithoutExt) {
        try {
            ResourceLocation loc = ResourceLocation.fromNamespaceAndPath(
                    LivingModsMod.MOD_ID, "textures/entity/citizen/" + relativeWithoutExt + ".png");
            Optional<Resource> res = Minecraft.getInstance().getResourceManager().getResource(loc);
            return res.isPresent();
        } catch (Throwable t) {
            // Dedicated server / early init — assume missing, use baseline.
            return false;
        }
    }

    private static String socialClass(String profession) {
        if (profession == null) return "common";
        return switch (profession.toUpperCase(Locale.ROOT)) {
            case "RULER" -> "royal";
            case "NOBLE" -> "noble";
            case "MERCHANT", "TRADER" -> "comfortable";
            case "CHILD", "UNEMPLOYED" -> "poor";
            case "GUARD", "SOLDIER" -> "military";
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
