package com.livingmods.worldgen.architecture;

import com.livingmods.common.id.BuildingTemplateId;
import com.livingmods.common.model.BuildingRole;
import com.livingmods.common.model.SettlementTier;
import com.livingmods.common.model.WealthClass;
import com.livingmods.common.util.Hashing;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * Registry of procedural building templates with selection metadata.
 */
public final class StructureRegistry {
    private static final StructureRegistry DEFAULT = new StructureRegistry();

    public record Template(
            BuildingTemplateId structureId,
            String cultureKey,
            BuildingRole role,
            SettlementTier minTier,
            WealthClass wealth,
            int width,
            int depth,
            int height,
            double weight,
            List<String> tags
    ) {
        public Template {
            tags = tags == null ? List.of() : List.copyOf(tags);
        }

        public boolean matchesCulture(String key) {
            return cultureKey.equals("*") || cultureKey.equals(key);
        }
    }

    private final List<Template> templates;

    public StructureRegistry() {
        this.templates = buildDefaults();
    }

    public static StructureRegistry defaultRegistry() {
        return DEFAULT;
    }

    public List<Template> all() {
        return templates;
    }

    public List<Template> candidates(String cultureKey, BuildingRole role, SettlementTier tier, WealthClass wealth) {
        List<Template> out = new ArrayList<>();
        for (Template t : templates) {
            if (!t.matchesCulture(cultureKey)) continue;
            if (t.role() != role) continue;
            if (tier.ordinal() < t.minTier().ordinal()) continue;
            if (t.wealth().ordinal() > wealth.ordinal() + 1 && t.wealth() != wealth) continue;
            out.add(t);
        }
        return out;
    }

    public Optional<Template> pickWeighted(String cultureKey, BuildingRole role, SettlementTier tier,
                                           WealthClass wealth, long seed, long ordinal) {
        List<Template> c = candidates(cultureKey, role, tier, wealth);
        if (c.isEmpty()) {
            c = candidates("*", role, tier, wealth);
        }
        if (c.isEmpty()) return Optional.empty();
        double total = 0;
        for (Template t : c) total += t.weight();
        double roll = (Hashing.mix(seed, ordinal) & 0xfffffff) / (double) 0xfffffff * total;
        double acc = 0;
        for (Template t : c) {
            acc += t.weight();
            if (roll <= acc) return Optional.of(t);
        }
        return Optional.of(c.get(c.size() - 1));
    }

    private static List<Template> buildDefaults() {
        long base = 0x574F524B47454EL;
        List<Template> list = new ArrayList<>();
        int i = 0;
        list.add(tpl(base, i++, "*", BuildingRole.HOUSE, SettlementTier.HAMLET, WealthClass.COMMON, 7, 7, 5, 3.0, "residential"));
        list.add(tpl(base, i++, "*", BuildingRole.HOUSE, SettlementTier.VILLAGE, WealthClass.COMMON, 9, 8, 6, 2.5, "residential"));
        list.add(tpl(base, i++, "*", BuildingRole.TOWNHOUSE, SettlementTier.TOWN, WealthClass.COMFORTABLE, 8, 10, 7, 2.0, "residential"));
        list.add(tpl(base, i++, "*", BuildingRole.MANOR, SettlementTier.CITY, WealthClass.WEALTHY, 14, 12, 8, 1.5, "residential"));
        list.add(tpl(base, i++, "*", BuildingRole.PALACE, SettlementTier.CAPITAL, WealthClass.ROYAL, 22, 18, 12, 1.0, "government"));
        list.add(tpl(base, i++, "*", BuildingRole.CASTLE_KEEP, SettlementTier.CAPITAL, WealthClass.NOBLE, 18, 16, 14, 1.2, "military"));
        list.add(tpl(base, i++, "*", BuildingRole.GATEHOUSE, SettlementTier.TOWN, WealthClass.COMMON, 6, 8, 8, 1.5, "military"));
        list.add(tpl(base, i++, "*", BuildingRole.TOWER, SettlementTier.CITY, WealthClass.COMMON, 5, 5, 12, 1.0, "military"));
        list.add(tpl(base, i++, "*", BuildingRole.MARKET_STALL, SettlementTier.VILLAGE, WealthClass.POOR, 4, 4, 3, 2.0, "commerce"));
        list.add(tpl(base, i++, "*", BuildingRole.MARKET_HALL, SettlementTier.TOWN, WealthClass.COMFORTABLE, 12, 10, 7, 1.5, "commerce"));
        list.add(tpl(base, i++, "*", BuildingRole.SHOP, SettlementTier.TOWN, WealthClass.COMMON, 7, 6, 5, 2.0, "commerce"));
        list.add(tpl(base, i++, "*", BuildingRole.TAVERN, SettlementTier.TOWN, WealthClass.COMMON, 10, 9, 6, 1.8, "commerce"));
        list.add(tpl(base, i++, "*", BuildingRole.WORKSHOP, SettlementTier.VILLAGE, WealthClass.COMMON, 9, 8, 5, 2.0, "craft"));
        list.add(tpl(base, i++, "*", BuildingRole.SMITHY, SettlementTier.TOWN, WealthClass.COMMON, 8, 8, 5, 1.5, "craft"));
        list.add(tpl(base, i++, "*", BuildingRole.WAREHOUSE, SettlementTier.TOWN, WealthClass.COMFORTABLE, 14, 10, 6, 1.2, "storage"));
        list.add(tpl(base, i++, "*", BuildingRole.BARN, SettlementTier.HAMLET, WealthClass.POOR, 10, 12, 5, 2.0, "farm"));
        list.add(tpl(base, i++, "*", BuildingRole.FARMHOUSE, SettlementTier.VILLAGE, WealthClass.COMMON, 8, 9, 5, 2.0, "farm"));
        list.add(tpl(base, i++, "*", BuildingRole.MILL, SettlementTier.VILLAGE, WealthClass.COMMON, 10, 8, 7, 1.0, "farm"));
        list.add(tpl(base, i++, "*", BuildingRole.MINE_ENTRANCE, SettlementTier.HAMLET, WealthClass.POOR, 6, 6, 4, 1.5, "mining"));
        list.add(tpl(base, i++, "*", BuildingRole.SAWMILL, SettlementTier.VILLAGE, WealthClass.COMMON, 11, 9, 6, 1.2, "logging"));
        list.add(tpl(base, i++, "*", BuildingRole.DOCK, SettlementTier.TOWN, WealthClass.COMMON, 12, 6, 4, 1.5, "port"));
        list.add(tpl(base, i++, "*", BuildingRole.TEMPLE, SettlementTier.TOWN, WealthClass.COMFORTABLE, 12, 14, 10, 1.5, "religious"));
        list.add(tpl(base, i++, "*", BuildingRole.SCHOOL, SettlementTier.CITY, WealthClass.COMFORTABLE, 10, 10, 6, 1.0, "education"));
        list.add(tpl(base, i++, "*", BuildingRole.CLINIC, SettlementTier.TOWN, WealthClass.COMMON, 8, 8, 5, 1.0, "civic"));
        list.add(tpl(base, i++, "*", BuildingRole.BARRACKS, SettlementTier.TOWN, WealthClass.COMMON, 14, 10, 6, 1.5, "military"));
        list.add(tpl(base, i++, "*", BuildingRole.GUARDHOUSE, SettlementTier.VILLAGE, WealthClass.COMMON, 6, 6, 5, 1.5, "military"));
        list.add(tpl(base, i++, "*", BuildingRole.PRISON, SettlementTier.CITY, WealthClass.COMMON, 10, 12, 6, 0.8, "military"));
        list.add(tpl(base, i++, "*", BuildingRole.MONUMENT, SettlementTier.CITY, WealthClass.NOBLE, 6, 6, 14, 0.6, "civic"));
        list.add(tpl(base, i++, "*", BuildingRole.WELL, SettlementTier.HAMLET, WealthClass.POOR, 3, 3, 2, 2.5, "civic"));
        list.add(tpl(base, i++, "*", BuildingRole.WAYSTONE, SettlementTier.HAMLET, WealthClass.POOR, 2, 2, 3, 1.0, "road"));
        return Collections.unmodifiableList(list);
    }

    private static Template tpl(long seed, int ordinal, String culture, BuildingRole role,
                                SettlementTier tier, WealthClass wealth,
                                int w, int d, int h, double weight, String tag) {
        return new Template(
                BuildingTemplateId.deterministic(seed, ordinal),
                culture, role, tier, wealth, w, d, h, weight, List.of(tag)
        );
    }
}
