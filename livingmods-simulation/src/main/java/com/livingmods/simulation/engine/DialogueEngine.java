package com.livingmods.simulation.engine;

import com.livingmods.common.id.CitizenId;
import com.livingmods.common.model.DiplomaticRelation;
import com.livingmods.common.model.ResourceType;
import com.livingmods.simulation.CanonicalWorldState;
import com.livingmods.simulation.state.CitizenState;
import com.livingmods.simulation.state.KingdomState;
import com.livingmods.simulation.state.SettlementState;
import com.livingmods.simulation.tick.RegionalWork;
import com.livingmods.simulation.tick.SimulationContext;
import com.livingmods.simulation.tick.SimulationScheduler;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Template dialogue grounded in canonical facts — no LLM. */
public final class DialogueEngine implements SimulationSubsystem {
    public enum Topic {
        GREETING,
        MARKET,
        RULER,
        WAR,
        EPIDEMIC
    }

    public record DialogueLine(String speaker, String text, Topic topic) {}

    @Override
    public String name() { return "dialogue"; }

    @Override
    public void phase1Regional(CanonicalWorldState state, RegionalWork work, SimulationContext ctx, SimulationScheduler scheduler) {}

    @Override
    public void phase3Independent(CanonicalWorldState state, SimulationContext ctx, SimulationScheduler scheduler) {
        // Dialogue is pull-based; phase 3 reserved for cache warming if needed.
    }

    public List<DialogueLine> linesFor(CanonicalWorldState state, CitizenId citizenId, Topic topic) {
        CitizenState citizen = state.citizens().get(citizenId);
        if (citizen == null || !citizen.alive()) {
            return List.of(new DialogueLine("unknown", "...", Topic.GREETING));
        }
        SettlementState settlement = state.settlements().get(citizen.settlementId());
        List<DialogueLine> lines = new ArrayList<>();
        String name = citizen.givenName();

        switch (topic) {
            case GREETING -> lines.add(new DialogueLine(name,
                    "Greetings from " + (settlement != null ? settlement.name() : "the road") + ".",
                    topic));
            case MARKET -> {
                double grain = settlement != null && state.markets().get(settlement.id()) != null
                        ? state.markets().get(settlement.id()).price(ResourceType.GRAIN) : 1.0;
                lines.add(new DialogueLine(name,
                        "Grain sells for " + String.format("%.2f", grain) + " here.", topic));
            }
            case RULER -> {
                Optional<KingdomState> kingdom = settlement != null && settlement.ownerKingdom().isPresent()
                        ? state.kingdom(settlement.ownerKingdom().get())
                        : Optional.empty();
                String ruler = kingdom.map(k -> state.citizens().get(k.rulerId()))
                        .map(c -> c.givenName() + " " + c.familyName())
                        .orElse("no one");
                lines.add(new DialogueLine(name, "Our ruler is " + ruler + ".", topic));
            }
            case WAR -> {
                boolean atWar = state.wars().values().stream().anyMatch(w -> w.active());
                lines.add(new DialogueLine(name, atWar ? "War burdens us all." : "Peace holds, for now.", topic));
            }
            case EPIDEMIC -> {
                boolean sick = state.epidemics().values().stream().anyMatch(e -> e.active());
                lines.add(new DialogueLine(name, sick ? "Stay clear of the fever." : "We are healthy today.", topic));
            }
        }
        return lines;
    }

    public DiplomaticRelation relationHint(CanonicalWorldState state, KingdomState a, KingdomState b) {
        return state.diplomacy().relation(a.id(), b.id());
    }
}
