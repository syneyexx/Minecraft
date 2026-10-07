package com.livingmods.simulation.state;

import com.livingmods.common.id.KingdomId;
import com.livingmods.common.id.TreatyId;
import com.livingmods.common.model.DiplomaticRelation;
import com.livingmods.common.model.TreatyType;

import java.util.HashMap;
import java.util.Map;

public final class DiplomacyState {
    private final Map<DiplomacyPair, DiplomaticRelation> relations = new HashMap<>();
    private final Map<TreatyId, TreatyRecord> treaties = new HashMap<>();

    public DiplomaticRelation relation(KingdomId a, KingdomId b) {
        if (a.equals(b)) return DiplomaticRelation.ALLIED;
        return relations.getOrDefault(new DiplomacyPair(a, b), DiplomaticRelation.NEUTRAL);
    }

    public void setRelation(KingdomId a, KingdomId b, DiplomaticRelation relation) {
        relations.put(new DiplomacyPair(a, b), relation);
    }

    public Map<DiplomacyPair, DiplomaticRelation> relations() { return relations; }
    public Map<TreatyId, TreatyRecord> treaties() { return treaties; }

    public record TreatyRecord(TreatyId id, KingdomId a, KingdomId b, TreatyType type, long signedDay) {}
}
