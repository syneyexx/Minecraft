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
                "ancestor_faith",
                List.of("Skjold", "Frost", "Iron", "Storm", "Rime", "Wolf"),
                List.of("heim", "fjord", "gard", "vik", "holt", "stad"),
                List.of("Bjorn", "Erik", "Harald", "Leif", "Ragnar", "Torsten", "Ivar", "Sigurd", "Olaf", "Gunnar"),
                List.of("Astrid", "Ingrid", "Sigrid", "Freyja", "Solveig", "Helga", "Thora", "Ragnhild", "Liv", "Kara"),
                List.of("Ironside", "Wolfsson", "Stonehand", "Frostborn", "Ravenclaw", "Seastorm", "Axebreaker"),
                List.of("Jarl", "King", "High King"),
                List.of("House", "Clan", "Blood of"),
                List.of("Kingdom of {prefix}{suffix}", "{title}dom of {family}", "Jarldom of {prefix}")));

        register(surface(1, "avalon", "Avalon", GovernmentType.MONARCHY,
                "oak_planks", "stone_bricks", "andesite", "oak_stairs", "cobblestone", "stone_brick_wall",
                CultureDefinition.RoofStyle.GABLE, CultureDefinition.LayoutStyle.GRID,
                List.of("plains", "forest", "flower_forest"), List.of("GRAIN", "CLOTH", "LIVESTOCK"),
                "solar_faith",
                List.of("Fair", "Green", "King", "Bright", "Silver", "Rose"),
                List.of("wick", "dale", "ford", "ham", "bury", "chester"),
                List.of("Arthur", "Gawain", "Percival", "Lancelot", "Tristan", "Bedivere", "Gareth", "Kay", "Owen", "Edmund"),
                List.of("Guinevere", "Elaine", "Isolde", "Morgana", "Igraine", "Lynette", "Rowena", "Eleanor", "Cecily", "Mab"),
                List.of("Pendragon", "Loxley", "Ashwood", "Fairchild", "Greenhill", "Whitford", "Rosevale"),
                List.of("King", "Queen", "Lord Protector"),
                List.of("House", "Line of", "Court of"),
                List.of("Kingdom of {prefix}{suffix}", "Realm of {family}", "{title}'s {prefix} Lands")));

        register(surface(2, "sahari", "Sahari", GovernmentType.OLIGARCHY,
                "sandstone", "smooth_sandstone", "terracotta", "red_sandstone_stairs", "smooth_sandstone", "sandstone_wall",
                CultureDefinition.RoofStyle.FLAT, CultureDefinition.LayoutStyle.ORGANIC_RADIAL,
                List.of("desert", "badlands"), List.of("GOLD", "STONE", "LUXURY"),
                "sun_faith",
                List.of("Qasr", "Oasis", "Golden", "Mirage", "Sun", "Dune"),
                List.of("a", "ur", "abad", "shahr", "qasr", "wah"),
                List.of("Rashid", "Omar", "Karim", "Farid", "Hassan", "Yusuf", "Tariq", "Samir", "Idris", "Nabil"),
                List.of("Layla", "Zahra", "Amira", "Fatima", "Noor", "Salma", "Yasmin", "Hana", "Leila", "Rana"),
                List.of("Al-Nazir", "Desertborn", "Sandwalker", "Suncrest", "Goldvein", "Miragehand", "Dunevoice"),
                List.of("Sultan", "Emir", "Caliph"),
                List.of("House", "Banu", "Dynasty of"),
                List.of("Sultanate of {prefix}{suffix}", "Emirate of {family}", "{title}'s Oasis of {prefix}")));

        register(surface(3, "yamato", "Yamato", GovernmentType.MONARCHY,
                "dark_oak_planks", "stone", "white_concrete", "dark_oak_stairs", "gravel", "dark_oak_fence",
                CultureDefinition.RoofStyle.HIP, CultureDefinition.LayoutStyle.TERRACED,
                List.of("cherry_grove", "bamboo_jungle", "sparse_jungle"), List.of("WOOD", "FISH", "GRAIN"),
                "kami_faith",
                List.of("Sakura", "Kyo", "Mizu", "Hana", "Kaze", "Yama"),
                List.of("mura", "kawa", "yama", "shima", "hara", "sato"),
                List.of("Hiroshi", "Kenji", "Takeshi", "Akira", "Ryo", "Daichi", "Haruto", "Sora", "Kaito", "Yuto"),
                List.of("Yuki", "Hana", "Aiko", "Sakura", "Mio", "Emi", "Nana", "Rina", "Kaori", "Yui"),
                List.of("Takahashi", "Yamamoto", "Sato", "Suzuki", "Watanabe", "Ito", "Nakamura"),
                List.of("Emperor", "Shogun", "Daimyo"),
                List.of("Clan", "House", "Line of"),
                List.of("Empire of {prefix}{suffix}", "Shogunate of {family}", "{title}'s {prefix} Domain")));

        register(surface(4, "helvetia", "Helvetia", GovernmentType.REPUBLIC,
                "spruce_planks", "stone", "andesite", "spruce_stairs", "cobblestone", "cobblestone_wall",
                CultureDefinition.RoofStyle.GABLE, CultureDefinition.LayoutStyle.TERRACED,
                List.of("meadow", "windswept_hills", "grove"), List.of("IRON", "WOOD", "GRAIN"),
                "civic_faith",
                List.of("Alp", "High", "Stone", "Clear", "Frost", "Pine"),
                List.of("berg", "thal", "bruck", "dorf", "wald", "heim"),
                List.of("Hans", "Felix", "Lukas", "Jonas", "Matthias", "Stefan", "Niklas", "Tobias", "Adrian", "Leo"),
                List.of("Greta", "Helena", "Clara", "Anna", "Sophie", "Emma", "Lina", "Mia", "Elsa", "Nina"),
                List.of("Bergmann", "Steiner", "Vogel", "Keller", "Fischer", "Weber", "Meier"),
                List.of("Consul", "Chancellor", "Burgermeister"),
                List.of("House", "League of", "Canton of"),
                List.of("Republic of {prefix}{suffix}", "Canton {family}", "{title}'s Alpine League")));

        register(surface(5, "amaru", "Amaru", GovernmentType.TRIBAL_COUNCIL,
                "jungle_planks", "mossy_cobblestone", "mud_bricks", "jungle_stairs", "dirt_path", "jungle_fence",
                CultureDefinition.RoofStyle.THATCH, CultureDefinition.LayoutStyle.ORGANIC_RADIAL,
                List.of("jungle", "sparse_jungle", "swamp"), List.of("WOOD", "GOLD", "FRUIT"),
                "spirit_faith",
                List.of("Green", "River", "Sun", "Jade", "Canopy", "Mist"),
                List.of("tlan", "co", "pan", "ya", "can", "tec"),
                List.of("Itzcoatl", "Cuahtli", "Nahuatl", "Tenoch", "Cipactli", "Atl", "Yolotl", "Mazatl", "Tochtli", "Ocelotl"),
                List.of("Xochitl", "Citlali", "Malinalli", "Izel", "Nayeli", "Tlalli", "Yaretzi", "Metztli", "Palli", "Zeltzin"),
                List.of("Jaguarheart", "Riverborn", "Greenleaf", "Sunfeather", "Mistcoil", "Goldvine", "Canopyeye"),
                List.of("High Speaker", "Elder", "War Chief"),
                List.of("Clan", "Bloodline of", "Circle of"),
                List.of("Council of {prefix}{suffix}", "Realm of {family}", "{title}'s {prefix} Peoples")));

        register(surface(6, "varangian", "Varangian", GovernmentType.OLIGARCHY,
                "spruce_planks", "deepslate_bricks", "iron_block", "spruce_stairs", "packed_ice", "deepslate_brick_wall",
                CultureDefinition.RoofStyle.GABLE, CultureDefinition.LayoutStyle.GRID,
                List.of("snowy_taiga", "ice_spikes", "frozen_peaks"), List.of("IRON", "FISH", "MEAT"),
                "sea_faith",
                List.of("Ice", "Storm", "North", "Frost", "Wave", "Rune"),
                List.of("burg", "haven", "port", "grad", "holm", "vik"),
                List.of("Rurik", "Oleg", "Vladimir", "Igor", "Svyatoslav", "Yaroslav", "Boris", "Gleb", "Mstislav", "Dmitri"),
                List.of("Olga", "Yaroslava", "Nadya", "Katya", "Irina", "Svetlana", "Anya", "Masha", "Vera", "Zoya"),
                List.of("Stormborn", "Icevein", "Longship", "Frosthelm", "Wavebreaker", "Runestone", "Northmark"),
                List.of("Prince", "Boyar", "Grand Prince"),
                List.of("House", "Druzhina of", "Line of"),
                List.of("Principality of {prefix}{suffix}", "Realm of {family}", "{title}'s Northern Hold")));

        register(surface(7, "celtara", "Celtara", GovernmentType.TRIBAL_COUNCIL,
                "oak_planks", "mossy_stone_bricks", "emerald_block", "oak_stairs", "dirt_path", "oak_fence",
                CultureDefinition.RoofStyle.THATCH, CultureDefinition.LayoutStyle.ORGANIC_RADIAL,
                List.of("forest", "birch_forest", "old_growth_birch_forest"), List.of("WOOD", "GRAIN", "LIVESTOCK"),
                "druid_faith",
                List.of("Glen", "Oak", "Mist", "Fern", "Hollow", "Briar"),
                List.of("dun", "loch", "glen", "ford", "cairn", "mór"),
                List.of("Connor", "Bran", "Finn", "Cian", "Liam", "Eoin", "Niall", "Ronán", "Tadhg", "Oisín"),
                List.of("Maeve", "Aoife", "Niamh", "Saoirse", "Siobhan", "Orla", "Fiona", "Brigid", "Aine", "Clodagh"),
                List.of("Oakheart", "Mistwalker", "Ravensong", "Briarthorn", "Glenborn", "Fernvale", "Hollowreed"),
                List.of("High King", "Chieftain", "Druid-King"),
                List.of("Clan", "Tuath of", "Blood of"),
                List.of("Kingdom of {prefix}{suffix}", "Tuath {family}", "{title}'s {prefix} Glens")));

        register(surface(8, "qin", "Qin", GovernmentType.MONARCHY,
                "cherry_planks", "stone_bricks", "gold_block", "cherry_stairs", "stone", "stone_brick_wall",
                CultureDefinition.RoofStyle.HIP, CultureDefinition.LayoutStyle.GRID,
                List.of("plains", "savanna", "windswept_savanna"), List.of("GRAIN", "CLOTH", "IRON"),
                "heaven_faith",
                List.of("Jade", "Dragon", "Heaven", "Silk", "Lotus", "Golden"),
                List.of("zhou", "jing", "shan", "an", "men", "fu"),
                List.of("Wei", "Li", "Chen", "Wang", "Zhang", "Liu", "Zhao", "Sun", "Zhou", "Huang"),
                List.of("Mei", "Ling", "Xia", "Yan", "Lan", "Fang", "Jing", "Hua", "Qiu", "Yue"),
                List.of("Dragonheart", "Jadehand", "Heavenly", "Silkroad", "Lotuspath", "Goldseal", "Cloudgate"),
                List.of("Emperor", "Prince", "Minister"),
                List.of("House", "Clan", "Dynasty of"),
                List.of("Empire of {prefix}{suffix}", "{family} Dynasty", "{title}'s Mandate of {prefix}")));

        register(surface(9, "atlantea", "Atlantea", GovernmentType.REPUBLIC,
                "prismarine", "dark_prismarine", "sea_lantern", "prismarine_brick_stairs", "prismarine", "prismarine_wall",
                CultureDefinition.RoofStyle.DOMED, CultureDefinition.LayoutStyle.GRID,
                List.of("beach", "ocean", "lukewarm_ocean"), List.of("FISH", "STONE", "LUXURY"),
                "tide_faith",
                List.of("Tide", "Coral", "Deep", "Azure", "Pearl", "Foam"),
                List.of("port", "bay", "harbor", "reef", "isle", "cove"),
                List.of("Marinus", "Oceanus", "Nereus", "Triton", "Pelagios", "Aegaeus", "Pontus", "Thalor", "Corin", "Salin"),
                List.of("Thalassa", "Coralia", "Marina", "Nerida", "Aurelia", "Selene", "Pearl", "Isla", "Naia", "Lira"),
                List.of("Waveborn", "Deepcurrent", "Saltwind", "Reefsong", "Pearlgate", "Tidemark", "Azuresail"),
                List.of("Archon", "Admiral", "Harbor Lord"),
                List.of("House", "League of", "Fleet of"),
                List.of("Republic of {prefix}{suffix}", "League of {family}", "{title}'s {prefix} Waters")));

        register(surface(10, "steppeborn", "Steppeborn", GovernmentType.TRIBAL_COUNCIL,
                "birch_planks", "terracotta", "hay_block", "birch_stairs", "dirt_path", "oak_fence",
                CultureDefinition.RoofStyle.THATCH, CultureDefinition.LayoutStyle.ORGANIC_RADIAL,
                List.of("savanna", "savanna_plateau", "windswept_savanna"), List.of("LIVESTOCK", "MEAT", "CLOTH"),
                "sky_faith",
                List.of("Wind", "Horse", "Sky", "Grass", "Eagle", "Dust"),
                List.of("ordu", "ger", "khan", "step", "aul", "yurt"),
                List.of("Temujin", "Batu", "Subutai", "Jebe", "Mukali", "Kublai", "Ogedei", "Tolui", "Altan", "Chagatai"),
                List.of("Borte", "Khulan", "Yesui", "Sorkhokhtani", "Mandukhai", "Alaqa", "Hoelun", "Qutulun", "Cirina", "Temulun"),
                List.of("Horsewind", "Skyarrow", "Wolfpack", "Dustmane", "Eaglefeather", "Grassfire", "Stormhoof"),
                List.of("Khan", "Noyan", "Bagatur"),
                List.of("Clan", "Ordu of", "Blood of"),
                List.of("Khanate of {prefix}{suffix}", "Ordu {family}", "{title}'s {prefix} Horde")));

        register(surface(11, "ironvale", "Ironvale", GovernmentType.OLIGARCHY,
                "deepslate_bricks", "iron_block", "coal_block", "deepslate_tile_stairs", "cobbled_deepslate", "deepslate_brick_wall",
                CultureDefinition.RoofStyle.FLAT, CultureDefinition.LayoutStyle.GRID,
                List.of("windswept_gravelly_hills", "stony_peaks", "jagged_peaks"), List.of("IRON", "COAL", "STONE"),
                "forge_faith",
                List.of("Iron", "Anvil", "Forge", "Coal", "Slag", "Ore"),
                List.of("vale", "hold", "delve", "deep", "mine", "crag"),
                List.of("Durgan", "Brom", "Kael", "Torin", "Garrick", "Brand", "Haldor", "Rurik", "Skarn", "Varr"),
                List.of("Bruna", "Hilda", "Greta", "Kara", "Sigrid", "Mara", "Edda", "Tova", "Ragna", "Una"),
                List.of("Ironfist", "Anvilborn", "Deepdelver", "Slaghand", "Orevein", "Coalheart", "Cragwalker"),
                List.of("Guildmaster", "Forge Lord", "Warden"),
                List.of("House", "Guild of", "Line of"),
                List.of("Hold of {prefix}{suffix}", "Guildrealm of {family}", "{title}'s {prefix} Delve")));

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
            List<String> titles,
            List<String> dynastyPrefixes,
            List<String> kingdomPatterns
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
                new CultureDefinition.NamingStyle(prefixes, suffixes, male, female, family, titles,
                        dynastyPrefixes, kingdomPatterns),
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
                        List.of("Root", "Mycel", "Arcane", "Deep", "Spore", "Glow"),
                        List.of("grove", "hollow", "spire", "vault", "cavern", "bloom"),
                        List.of("Thalorin", "Vex", "Mycor", "Sporel", "Lumin", "Rootan", "Arcady", "Nyth", "Gloam", "Verd"),
                        List.of("Sylvara", "Nyx", "Lumina", "Mycelia", "Spira", "Aureole", "Thalira", "Vespera", "Roota", "Glim"),
                        List.of("Rootwhisper", "Sporemind", "Deepbloom", "Glowcap", "Arcveil", "Hollowsong", "Mycelheart"),
                        List.of("Archmycelium", "High Sporarch", "Root-Seer"),
                        List.of("Mycelium of", "Circle of", "Rootline"),
                        List.of("Theocracy of {prefix}{suffix}", "Mycelium {family}", "{title}'s {prefix} Hollow")
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
