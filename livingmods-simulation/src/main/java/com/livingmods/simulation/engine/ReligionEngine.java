package com.livingmods.simulation.engine;

import com.livingmods.common.culture.CultureDefinition;
import com.livingmods.common.culture.CultureRegistry;
import com.livingmods.common.model.DiplomaticRelation;
import com.livingmods.common.model.ReligionDefinition;
import com.livingmods.simulation.CanonicalWorldState;
import com.livingmods.simulation.state.KingdomState;
import com.livingmods.simulation.state.SettlementState;
import com.livingmods.simulation.tick.RegionalWork;
import com.livingmods.simulation.tick.SimulationContext;

/**
 * Religion shapes culture expression, legitimacy, diplomacy leanings, festivals,
 * and marriage / political alliance bias.
 */
public final class ReligionEngine implements SimulationSubsystem {
    private final CultureRegistry cultures = new CultureRegistry();

    @Override
    public String name() { return "religion"; }

    @Override
    public void phase1Regional(CanonicalWorldState state, RegionalWork work, SimulationContext ctx) {
        if (!ctx.schedule().runGovernment()) return;

        for (SettlementState settlement : state.settlements().values()) {
            if (!settlement.region().equals(work.region())) continue;
            KingdomState kingdom = settlement.ownerKingdom()
                    .map(id -> state.kingdoms().get(id))
                    .orElse(null);
            ReligionDefinition religion = resolveReligion(state, settlement, kingdom);
            double festivity = religion.cultureModifiers().getOrDefault("festival_intensity", 1.0);
            // Festival days gently restore legitimacy / reduce unrest.
            boolean festivalDay = isFestivalDay(ctx.time().dayOfYear(), religion);
            work.enqueueCommit(() -> {
                if (festivalDay) {
                    settlement.setLegitimacy(Math.min(100, settlement.legitimacy() + 0.4 * festivity));
                    settlement.setUnrest(settlement.unrest() - 0.01 * festivity);
                }
                if (kingdom != null && (kingdom.religionKey() == null || "none".equals(kingdom.religionKey()))) {
                    kingdom.setReligionKey(religion.key());
                }
            });
        }
    }

    @Override
    public void phase2Global(CanonicalWorldState state, SimulationContext ctx) {
        if (!ctx.schedule().runGovernment()) return;

        for (KingdomState kingdom : state.kingdoms().values()) {
            ReligionDefinition religion = ReligionDefinition.byKey(kingdom.religionKey());
            if ("none".equals(kingdom.religionKey()) || kingdom.religionKey() == null) {
                CultureDefinition culture = cultures.get(kingdom.cultureId()).orElse(null);
                if (culture != null) {
                    religion = ReligionDefinition.byKey(culture.religionKey());
                    kingdom.setReligionKey(religion.key());
                }
            }
            double legitimacyBonus = religion.legitimacyBonus();
            kingdom.setLegitimacy(kingdom.legitimacy() + legitimacyBonus * 0.1);

            // Soft diplomacy lean: same-faith kingdoms drift friendlier.
            for (KingdomState other : state.kingdoms().values()) {
                if (other.id().equals(kingdom.id())) continue;
                if (!religion.key().equals(other.religionKey())) continue;
                DiplomaticRelation current = state.diplomacy().relation(kingdom.id(), other.id());
                if (current == DiplomaticRelation.NEUTRAL || current == DiplomaticRelation.TENSE) {
                    if (ctx.random().chance(0.02 * religion.marriageAllianceBias() + 0.01)) {
                        state.diplomacy().setRelation(kingdom.id(), other.id(), DiplomaticRelation.FRIENDLY);
                    }
                }
            }
        }
    }

    public ReligionDefinition resolveReligion(
            CanonicalWorldState state,
            SettlementState settlement,
            KingdomState kingdom
    ) {
        if (kingdom != null && kingdom.religionKey() != null && !"none".equals(kingdom.religionKey())) {
            return ReligionDefinition.byKey(kingdom.religionKey());
        }
        com.livingmods.common.id.CultureId cultureId = settlement == null ? null : findCulture(state, settlement);
        CultureDefinition culture = cultureId == null ? null : cultures.get(cultureId).orElse(null);
        if (culture != null) {
            return ReligionDefinition.byKey(culture.religionKey());
        }
        return ReligionDefinition.catalog().getFirst();
    }

    private com.livingmods.common.id.CultureId findCulture(CanonicalWorldState state, SettlementState settlement) {
        if (settlement.ownerKingdom().isPresent()) {
            KingdomState k = state.kingdoms().get(settlement.ownerKingdom().get());
            if (k != null) return k.cultureId();
        }
        for (var c : state.citizens().values()) {
            if (c.settlementId().equals(settlement.id())) {
                return c.cultureId();
            }
        }
        return null;
    }

    private static boolean isFestivalDay(int dayOfYear, ReligionDefinition religion) {
        int hash = Math.floorMod(religion.key().hashCode(), 60);
        return dayOfYear % 60 == hash || dayOfYear % 90 == (hash % 90);
    }
}
