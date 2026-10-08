package com.livingmods.simulation.engine;

import com.livingmods.common.culture.CultureKeys;
import com.livingmods.common.culture.CultureRegistry;
import com.livingmods.common.id.KingdomId;
import com.livingmods.common.id.SettlementId;
import com.livingmods.simulation.CanonicalWorldState;
import com.livingmods.simulation.state.KingdomState;
import com.livingmods.simulation.state.SettlementState;
import com.livingmods.simulation.physical.DynamicStructureRecord;

/**
 * Authoritative culture key resolution for physical intents.
 * Never treats a kingdom UUID string as a culture key.
 */
public final class CultureResolver {
    private static final CultureRegistry REGISTRY = new CultureRegistry();

    private CultureResolver() {}

    public static String forSettlement(CanonicalWorldState state, SettlementState settlement) {
        if (settlement == null) {
            return CultureKeys.DEFAULT;
        }
        // Prefer existing dynamic structure culture in the settlement.
        for (DynamicStructureRecord rec : state.dynamicPhysical().structures().values()) {
            if (rec.settlementId().equals(settlement.id()) && rec.cultureKey() != null && !rec.cultureKey().isBlank()) {
                String sanitized = CultureKeys.sanitize(rec.cultureKey());
                if (!sanitized.equals(CultureKeys.DEFAULT) || CultureKeys.DEFAULT.equals(rec.cultureKey())) {
                    if (REGISTRY.get(rec.cultureKey()).isPresent()) {
                        return rec.cultureKey();
                    }
                }
            }
        }
        return forKingdom(state, settlement.ownerKingdom().orElse(null));
    }

    public static String forKingdom(CanonicalWorldState state, KingdomId kingdomId) {
        if (kingdomId == null) {
            return CultureKeys.DEFAULT;
        }
        KingdomState kingdom = state.kingdoms().get(kingdomId);
        if (kingdom == null) {
            return CultureKeys.DEFAULT;
        }
        return CultureKeys.resolve(kingdom.cultureId(), REGISTRY);
    }

    public static String forSettlementId(CanonicalWorldState state, SettlementId settlementId) {
        if (settlementId == null) {
            return CultureKeys.DEFAULT;
        }
        SettlementState s = state.settlements().get(settlementId);
        return forSettlement(state, s);
    }
}
