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
        Set<String> out = new LinkedHashSet<>(base);
        for (String e : extras) {
            if (out.size() >= target) break;
            out.add(e);
        }
        // Deterministic synthetic fillers from base syllables — avoid spam duplicates.
        List<String> roots = new ArrayList<>(out);
        int i = 0;
        while (out.size() < target && !roots.isEmpty()) {
            String a = roots.get(i % roots.size());
            String b = roots.get((i * 7 + 3) % roots.size());
            String syn = synthesize(a, b, i);
            out.add(syn);
            i++;
            if (i > target * 8) break;
        }
        return new ArrayList<>(out).subList(0, Math.min(target, out.size()));
    }

    private static String synthesize(String a, String b, int i) {
        String aa = a.replaceAll("[^A-Za-z]", "");
        String bb = b.replaceAll("[^A-Za-z]", "");
        if (aa.length() < 2) aa = aa + "ara";
        if (bb.length() < 2) bb = bb + "ven";
        int cutA = Math.min(aa.length(), Math.max(1, 2 + (i % 3)));
        int cutB = Math.min(bb.length(), Math.max(1, 2 + ((i + 1) % 3)));
        String s = aa.substring(0, cutA) + bb.substring(bb.length() - cutB).toLowerCase(Locale.ROOT);
        if (s.isEmpty()) s = "Ara" + i;
        return Character.toUpperCase(s.charAt(0)) + (s.length() > 1 ? s.substring(1) : "");
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
        return List.of("High", "Old", "New", "Great", "Little", "North", "South", "East", "West", "Upper",
                "Lower", "Far", "Near", "Bright", "Dark", "White", "Black", "Red", "Gold", "Silver");
    }

    private static List<String> suffixSeeds(String culture) {
        return List.of("ton", "ford", "wick", "haven", "field", "wood", "brook", "vale", "moor", "ridge",
                "port", "gate", "hall", "stead", "mere", "fell", "marsh", "cliff", "bay", "reach");
    }

    private static List<String> titleSeeds(String culture) {
        return List.of("Lord", "Lady", "High", "Grand", "Chief", "Master", "Elder", "Warden", "Steward", "Regent",
                "Prince", "Princess", "Duke", "Duchess", "Marshal", "Chancellor", "Speaker", "Keeper", "First", "Supreme");
    }

    private static List<String> dynastySeeds(String culture) {
        return List.of("House", "Clan", "Line of", "Blood of", "Order of", "Circle of", "Banner of", "Seat of",
                "Throne of", "Court of", "Legacy of", "Kin of", "Realm of", "Crown of", "Shield of", "Flame of",
                "Star of", "Stone of", "Root of", "Tide of");
    }

    private static List<String> kingdomSeeds(String culture) {
        return List.of(
                "Kingdom of {prefix}{suffix}",
                "Realm of {family}",
                "{title}'s {prefix} Lands",
                "Dominion of {prefix}{suffix}",
                "Hold of {family}",
                "League of {prefix}",
                "Empire of {prefix}{suffix}",
                "{family} March",
                "Protectorate of {prefix}",
                "Free {prefix}{suffix}",
                "United {prefix} Realms",
                "Crownlands of {family}",
                "{title}dom of {prefix}",
                "Confederation of {suffix}",
                "Principality of {prefix}{suffix}",
                "Grand Duchy of {family}",
                "Satrapy of {prefix}",
                "Khanate of {prefix}{suffix}",
                "Theocracy of {prefix}",
                "Guildrealm of {family}"
        );
    }
}
