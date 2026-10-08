package com.livingmods.tools.culturelibrary;

import com.livingmods.common.culture.CultureDefinition;
import com.livingmods.common.culture.CultureRegistry;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Expands culture naming pools into data files under cultures/&lt;key&gt;/naming.json.
 */
public final class NamingDataAuthor {
    private NamingDataAuthor() {}

    public static int writeAll(Path culturesRoot) throws IOException {
        CultureRegistry reg = new CultureRegistry();
        int n = 0;
        for (CultureDefinition c : reg.all()) {
            Path dir = culturesRoot.resolve(c.key());
            Files.createDirectories(dir);
            Files.writeString(dir.resolve("naming.json"), buildNamingJson(c), StandardCharsets.UTF_8);
            n++;
        }
        return n;
    }

    private static String buildNamingJson(CultureDefinition c) {
        var n = c.naming();
        List<String> male = expand(n.maleNames(), maleSeeds(c.key()), 140);
        List<String> female = expand(n.femaleNames(), femaleSeeds(c.key()), 140);
        List<String> family = expand(n.familyNames(), familySeeds(c.key()), 110);
        List<String> prefixes = expand(n.settlementPrefixes(), prefixSeeds(c.key()), 50);
        List<String> suffixes = expand(n.settlementSuffixes(), suffixSeeds(c.key()), 50);
        List<String> titles = expand(n.rulerTitles(), titleSeeds(c.key()), 20);
        List<String> dynasty = expand(n.dynastyPrefixes(), dynastySeeds(c.key()), 20);
        List<String> kingdom = expand(n.kingdomPatterns(), kingdomSeeds(c.key()), 20);

        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        sb.append("  \"key\": \"").append(c.key()).append("\",\n");
        writeArr(sb, "maleNames", male, true);
        writeArr(sb, "femaleNames", female, true);
        writeArr(sb, "familyNames", family, true);
        writeArr(sb, "settlementPrefixes", prefixes, true);
        writeArr(sb, "settlementSuffixes", suffixes, true);
        writeArr(sb, "rulerTitles", titles, true);
        writeArr(sb, "dynastyPrefixes", dynasty, true);
        writeArr(sb, "kingdomPatterns", kingdom, false);
        sb.append("}\n");
        return sb.toString();
    }

    private static void writeArr(StringBuilder sb, String key, List<String> values, boolean comma) {
        sb.append("  \"").append(key).append("\": [");
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) sb.append(", ");
            if (i % 8 == 0) sb.append("\n    ");
            sb.append('"').append(values.get(i).replace("\"", "")).append('"');
        }
        sb.append("\n  ]");
        if (comma) sb.append(',');
        sb.append('\n');
    }

    private static List<String> expand(List<String> base, List<String> extras, int target) {
        // Curated pools only — never splice gibberish syllable fillers.
        Set<String> out = new LinkedHashSet<>();
        if (base != null) out.addAll(base);
        for (String e : extras) {
            if (out.size() >= target) break;
            if (e != null && !e.isBlank()) out.add(e);
        }
        return new ArrayList<>(out);
    }

    private static List<String> maleSeeds(String culture) {
        return switch (culture) {
            case "nordheim" -> List.of("Halfdan", "Knut", "Ulf", "Sven", "Eirik", "Arne", "Roar", "Stein", "Hakon", "Vidar",
                    "Trygve", "Frode", "Geir", "Odd", "Rune", "Stig", "Tor", "Yngve", "Asgeir", "Bjarke");
            case "avalon" -> List.of("Aldric", "Cedric", "Roland", "Godfrey", "Hugh", "Walter", "Miles", "Geoffrey", "Raymond", "Simon",
                    "Philip", "Henry", "Richard", "Edward", "Thomas", "William", "Robert", "Stephen", "Gilbert", "Baldwin");
            case "sahari" -> List.of("Malik", "Zayd", "Imran", "Bilal", "Adnan", "Jamal", "Khalid", "Nasir", "Rami", "Sami",
                    "Anwar", "Faisal", "Hakim", "Lutfi", "Qadir", "Salim", "Wasim", "Yasin", "Zubair", "Amin");
            case "yamato" -> List.of("Isamu", "Jiro", "Kazuo", "Makoto", "Noboru", "Osamu", "Shin", "Taro", "Wataru", "Yoichi",
                    "Hayato", "Itsuki", "Ren", "Sota", "Naoki", "Masato", "Takumi", "Yuji", "Ken", "Riku");
            case "helvetia" -> List.of("Anton", "Bruno", "Conrad", "Dietrich", "Emil", "Fritz", "Gustav", "Heinz", "Kurt", "Otto",
                    "Paul", "Rolf", "Sepp", "Ueli", "Walter", "Yves", "Markus", "Beat", "Reto", "Pirmin");
            case "amaru" -> List.of("Amoxtli", "Cualli", "Eztli", "Huitzil", "Ilhicamina", "Necalli", "Tepiltzin", "Xipil", "Yaotl", "Zyanya",
                    "Coatl", "Meztli", "Nochtli", "Quauhtli", "Tizoc", "Xolotl", "Yolotli", "Zolin", "Chimalli", "Icnoyotl");
            case "varangian" -> List.of("Aleksandr", "Bogdan", "Dragomir", "Fyodor", "Gleb", "Ilya", "Kirill", "Leonid", "Mikhail", "Nikita",
                    "Pavel", "Roman", "Stanislav", "Timofei", "Vadim", "Yevgeny", "Zinoviy", "Rostislav", "Svyatopolk", "Vsevolod");
            case "celtara" -> List.of("Aidan", "Brennan", "Cathal", "Declan", "Eamon", "Fergus", "Gareth", "Killian", "Lorcan", "Padraig",
                    "Quinn", "Rory", "Seamus", "Tiernan", "Ultan", "Cormac", "Darragh", "Eoghan", "Fionn", "Odhran");
            case "qin" -> List.of("Bai", "Cheng", "Dong", "Feng", "Gang", "Hai", "Jun", "Kang", "Liang", "Ming",
                    "Ning", "Peng", "Qiang", "Rui", "Shen", "Tao", "Wei", "Xin", "Yong", "Zhen");
            case "atlantea" -> List.of("Alcyon", "Brine", "Caelum", "Delmar", "Eryx", "Galen", "Helios", "Iason", "Kairos", "Lysander",
                    "Nereus", "Orpheus", "Philos", "Quillon", "Sandros", "Thalos", "Uriel", "Varian", "Xenos", "Zephyr");
            case "steppeborn" -> List.of("Arslan", "Batuhan", "Chuluun", "Erden", "Gantulga", "Khasar", "Munkh", "Naran", "Otgon", "Sukh",
                    "Temur", "Uugan", "Yesugei", "Zaya", "Altan", "Bayan", "Dorj", "Ganbaatar", "Khulanbaatar", "Sarnai");
            case "ironvale" -> List.of("Balin", "Dain", "Farin", "Gimli", "Halin", "Korin", "Lorin", "Morin", "Nalin", "Orin",
                    "Thrain", "Urist", "Vili", "Yorin", "Zarin", "Brokk", "Eitri", "Fafnir", "Grimm", "Hroth");
            case "wizard_trees" -> List.of("Aether", "Bole", "Cairn", "Dew", "Elder", "Fathom", "Gloamroot", "Hypha", "Ichor", "Jasper",
                    "Knot", "Lichen", "Moss", "Nexus", "Orb", "Pith", "Quark", "Rill", "Sap", "Thorn");
            default -> List.of();
        };
    }

    private static List<String> femaleSeeds(String culture) {
        return switch (culture) {
            case "nordheim" -> List.of("Alva", "Brynja", "Disa", "Eira", "Frida", "Gunhild", "Hilda", "Idun", "Jorunn", "Kirsten",
                    "Magnhild", "Nanna", "Runa", "Siv", "Tyra", "Unni", "Vigdis", "Ylva", "Asta", "Embla");
            case "avalon" -> List.of("Alys", "Beatrice", "Catherine", "Diane", "Eve", "Frances", "Grace", "Helen", "Isabel", "Joan",
                    "Katherine", "Lydia", "Margaret", "Nora", "Olive", "Patience", "Rose", "Sybil", "Ursula", "Vivian");
            case "sahari" -> List.of("Amina", "Dalal", "Farah", "Ghada", "Iman", "Jamila", "Karima", "Lina", "Mona", "Nadia",
                    "Rania", "Safia", "Tara", "Wafa", "Yara", "Zainab", "Bushra", "Dina", "Heba", "Reem");
            case "yamato" -> List.of("Akane", "Chiyo", "Etsuko", "Fumiko", "Haruka", "Izumi", "Junko", "Keiko", "Mika", "Noriko",
                    "Reiko", "Satomi", "Tomoko", "Umeko", "Yoko", "Aoi", "Hina", "Mei", "Riko", "Saki");
            case "helvetia" -> List.of("Adele", "Brigitte", "Doris", "Erika", "Franziska", "Gabi", "Heidi", "Ilse", "Jolanda", "Karin",
                    "Liselotte", "Monika", "Nadja", "Petra", "Regula", "Silvia", "Trudi", "Ursula", "Vreni", "Yvonne");
            case "amaru" -> List.of("Atl", "Centehua", "Citlalmina", "Iuitl", "Miliani", "Nenetl", "Papantzin", "Quetzalxochitl", "Tlacoehua", "Xoco",
                    "Yoltzin", "Zeltzin", "Amoxtli", "Cihuaton", "Ixchel", "Nelli", "Patli", "Tonalnan", "Xilonen", "Zyanya");
            case "varangian" -> List.of("Anastasia", "Daria", "Elena", "Galina", "Irina", "Ksenia", "Larisa", "Marina", "Natalya", "Oksana",
                    "Polina", "Raisa", "Sofiya", "Tamara", "Ulyana", "Valentina", "Yelena", "Zhanna", "Lyudmila", "Nadezhda");
            case "celtara" -> List.of("Aisling", "Blaithin", "Cara", "Deirdre", "Eilis", "Fionnuala", "Grainne", "Iona", "Keira", "Mairead",
                    "Neasa", "Oonagh", "Roisin", "Shannon", "Tara", "Una", "Bronagh", "Cliona", "Eithne", "Siobhan");
            case "qin" -> List.of("Baihua", "Chun", "Dongmei", "Fang", "Guiying", "Hong", "Juan", "Lian", "Min", "Ning",
                    "Ping", "Qian", "Shuyi", "Ting", "Xiaoli", "Yanli", "Zhenzhen", "Ai", "Bao", "Cai");
            case "atlantea" -> List.of("Aella", "Brineya", "Calypso", "Delphine", "Eirene", "Foam", "Galatea", "Halcyon", "Iris", "Juno",
                    "Kalliope", "Lyra", "Mira", "Nerissa", "Oceana", "Phaedra", "Rhea", "Selene", "Thetis", "Undine");
            case "steppeborn" -> List.of("Altantsetseg", "Bolormaa", "Gerel", "Khulan", "Narangerel", "Oyunchimeg", "Sarangerel", "Tsetseg", "Uugantsetseg", "Zolzaya",
                    "Anu", "Battsetseg", "Delger", "Enkhtuya", "Munhtuya", "Nomin", "Odval", "Solongo", "Tungalag", "Yesui");
            case "ironvale" -> List.of("Bera", "Dagna", "Elda", "Frey", "Groa", "Helga", "Inga", "Jorunn", "Kata", "Luta",
                    "Marta", "Nessa", "Olga", "Petra", "Ragna", "Sigrid", "Tilda", "Una", "Vera", "Ylva");
            case "wizard_trees" -> List.of("Amanita", "Bloom", "Cystia", "Dewdrop", "Elaia", "Fern", "Glimmer", "Hyphaea", "Ivy", "Jade",
                    "Kelp", "Lumina", "Myra", "Nymph", "Opal", "Petal", "Roota", "Spira", "Thalia", "Vinea");
            default -> List.of();
        };
    }

    private static List<String> familySeeds(String culture) {
        return switch (culture) {
            case "nordheim" -> List.of("Stormson", "Icevein", "Thornaxe", "Skaldson", "Bearmantle", "Fjordwalker", "Runebinder", "Snowmane", "Ashhelm", "Wavecrest");
            case "avalon" -> List.of("Blackwood", "Hartley", "Kingsley", "Northcote", "Redgrave", "Thornfield", "Westbrook", "Ashford", "Bellemont", "Crowley");
            case "sahari" -> List.of("Al-Faris", "Al-Hakim", "Al-Rashid", "Bin-Salah", "Desertwind", "Goldensand", "Oasisborn", "Sunspear", "Miragewalker", "Qasri");
            case "yamato" -> List.of("Fujimoto", "Hasegawa", "Ishikawa", "Kobayashi", "Matsuda", "Nishimura", "Okada", "Sakamoto", "Tanaka", "Ueno");
            case "helvetia" -> List.of("Albrecht", "Baumann", "Gerber", "Hofmann", "Kunz", "Lehmann", "Moser", "Schmid", "Ziegler", "Ambuehl");
            case "amaru" -> List.of("Jadeclaw", "Riverstone", "Suncoil", "Mossfang", "Canopyborn", "Goldpetal", "Mistscale", "Templewind", "Quetzal", "Obsidian");
            case "varangian" -> List.of("Belov", "Chernov", "Dubrov", "Ivanov", "Kuznetsov", "Lebedev", "Morozov", "Orlov", "Petrov", "Sokolov");
            case "celtara" -> List.of("MacCarthy", "OBrien", "OConnor", "ONeill", "Fitzpatrick", "Gallagher", "Kelly", "Murphy", "Ryan", "Walsh");
            case "qin" -> List.of("Bai", "Guo", "Han", "Jin", "Ma", "Tang", "Xu", "Yang", "Zheng", "Zhu");
            case "atlantea" -> List.of("Deepwake", "Foamcrest", "Pearlhand", "Reefborn", "Saltglint", "Tidecaller", "Waveglass", "Azurekeel", "Coralmark", "Seastone");
            case "steppeborn" -> List.of("Batbayar", "Erdene", "Ganbold", "Khishig", "Munkhbayar", "Nergui", "Otgonbayar", "Sukhbaatar", "Tuvshin", "Zorig");
            case "ironvale" -> List.of("Anvilmark", "Coalbrow", "Deepgrip", "Forgevein", "Grimstone", "Ironbrow", "Oretooth", "Slagborn", "Steelhand", "Underpeak");
            case "wizard_trees" -> List.of("Capwhisper", "Glowspore", "Hollowroot", "Mycelink", "Nightbloom", "Rootveil", "Spirecap", "Thalspore", "Umbralash", "Verdantomb");
            default -> List.of();
        };
    }

    private static List<String> prefixSeeds(String culture) {
        return switch (culture) {
            case "nordheim" -> List.of("Frost", "Storm", "Iron", "Wolf", "Sea", "Rime", "Ash", "Skald", "Fjell", "Drake");
            case "avalon" -> List.of("High", "Old", "Fair", "Green", "Stone", "King", "White", "Red", "Gold", "River");
            case "sahari" -> List.of("Golden", "Palm", "Sand", "Oasis", "Sun", "Mirage", "Spice", "Dune", "Ivory", "Amber");
            case "yamato" -> List.of("Sakura", "Kaze", "Yama", "Hana", "Tsuki", "Kiri", "Mizu", "Take", "Hoshi", "Aoi");
            case "helvetia" -> List.of("Alpine", "Snow", "Peak", "Crystal", "High", "Pine", "Glacier", "Valley", "Stone", "Cloud");
            case "amaru" -> List.of("Jade", "Sun", "Jungle", "Cloud", "Terrace", "Green", "Stone", "Rain", "Serpent", "Flame");
            case "varangian" -> List.of("White", "Black", "River", "Forest", "North", "Ice", "Trade", "Birch", "Wolf", "Old");
            case "celtara" -> List.of("Green", "Oak", "Mist", "Hill", "Glen", "Stone", "Wild", "Grove", "River", "Tor");
            case "qin" -> List.of("Jade", "Golden", "Azure", "Central", "Eastern", "Western", "Imperial", "River", "Mountain", "Silk");
            case "atlantea" -> List.of("Azure", "Tide", "Pearl", "Coral", "Salt", "Harbor", "Bright", "Deep", "Storm", "Isle");
            case "steppeborn" -> List.of("Wind", "Sky", "Horse", "Grass", "Golden", "Eagle", "Open", "Far", "Blue", "Herd");
            case "ironvale" -> List.of("Deep", "Iron", "Coal", "Forge", "Stone", "Under", "Ore", "Black", "Anvil", "Peak");
            case "wizard_trees" -> List.of("Glow", "Root", "Spore", "Mycel", "Crystal", "Hollow", "Night", "Deep", "Verdant", "Arcane");
            default -> List.of("New", "Old", "Great", "Far");
        };
    }

    private static List<String> suffixSeeds(String culture) {
        // Culture-appropriate settlement endings — not a universal English pool.
        return switch (culture) {
            case "nordheim" -> List.of("heim", "vik", "fjord", "gard", "stad", "ness", "holm", "by", "dal", "berg");
            case "avalon" -> List.of("ton", "ford", "wick", "ham", "bury", "chester", "worth", "field", "bridge", "shire");
            case "sahari" -> List.of("abad", "stan", "qasr", "souk", "oasis", "medina", "ribat", "wadi", "kasbah", "port");
            case "yamato" -> List.of("mura", "ichi", "yama", "shima", "kawa", "hara", "saki", "minato", "dera", "ji");
            case "helvetia" -> List.of("berg", "thal", "dorf", "bad", "wald", "see", "horn", "alp", "bruck", "hof");
            case "amaru" -> List.of("coatl", "tlan", "pan", "can", "tepec", "yan", "calli", "mil", "ixco", "hua");
            case "varangian" -> List.of("grad", "ovsk", "sk", "pol", "gorod", "mir", "slav", "ino", "ka", "burg");
            case "celtara" -> List.of("dun", "kil", "glen", "tor", "llyn", "bally", "cairn", "rath", "avon", "ness");
            case "qin" -> List.of("zhou", "cheng", "men", "guan", "shan", "he", "jiang", "fu", "ting", "cun");
            case "atlantea" -> List.of("port", "bay", "isle", "harbor", "reef", "cove", "strand", "quay", "marina", "point");
            case "steppeborn" -> List.of("ordo", "kuren", "gol", "nur", "bulag", "hot", "sum", "tala", "uul", "ger");
            case "ironvale" -> List.of("delve", "hold", "forge", "mine", "deep", "gate", "anvil", "vein", "hall", "crag");
            case "wizard_trees" -> List.of("hollow", "grove", "cap", "root", "spire", "cavern", "bloom", "veil", "deep", "glow");
            default -> List.of("hold", "haven", "vale", "gate");
        };
    }

    private static List<String> titleSeeds(String culture) {
        return switch (culture) {
            case "nordheim" -> List.of("Jarl", "King", "Queen", "Thane", "Hersir", "Skald-Lord", "Sea-King", "Ring-Giver");
            case "avalon" -> List.of("King", "Queen", "Lord", "Lady", "Duke", "Duchess", "Baron", "Earl", "Prince", "Princess");
            case "sahari" -> List.of("Sultan", "Emir", "Caliph", "Sheikh", "Vizier", "Pasha", "Malik", "Sultana");
            case "yamato" -> List.of("Emperor", "Shogun", "Daimyo", "Lord", "Lady", "Regent", "Prince", "Princess");
            case "helvetia" -> List.of("Count", "Countess", "Lord", "Lady", "Burgermeister", "Warden", "Prince", "Abbot");
            case "amaru" -> List.of("Tlatoani", "High Priest", "Lord", "Lady", "Speaker", "Sun-Warden", "Elder");
            case "varangian" -> List.of("Prince", "Princess", "Boyar", "Grand Prince", "Tsar", "Tsarina", "Voivode");
            case "celtara" -> List.of("High King", "Queen", "Chieftain", "Druid-Lord", "Ri", "Banrion", "Elder");
            case "qin" -> List.of("Emperor", "Empress", "King", "Minister", "Governor", "Magistrate", "Prince", "Princess");
            case "atlantea" -> List.of("Archon", "Navarch", "Lord", "Lady", "Harbor-King", "Prince", "Princess", "Admiral");
            case "steppeborn" -> List.of("Khan", "Khatun", "Noyan", "Baghatur", "Khagan", "Chief", "Elder");
            case "ironvale" -> List.of("King", "Queen", "Thane", "Forge-Lord", "Deep-Warden", "Master", "Elder");
            case "wizard_trees" -> List.of("Archmage", "Spore-Elder", "Root-Lord", "High Mycel", "Keeper", "Speaker");
            default -> List.of("Lord", "Lady", "Ruler", "Elder");
        };
    }

    private static List<String> dynastySeeds(String culture) {
        return switch (culture) {
            case "nordheim" -> List.of("House", "Clan", "Blood of", "Line of", "Shield of", "Ship of");
            case "avalon" -> List.of("House", "Line of", "Banner of", "Seat of", "Crown of", "Court of");
            case "sahari" -> List.of("House", "Tribe of", "Caravan of", "Line of", "Court of", "Oasis of");
            case "yamato" -> List.of("Clan", "House", "Line of", "Court of", "Banner of");
            case "helvetia" -> List.of("House", "Line of", "Alpine Seat of", "Banner of");
            case "amaru" -> List.of("Line of", "Blood of", "Sun of", "Temple of", "House");
            case "varangian" -> List.of("House", "Line of", "Court of", "Boyar of");
            case "celtara" -> List.of("Clan", "Tuath of", "Blood of", "Grove of", "House");
            case "qin" -> List.of("House", "Clan", "Court of", "Line of", "Mandate of");
            case "atlantea" -> List.of("House", "Fleet of", "Harbor of", "Line of", "Tide of");
            case "steppeborn" -> List.of("Ordu of", "Clan", "Blood of", "Herd of", "Banner of");
            case "ironvale" -> List.of("House", "Clan", "Forge of", "Delve of", "Line of");
            case "wizard_trees" -> List.of("Circle of", "Root of", "Spore of", "Line of", "Grove of");
            default -> List.of("House", "Line of", "Clan");
        };
    }

    private static List<String> kingdomSeeds(String culture) {
        return switch (culture) {
            case "nordheim" -> List.of(
                    "Kingdom of {prefix}{suffix}", "Jarldom of {prefix}", "Hold of {family}",
                    "{family} Vik", "Sea-Realm of {prefix}", "Shieldlands of {family}");
            case "avalon" -> List.of(
                    "Kingdom of {prefix}{suffix}", "Realm of {family}", "Duchy of {prefix}{suffix}",
                    "Principality of {prefix}", "Crownlands of {family}", "{family} March");
            case "sahari" -> List.of(
                    "Sultanate of {prefix}", "Emirate of {prefix}{suffix}", "Caliphate of {family}",
                    "Oasis Realm of {prefix}", "Dominion of {prefix}{suffix}");
            case "yamato" -> List.of(
                    "Empire of {prefix}", "Shogunate of {family}", "Domain of {prefix}{suffix}",
                    "Province of {prefix}", "Court of {family}");
            case "helvetia" -> List.of(
                    "County of {prefix}{suffix}", "Alpine Realm of {family}", "Confederation of {prefix}",
                    "Principality of {prefix}", "Canton of {prefix}{suffix}");
            case "amaru" -> List.of(
                    "Empire of {prefix}", "Sun-Realm of {family}", "Temple-State of {prefix}",
                    "Dominion of {prefix}{suffix}", "High Seat of {family}");
            case "varangian" -> List.of(
                    "Principality of {prefix}{suffix}", "Grand Realm of {family}", "Tsardom of {prefix}",
                    "Boyar Lands of {family}", "River-Realm of {prefix}");
            case "celtara" -> List.of(
                    "Kingdom of {prefix}", "High Realm of {family}", "Tuath of {prefix}{suffix}",
                    "Clanlands of {family}", "Grove-Realm of {prefix}");
            case "qin" -> List.of(
                    "Empire of {prefix}", "Kingdom of {prefix}{suffix}", "Mandate of {family}",
                    "Province of {prefix}", "Imperial Domain of {family}");
            case "atlantea" -> List.of(
                    "Maritime Realm of {prefix}", "Archonate of {family}", "Harbor Kingdom of {prefix}{suffix}",
                    "Isle League of {prefix}", "Principality of {prefix}");
            case "steppeborn" -> List.of(
                    "Khanate of {prefix}", "Ordu of {family}", "Khaganate of {prefix}{suffix}",
                    "Steppe Realm of {family}", "Herd-Nation of {prefix}");
            case "ironvale" -> List.of(
                    "Kingdom Under {prefix}", "Deep Realm of {family}", "Forge-Hold of {prefix}",
                    "Delve of {family}", "Under-Kingdom of {prefix}{suffix}");
            case "wizard_trees" -> List.of(
                    "Mycelium Realm of {prefix}", "Root-Court of {family}", "Spore Dominion of {prefix}",
                    "Arcane Hollow of {family}", "Circle of {prefix}{suffix}");
            default -> List.of("Realm of {family}", "Lands of {prefix}{suffix}");
        };
    }
}
