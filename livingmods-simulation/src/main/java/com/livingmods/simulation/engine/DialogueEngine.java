package com.livingmods.simulation.engine;

import com.livingmods.common.id.CitizenId;
import com.livingmods.common.model.DiplomaticRelation;
import com.livingmods.common.model.ResourceType;
import com.livingmods.simulation.CanonicalWorldState;
import com.livingmods.simulation.state.CitizenState;
import com.livingmods.simulation.state.KingdomState;
import com.livingmods.simulation.state.MarketState;
import com.livingmods.simulation.state.SettlementState;
import com.livingmods.simulation.state.StockpileState;
import com.livingmods.simulation.tick.RegionalWork;
import com.livingmods.simulation.tick.SimulationContext;
import com.livingmods.simulation.tick.SimulationScheduler;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/** Template dialogue grounded in canonical facts — no LLM. */
public final class DialogueEngine implements SimulationSubsystem {
    public enum Topic {
        GREETING,
        SELF,
        PROFESSION,
        FAMILY,
        HEALTH,
        SETTLEMENT,
        RULER,
        KINGDOM,
        LAW,
        CRIME,
        TAX,
        FOOD,
        MARKET,
        TRADE,
        TECHNOLOGY,
        SCHOOL,
        WAR,
        ARMIES,
        GUARDS,
        ROUTES,
        MIGRATION,
        CULTURE,
        RELIGION,
        HISTORY,
        RUMORS,
        CALENDAR,
        RECENT_EVENTS,
        DIRECTIONS,
        OPINIONS,
        PLAYER_REPUTATION,
        EPIDEMIC
    }

    public record DialogueLine(String speaker, String text, Topic topic) {}

    @Override
    public String name() { return "dialogue"; }

    @Override
    public void phase1Regional(CanonicalWorldState state, RegionalWork work, SimulationContext ctx, SimulationScheduler scheduler) {}

    @Override
    public void phase3Independent(CanonicalWorldState state, SimulationContext ctx, SimulationScheduler scheduler) {}

    public List<DialogueLine> respond(CanonicalWorldState state, CitizenId citizenId, String intent) {
        Topic topic = detectIntent(intent);
        return linesFor(state, citizenId, topic);
    }

    public Topic detectIntent(String intent) {
        if (intent == null || intent.isBlank()) {
            return Topic.GREETING;
        }
        String t = intent.toLowerCase(Locale.ROOT);
        if (t.contains("bread") || t.contains("food") || t.contains("grain") || t.contains("hunger") || t.contains("famine")) {
            return Topic.FOOD;
        }
        if (t.contains("price") || t.contains("market") || t.contains("expensive") || t.contains("cheap")) {
            return Topic.MARKET;
        }
        if (t.contains("ruler") || t.contains("king") || t.contains("queen") || t.contains("lord")) {
            return Topic.RULER;
        }
        if (t.contains("war") || t.contains("battle") || t.contains("siege")) {
            return Topic.WAR;
        }
        if (t.contains("sick") || t.contains("plague") || t.contains("epidemic") || t.contains("disease")) {
            return Topic.EPIDEMIC;
        }
        if (t.contains("tax") || t.contains("levy")) {
            return Topic.TAX;
        }
        if (t.contains("crime") || t.contains("thief") || t.contains("bandit")) {
            return Topic.CRIME;
        }
        if (t.contains("trade") || t.contains("caravan")) {
            return Topic.TRADE;
        }
        if (t.contains("job") || t.contains("work") || t.contains("profession")) {
            return Topic.PROFESSION;
        }
        if (t.contains("who are you") || t.contains("yourself") || t.contains("name")) {
            return Topic.SELF;
        }
        if (t.contains("history") || t.contains("remember")) {
            return Topic.HISTORY;
        }
        if (t.contains("rumor") || t.contains("heard")) {
            return Topic.RUMORS;
        }
        if (t.contains("where") || t.contains("direction") || t.contains("road")) {
            return Topic.DIRECTIONS;
        }
        return Topic.GREETING;
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
            case SELF -> lines.add(new DialogueLine(name,
                    "I am " + citizen.givenName() + " " + citizen.familyName() + ", a "
                            + citizen.profession().name().toLowerCase(Locale.ROOT).replace('_', ' ') + ".",
                    topic));
            case PROFESSION -> lines.add(new DialogueLine(name,
                    "I work as a " + citizen.profession().name().toLowerCase(Locale.ROOT).replace('_', ' ') + ".",
                    topic));
            case FAMILY -> lines.add(new DialogueLine(name,
                    "My household keeps us together through hard seasons.", topic));
            case HEALTH -> lines.add(new DialogueLine(name,
                    citizen.health() < 0.4 ? "I feel poorly of late." : "I am well enough.", topic));
            case SETTLEMENT -> lines.add(new DialogueLine(name,
                    settlement == null ? "I wander." :
                            settlement.name() + " is a " + settlement.tier().name().toLowerCase(Locale.ROOT) + ".",
                    topic));
            case FOOD, MARKET -> lines.addAll(marketFoodLines(state, citizen, settlement, topic));
            case RULER -> {
                Optional<KingdomState> kingdom = settlement != null && settlement.ownerKingdom().isPresent()
                        ? state.kingdom(settlement.ownerKingdom().get())
                        : Optional.empty();
                String ruler = kingdom.map(k -> state.citizens().get(k.rulerId()))
                        .map(c -> c.givenName() + " " + c.familyName())
                        .orElse("no one");
                lines.add(new DialogueLine(name, "Our ruler is " + ruler + ".", topic));
            }
            case KINGDOM -> {
                Optional<KingdomState> kingdom = settlement != null && settlement.ownerKingdom().isPresent()
                        ? state.kingdom(settlement.ownerKingdom().get())
                        : Optional.empty();
                lines.add(new DialogueLine(name,
                        kingdom.map(k -> "We serve " + k.name() + ".").orElse("No kingdom claims me."),
                        topic));
            }
            case TAX -> {
                Optional<KingdomState> kingdom = settlement != null && settlement.ownerKingdom().isPresent()
                        ? state.kingdom(settlement.ownerKingdom().get())
                        : Optional.empty();
                double tax = kingdom.map(KingdomState::taxRate).orElse(0.1);
                lines.add(new DialogueLine(name,
                        "Taxes take about " + String.format(Locale.ROOT, "%.0f", tax * 100) + " of each harvest.",
                        topic));
            }
            case WAR -> {
                boolean atWar = state.wars().values().stream().anyMatch(w -> w.active());
                lines.add(new DialogueLine(name, atWar ? "War burdens us all." : "Peace holds, for now.", topic));
            }
            case EPIDEMIC -> {
                boolean sick = state.epidemics().values().stream().anyMatch(e -> e.active());
                lines.add(new DialogueLine(name, sick ? "Stay clear of the fever." : "We are healthy today.", topic));
            }
            case CRIME, LAW -> lines.add(new DialogueLine(name,
                    "The guards keep order when they can. Bandits prefer the frontier roads.", topic));
            case TRADE -> lines.add(new DialogueLine(name,
                    state.shipments().isEmpty()
                            ? "Few caravans pass lately."
                            : "Caravans still move goods between our towns.",
                    topic));
            case HISTORY -> {
                var hist = state.historySnapshot();
                if (hist.isEmpty()) {
                    lines.add(new DialogueLine(name, "Our founding is still young in memory.", topic));
                } else {
                    var last = hist.get(hist.size() - 1);
                    lines.add(new DialogueLine(name, "People still speak of: " + last.title() + ".", topic));
                }
            }
            case RUMORS, RECENT_EVENTS -> lines.add(new DialogueLine(name, rumorFromState(state, settlement), topic));
            case DIRECTIONS, ROUTES -> lines.add(new DialogueLine(name,
                    settlement == null ? "Follow the dirt track." :
                            "The roads from " + settlement.name() + " lead to the capital and neighboring towns.",
                    topic));
            case CULTURE, RELIGION -> lines.add(new DialogueLine(name,
                    "Our customs and faith shape every festival and law.", topic));
            case TECHNOLOGY, SCHOOL -> lines.add(new DialogueLine(name,
                    "Knowledge spreads slowly — scholars and apprentices carry it between settlements.", topic));
            case MIGRATION -> lines.add(new DialogueLine(name,
                    state.migrations().isEmpty()
                            ? "Few have left or arrived recently."
                            : "People are on the move — some fleeing, some seeking work.",
                    topic));
            case GUARDS, ARMIES -> lines.add(new DialogueLine(name,
                    "Guards watch the gates; armies march only when rulers demand it.", topic));
            case CALENDAR -> lines.add(new DialogueLine(name,
                    "It is " + state.time().toCalendar() + ".", topic));
            case OPINIONS, PLAYER_REPUTATION -> lines.add(new DialogueLine(name,
                    "Outsiders earn trust by trade, aid, and keeping the peace.", topic));
            default -> lines.add(new DialogueLine(name, "I have little to say on that.", topic));
        }
        return lines;
    }

    private List<DialogueLine> marketFoodLines(
            CanonicalWorldState state,
            CitizenState citizen,
            SettlementState settlement,
            Topic topic
    ) {
        List<DialogueLine> lines = new ArrayList<>();
        String name = citizen.givenName();
        if (settlement == null) {
            lines.add(new DialogueLine(name, "I have no market here.", topic));
            return lines;
        }
        MarketState market = state.markets().get(settlement.id());
        StockpileState stock = state.stockpiles().get(settlement.id());
        double grainPrice = market == null ? 1.0 : market.price(ResourceType.GRAIN);
        double grainStock = stock == null ? 0.0 : stock.get(ResourceType.GRAIN);
        double base = MarketState.basePrice(ResourceType.GRAIN);

        if (grainPrice > base * 1.4 || grainStock < 20) {
            String cause;
            if (grainStock < 10) {
                cause = "our grain stores are nearly empty";
            } else if (market != null && market.crisisSeverity() > 0.3) {
                cause = "a market crisis has gripped the stalls";
            } else if (!state.shipments().isEmpty()) {
                cause = "caravans cannot keep pace with demand";
            } else {
                cause = "the last harvest was thin";
            }
            lines.add(new DialogueLine(name,
                    "Bread is dear because " + cause + ". Grain sells for "
                            + String.format(Locale.ROOT, "%.2f", grainPrice) + ".",
                    topic));
        } else {
            lines.add(new DialogueLine(name,
                    "Grain sells for " + String.format(Locale.ROOT, "%.2f", grainPrice) + " — fair enough.",
                    topic));
        }
        return lines;
    }

    private String rumorFromState(CanonicalWorldState state, SettlementState settlement) {
        if (state.wars().values().stream().anyMatch(w -> w.active())) {
            return "They say banners are rising for war.";
        }
        if (state.epidemics().values().stream().anyMatch(e -> e.active())) {
            return "Whispers speak of fever in distant wards.";
        }
        if (settlement != null) {
            MarketState market = state.markets().get(settlement.id());
            if (market != null && market.price(ResourceType.GRAIN) > MarketState.basePrice(ResourceType.GRAIN) * 1.5) {
                return "Everyone complains about the price of bread.";
            }
        }
        return "Quiet days — only small talk in the square.";
    }

    public DiplomaticRelation relationHint(CanonicalWorldState state, KingdomState a, KingdomState b) {
        return state.diplomacy().relation(a.id(), b.id());
    }
}
