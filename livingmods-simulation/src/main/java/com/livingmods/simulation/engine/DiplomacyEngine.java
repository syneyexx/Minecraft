package com.livingmods.simulation.engine;

import com.livingmods.common.event.CivilizationEventType;
import com.livingmods.common.event.HistoricalEvent;
import com.livingmods.common.id.HistoricalEventId;
import com.livingmods.common.id.KingdomId;
import com.livingmods.common.id.TreatyId;
import com.livingmods.common.model.DiplomaticRelation;
import com.livingmods.common.model.TreatyType;
import com.livingmods.simulation.CanonicalWorldState;
import com.livingmods.simulation.state.DiplomacyState;
import com.livingmods.simulation.state.KingdomState;
import com.livingmods.simulation.tick.RegionalWork;
import com.livingmods.simulation.tick.SimulationContext;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class DiplomacyEngine implements SimulationSubsystem {
    @Override
    public String name() { return "diplomacy"; }

    @Override
    public void phase1Regional(CanonicalWorldState state, RegionalWork work, SimulationContext ctx) {}

    @Override
    public void phase2Global(CanonicalWorldState state, SimulationContext ctx) {
        if (!ctx.schedule().runDiplomacyDaily()) return;

        List<KingdomId> ids = new ArrayList<>(state.kingdoms().keySet());
        ids.sort(KingdomId::compareTo);
        DiplomacyState dip = state.diplomacy();

        for (int i = 0; i < ids.size(); i++) {
            for (int j = i + 1; j < ids.size(); j++) {
                KingdomId a = ids.get(i);
                KingdomId b = ids.get(j);
                DiplomaticRelation rel = dip.relation(a, b);
                if (rel == DiplomaticRelation.HOSTILE && ctx.random().chance(0.01)) {
                    rel = DiplomaticRelation.TENSE;
                    dip.setRelation(a, b, rel);
                }
            }
        }

        if (ctx.schedule().runDiplomacyWeekly() && ids.size() >= 2) {
            KingdomId a = ids.get(0);
            KingdomId b = ids.get(1);
            if (dip.relation(a, b) == DiplomaticRelation.NEUTRAL) {
                TreatyId tid = TreatyId.deterministic(state.seed(), dip.treaties().size());
                dip.treaties().put(tid, new DiplomacyState.TreatyRecord(
                        tid, a, b, TreatyType.TRADE, ctx.time().dayIndex()));
                dip.setRelation(a, b, DiplomaticRelation.FRIENDLY);
                KingdomState ka = state.kingdoms().get(a);
                state.appendHistory(new HistoricalEvent(
                        HistoricalEventId.deterministic(state.seed(), state.history().size()),
                        CivilizationEventType.TREATY_SIGNED,
                        ctx.time(),
                        "Trade treaty",
                        ka != null ? ka.name() + " signs trade with neighbor." : "Treaty signed.",
                        Optional.empty(),
                        Map.of("treaty", tid.toString())
                ));
            }
        }
    }
}
