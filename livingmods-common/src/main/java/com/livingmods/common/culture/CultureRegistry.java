package com.livingmods.common.culture;

import com.livingmods.common.id.CultureId;
import com.livingmods.common.model.GovernmentType;
import com.livingmods.common.util.DeterministicRandom;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Data-driven culture registry. Twelve surface cultures + Wizard Trees.
 */
public final class CultureRegistry {
    private static final long WIZARD_SEED = 0x57495A415244L;
    private static final long CULTURE_SEED = 0xC01712E5EEDL;

    private final Map<String, CultureDefinition> byKey = new LinkedHashMap<>();
    private final Map<CultureId, CultureDefinition> byId = new LinkedHashMap<>();

    public CultureRegistry() {
        registerDefaults();
    }

    public void register(CultureDefinition culture) {
        byKey.put(culture.key(), culture);
        byId.put(culture.id(), culture);
    }

    public Optional<CultureDefinition> get(String key) {
        return Optional.ofNullable(byKey.get(key));
    }

    public Optional<CultureDefinition> get(CultureId id) {
        return Optional.ofNullable(byId.get(id));
    }

    public List<CultureDefinition> surfaceCultures() {
        List<CultureDefinition> list = new ArrayList<>();
        for (CultureDefinition c : byKey.values()) {
            if (!c.underground()) {
                list.add(c);
            }
        }
        return Collections.unmodifiableList(list);
    }

    public CultureDefinition wizardTrees() {
        return byKey.get("wizard_trees");
    }

    public List<CultureDefinition> all() {
        return List.copyOf(byKey.values());
    }

    public CultureDefinition pickSurface(DeterministicRandom random) {
        return random.pick(surfaceCultures());
    }

    private void registerDefaults() {
        register(surface(0, "nordheim", "Nordheim", GovernmentType.MONARCHY,
                "spruce_planks", "stone_bricks", "cobblestone", "dark_oak_stairs", "gravel", "cobblestone_wall",
                CultureDefinition.RoofStyle.GABLE, CultureDefinition.LayoutStyle.ORGANIC_RADIAL,
                List.of("taiga", "snowy_plains", "frozen_river"), List.of("WOOD", "IRON", "MEAT"),
                "ancestor_faith", List.of("Skjold", "Frost", "Iron"), List.of("heim", "fjord", "gard"),
                List.of("Bjorn", "Erik", "Harald"), List.of("Astrid", "Ingrid", "Sigrid"),
                List.of("Ironside", "Wolfsson", "Stonehand"), List.of("Jarl", "King", "High King")));

        register(surface(1, "avalon", "Avalon", GovernmentType.MONARCHY,
                "oak_planks", "stone_bricks", "andesite", "oak_stairs", "cobblestone", "stone_brick_wall",
                CultureDefinition.RoofStyle.GABLE, CultureDefinition.LayoutStyle.GRID,
                List.of("plains", "forest", "flower_forest"), List.of("GRAIN", "CLOTH", "LIVESTOCK"),
                "solar_faith", List.of("Fair", "Green", "King"), List.of("wick", "dale", "ford"),
                List.of("Arthur", "Gawain", "Percival"), List.of("Guinevere", "Elaine", "Isolde"),
                List.of("Pendragon", "Loxley", "Ashwood"), List.of("King", "Queen", "Lord Protector")));

        register(surface(2, "sahari", "Sahari", GovernmentType.OLIGARCHY,
                "sandstone", "smooth_sandstone", "terracotta", "red_sandstone_stairs", "smooth_sandstone", "sandstone_wall",
                CultureDefinition.RoofStyle.FLAT, CultureDefinition.LayoutStyle.ORGANIC_RADIAL,
                List.of("desert", "badlands"), List.of("GOLD", "STONE", "LUXURY"),
                "sun_faith", List.of("Qasr", "Oasis", "Golden"), List.of("a", "ur", "abad"),
                List.of("Rashid", "Omar", "Karim"), List.of("Layla", "Zahra", "Amira"),
                List.of("Al-Nazir", "Desertborn", "Sandwalker"), List.of("Sultan", "Emir", "Caliph")));

        register(surface(3, "yamato", "Yamato", GovernmentType.MONARCHY,
                "dark_oak_planks", "stone", "white_concrete", "dark_oak_stairs", "gravel", "dark_oak_fence",
                CultureDefinition.RoofStyle.HIP, CultureDefinition.LayoutStyle.TERRACED,
                List.of("cherry_grove", "bamboo_jungle", "sparse_jungle"), List.of("WOOD", "FISH", "GRAIN"),
                "kami_faith", List.of("Sakura", "Kyo", "Mizu"), List.of("mura", "kawa", "yama"),
                List.of("Hiroshi", "Kenji", "Takeshi"), List.of("Yuki", "Hana", "Aiko"),
                List.of("Takahashi", "Yamamoto", "Sato"), List.of("Emperor", "Shogun", "Daimyo")));

        register(surface(4, "helvetia", "Helvetia", GovernmentType.REPUBLIC,
                "spruce_planks", "stone", "andesite", "spruce_stairs", "cobblestone", "cobblestone_wall",
                CultureDefinition.RoofStyle.GABLE, CultureDefinition.LayoutStyle.TERRACED,
                List.of("meadow", "windswept_hills", "grove"), List.of("IRON", "WOOD", "GRAIN"),
                "civic_faith", List.of("Alp", "High", "Stone"), List.of("berg", "thal", "bruck"),
                List.of("Hans", "Felix", "Lukas"), List.of("Greta", "Helena", "Clara"),
                List.of("Bergmann", "Steiner", "Vogel"), List.of("Consul", "Chancellor", "Burgermeister")));

        register(surface(5, "amaru", "Amaru", GovernmentType.TRIBAL_COUNCIL,
                "jungle_planks", "mossy_cobblestone", "mud_bricks", "jungle_stairs", "dirt_path", "jungle_fence",
                CultureDefinition.RoofStyle.THATCH, CultureDefinition.LayoutStyle.ORGANIC_RADIAL,
                List.of("jungle", "sparse_jungle", "swamp"), List.of("WOOD", "GOLD", "FRUIT"),
                "spirit_faith", List.of("Green", "River", "Sun"), List.of("tlan", "co", "pan"),
                List.of("Itzcoatl", "Cuahtli", "Nahuatl"), List.of("Xochitl", "Citlali", "Malinalli"),
                List.of("Jaguarheart", "Riverborn", "Greenleaf"), List.of("High Speaker", "Elder", "War Chief")));

        register(surface(6, "varangian", "Varangian", GovernmentType.OLIGARCHY,
                "spruce_planks", "deepslate_bricks", "iron_block", "spruce_stairs", "packed_ice", "deepslate_brick_wall",
                CultureDefinition.RoofStyle.GABLE, CultureDefinition.LayoutStyle.GRID,
                List.of("snowy_taiga", "ice_spikes", "frozen_peaks"), List.of("IRON", "FISH", "MEAT"),
                "sea_faith", List.of("Ice", "Storm", "North"), List.of("burg", "haven", "port"),
                List.of("Rurik", "Oleg", "Vladimir"), List.of("Olga", "Yaroslava", "Nadya"),
                List.of("Stormborn", "Icevein", "Longship"), List.of("Prince", "Boyar", "Grand Prince")));

        register(surface(7, "celtara", "Celtara", GovernmentType.TRIBAL_COUNCIL,
                "oak_planks", "mossy_stone_bricks", "emerald_block", "oak_stairs", "dirt_path", "oak_fence",
                CultureDefinition.RoofStyle.THATCH, CultureDefinition.LayoutStyle.ORGANIC_RADIAL,
                List.of("forest", "birch_forest", "old_growth_birch_forest"), List.of("WOOD", "GRAIN", "LIVESTOCK"),
                "druid_faith", List.of("Glen", "Oak", "Mist"), List.of("dun", "loch", "glen"),
                List.of("Connor", "Bran", "Finn"), List.of("Maeve", "Aoife", "Niamh"),
                List.of("Oakheart", "Mistwalker", "Ravensong"), List.of("High King", "Chieftain", "Druid-King")));

        register(surface(8, "qin", "Qin", GovernmentType.MONARCHY,
                "cherry_planks", "stone_bricks", "gold_block", "cherry_stairs", "stone", "stone_brick_wall",
                CultureDefinition.RoofStyle.HIP, CultureDefinition.LayoutStyle.GRID,
                List.of("plains", "savanna", "windswept_savanna"), List.of("GRAIN", "CLOTH", "IRON"),
                "heaven_faith", List.of("Jade", "Dragon", "Heaven"), List.of("zhou", "jing", "shan"),
                List.of("Wei", "Li", "Chen"), List.of("Mei", "Ling", "Xia"),
                List.of("Dragonheart", "Jadehand", "Heavenly"), List.of("Emperor", "Prince", "Minister")));

        register(surface(9, "atlantea", "Atlantea", GovernmentType.REPUBLIC,
                "prismarine", "dark_prismarine", "sea_lantern", "prismarine_brick_stairs", "prismarine", "prismarine_wall",
                CultureDefinition.RoofStyle.DOMED, CultureDefinition.LayoutStyle.GRID,
                List.of("beach", "ocean", "lukewarm_ocean"), List.of("FISH", "STONE", "LUXURY"),
                "tide_faith", List.of("Tide", "Coral", "Deep"), List.of("port", "bay", "harbor"),
                List.of("Marinus", "Oceanus", "Nereus"), List.of("Thalassa", "Coralia", "Marina"),
                List.of("Waveborn", "Deepcurrent", "Saltwind"), List.of("Archon", "Admiral", "Harbor Lord")));

        register(surface(10, "steppeborn", "Steppeborn", GovernmentType.TRIBAL_COUNCIL,
                "birch_planks", "terracotta", "hay_block", "birch_stairs", "dirt_path", "oak_fence",
                CultureDefinition.RoofStyle.THATCH, CultureDefinition.LayoutStyle.ORGANIC_RADIAL,
                List.of("savanna", "savanna_plateau", "windswept_savanna"), List.of("LIVESTOCK", "MEAT", "CLOTH"),
                "sky_faith", List.of("Wind", "Horse", "Sky"), List.of("ordu", "ger", "khan"),
                List.of("Temujin", "Batu", "Subutai"), List.of("Borte", "Khulan", "Yesui"),
                List.of("Horsewind", "Skyarrow", "Wolfpack"), List.of("Khan", "Noyan", "Bagatur")));

        register(surface(11, "ironvale", "Ironvale", GovernmentType.OLIGARCHY,
                "deepslate_bricks", "iron_block", "coal_block", "deepslate_tile_stairs", "cobbled_deepslate", "deepslate_brick_wall",
                CultureDefinition.RoofStyle.FLAT, CultureDefinition.LayoutStyle.GRID,
                List.of("windswept_gravelly_hills", "stony_peaks", "jagged_peaks"), List.of("IRON", "COAL", "STONE"),
                "forge_faith", List.of("Iron", "Anvil", "Forge"), List.of("vale", "hold", "delve"),
                List.of("Durgan", "Brom", "Kael"), List.of("Bruna", "Hilda", "Greta"),
                List.of("Ironfist", "Anvilborn", "Deepdelver"), List.of("Guildmaster", "Forge Lord", "Warden")));

        register(wizardTreesCulture());
    }

    private static CultureDefinition surface(
            int ordinal,
            String key,
            String displayName,
            GovernmentType government,
            String primary, String secondary, String accent, String roof, String road, String wall,
            CultureDefinition.RoofStyle roofStyle,
            CultureDefinition.LayoutStyle layoutStyle,
            List<String> biomes,
            List<String> resources,
            String religion,
            List<String> prefixes,
            List<String> suffixes,
            List<String> male,
            List<String> female,
            List<String> family,
            List<String> titles
    ) {
        CultureId id = CultureId.deterministic(CULTURE_SEED, ordinal);
        Map<String, Double> weights = Map.of(
                "FARMER", 1.0,
                "ARTISAN", 0.6,
                "GUARD", 0.4,
                "TRADER", 0.5,
                "PRIEST", 0.3,
                "SCHOLAR", 0.2
        );
        return new CultureDefinition(
                id, key, displayName, government,
                new CultureDefinition.ArchitectureStyle(
                        primary, secondary, accent, roof, road, wall,
                        roofStyle, layoutStyle, 0.35, layoutStyle == CultureDefinition.LayoutStyle.GRID
                ),
                new CultureDefinition.NamingStyle(prefixes, suffixes, male, female, family, titles),
                new CultureDefinition.MilitaryStyle("balanced", 0.3, 0.5, "blades"),
                biomes, resources, weights, religion, religion + "_law", false
        );
    }

    private static CultureDefinition wizardTreesCulture() {
        CultureId id = CultureId.deterministic(WIZARD_SEED, 12);
        return new CultureDefinition(
                id, "wizard_trees", "Wizard Trees", GovernmentType.THEOCRACY,
                new CultureDefinition.ArchitectureStyle(
                        "deepslate_bricks", "amethyst_block", "crying_obsidian", "purple_stained_glass",
                        "polished_deepslate", "deepslate_brick_wall",
                        CultureDefinition.RoofStyle.MAGICAL,
                        CultureDefinition.LayoutStyle.CAVERN,
                        0.5, false
                ),
                new CultureDefinition.NamingStyle(
                        List.of("Root", "Mycel", "Arcane", "Deep"),
                        List.of("grove", "hollow", "spire", "vault"),
                        List.of("Thalorin", "Vex", "Mycor"),
                        List.of("Sylvara", "Nyx", "Lumina"),
                        List.of("Rootwhisper", "Sporemind", "Deepbloom"),
                        List.of("Archmycelium", "High Sporarch", "Root-Seer")
                ),
                new CultureDefinition.MilitaryStyle("arcane_defense", 0.1, 0.9, "magic"),
                List.of("lush_caves", "dripstone_caves", "deep_dark"),
                List.of("KNOWLEDGE", "AMETHYST", "REDSTONE", "FUNGUS"),
                Map.of("SCHOLAR", 1.2, "PRIEST", 1.0, "ARTISAN", 0.7, "FARMER", 0.5, "GUARD", 0.4),
                "mycelial_theocracy",
                "mycelial_law",
                true
        );
    }

}
