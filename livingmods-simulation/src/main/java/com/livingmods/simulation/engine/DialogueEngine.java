package com.livingmods.simulation.engine;

import com.livingmods.common.id.CitizenId;
import com.livingmods.common.id.PlayerId;
import com.livingmods.common.model.DiplomaticRelation;
import com.livingmods.common.model.FactionStanding;
import com.livingmods.common.model.ResourceType;
import com.livingmods.common.model.TechnologyDefinition;
import com.livingmods.simulation.CanonicalWorldState;
import com.livingmods.simulation.state.CitizenState;
import com.livingmods.simulation.state.KingdomState;
import com.livingmods.simulation.state.MarketState;
import com.livingmods.simulation.state.RumorState;
import com.livingmods.simulation.state.SettlementState;
import com.livingmods.simulation.state.StockpileState;
import com.livingmods.simulation.tick.RegionalWork;
import com.livingmods.simulation.tick.SimulationContext;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/** Template dialogue grounded in canonical facts — no LLM. Dutch + English intents. */
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
    public void phase1Regional(CanonicalWorldState state, RegionalWork work, SimulationContext ctx) {}

    @Override
    public void phase3Independent(CanonicalWorldState state, SimulationContext ctx) {}

    public List<DialogueLine> respond(CanonicalWorldState state, CitizenId citizenId, String intent) {
        return respond(state, citizenId, intent, null);
    }

    public List<DialogueLine> respond(
            CanonicalWorldState state,
            CitizenId citizenId,
            String intent,
            PlayerId player
    ) {
        Topic topic = detectIntent(intent);
        return linesFor(state, citizenId, topic, player);
    }

    public Topic detectIntent(String intent) {
        if (intent == null || intent.isBlank()) {
            return Topic.GREETING;
        }
        String t = intent.toLowerCase(Locale.ROOT);
        if (containsAny(t, "bread", "food", "grain", "hunger", "famine", "brood", "eten", "voedsel", "honger", "graan")) {
            return Topic.FOOD;
        }
        if (containsAny(t, "price", "market", "expensive", "cheap", "prijs", "markt", "duur", "goedkoop")) {
            return Topic.MARKET;
        }
        if (containsAny(t, "ruler", "king", "queen", "lord", "heerser", "koning", "koningin", "vorst")) {
            return Topic.RULER;
        }
        if (containsAny(t, "war", "battle", "siege", "oorlog", "slag", "beleg")) {
            return Topic.WAR;
        }
        if (containsAny(t, "sick", "plague", "epidemic", "disease", "ziek", "pest", "ziekte", "koorts")) {
            return Topic.EPIDEMIC;
        }
        if (containsAny(t, "tax", "levy", "belasting", "heffing")) {
            return Topic.TAX;
        }
        if (containsAny(t, "crime", "thief", "bandit", "misdaad", "dief", "rover")) {
            return Topic.CRIME;
        }
        if (containsAny(t, "trade", "caravan", "handel", "karavaan")) {
            return Topic.TRADE;
        }
        if (containsAny(t, "job", "work", "profession", "werk", "beroep")) {
            return Topic.PROFESSION;
        }
        if (containsAny(t, "who are you", "yourself", "name", "wie ben je", "wie ben jij", "naam")) {
            return Topic.SELF;
        }
        if (containsAny(t, "history", "remember", "geschiedenis", "herinner")) {
            return Topic.HISTORY;
        }
        if (containsAny(t, "rumor", "heard", "gerucht", "gehoord", "roddel")) {
            return Topic.RUMORS;
        }
        if (containsAny(t, "where", "direction", "road", "waar", "richting", "weg")) {
            return Topic.DIRECTIONS;
        }
        if (containsAny(t, "tech", "school", "knowledge", "school", "kennis", "technologie")) {
            return Topic.TECHNOLOGY;
        }
        if (containsAny(t, "religion", "faith", "god", "geloof", "tempel", "kerk")) {
            return Topic.RELIGION;
        }
        if (containsAny(t, "reputation", "trust", "reputatie", "vertrouwen")) {
            return Topic.PLAYER_REPUTATION;
        }
        if (containsAny(t, "migration", "refugee", "vluchteling", "migratie")) {
            return Topic.MIGRATION;
        }
        if (containsAny(t, "hello", "hi", "greet", "hallo", "dag", "goedendag")) {
            return Topic.GREETING;
        }
        return Topic.GREETING;
    }

    public List<DialogueLine> linesFor(CanonicalWorldState state, CitizenId citizenId, Topic topic) {
        return linesFor(state, citizenId, topic, null);
    }

    public List<DialogueLine> linesFor(
            CanonicalWorldState state,
            CitizenId citizenId,
            Topic topic,
            PlayerId player
    ) {
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
            case FAMILY -> {
                String family;
                if (citizen.spouseId() != null && state.citizens().get(citizen.spouseId()) != null) {
                    CitizenState spouse = state.citizens().get(citizen.spouseId());
                    family = "I am bound to " + spouse.givenName() + " of household "
                            + citizen.familyName() + ".";
                } else if (citizen.isChild(state.time())) {
                    family = "I still live under my parents' roof.";
                } else {
                    family = "My household keeps us together through hard seasons.";
                }
                lines.add(new DialogueLine(name, family, topic));
            }
            case HEALTH -> lines.add(new DialogueLine(name,
                    citizen.health() < 30.0 ? "I feel poorly of late." : "I am well enough.", topic));
            case SETTLEMENT -> lines.add(new DialogueLine(name,
                    settlement == null ? "I wander." :
                            settlement.name() + " is a " + settlement.tier().name().toLowerCase(Locale.ROOT)
                                    + (settlement.hunger() > 0.4 ? " — and hungry." : "."),
                    topic));
            case FOOD, MARKET -> lines.addAll(marketFoodLines(state, citizen, settlement, topic));
            case RULER -> {
                Optional<KingdomState> kingdom = kingdomOf(state, settlement);
                String ruler = kingdom.map(k -> state.citizens().get(k.rulerId()))
                        .map(c -> c.givenName() + " " + c.familyName())
                        .orElse("no one");
                lines.add(new DialogueLine(name, "Our ruler is " + ruler + ".", topic));
            }
            case KINGDOM -> {
                Optional<KingdomState> kingdom = kingdomOf(state, settlement);
                lines.add(new DialogueLine(name,
                        kingdom.map(k -> "We serve " + k.name() + ".").orElse("No kingdom claims me."),
                        topic));
            }
            case TAX -> {
                Optional<KingdomState> kingdom = kingdomOf(state, settlement);
                double tax = kingdom.map(KingdomState::taxRate).orElse(0.1);
                lines.add(new DialogueLine(name,
                        "Taxes take about " + String.format(Locale.ROOT, "%.0f", tax * 100) + " of each harvest.",
                        topic));
            }
            case WAR -> {
                boolean atWar = false;
                Optional<KingdomState> kingdom = kingdomOf(state, settlement);
                if (kingdom.isPresent()) {
                    KingdomState k = kingdom.get();
                    atWar = state.wars().values().stream()
                            .anyMatch(w -> w.active() && w.participants().contains(k.id()));
                }
                // Citizen may not know distant wars — only if rumor known or local kingdom at war.
                boolean heard = citizenKnowsTopic(state, citizen, "War") || atWar;
                lines.add(new DialogueLine(name,
                        heard ? (atWar ? "War burdens us all." : "I have heard banners rising elsewhere.")
                                : "If there is war, word has not reached me.",
                        topic));
            }
            case EPIDEMIC -> {
                boolean localSick = state.epidemics().values().stream().anyMatch(e ->
                        e.active() && settlement != null && e.affectedSettlements().contains(settlement.id()));
                boolean heard = localSick || citizenKnowsTopic(state, citizen, "Epidemic")
                        || citizenKnowsTopic(state, citizen, "fever")
                        || citizenKnowsTopic(state, citizen, "Pox");
                lines.add(new DialogueLine(name,
                        localSick ? "Stay clear of the fever in our wards."
                                : heard ? "Whispers speak of sickness on the roads."
                                : "We seem healthy enough today.",
                        topic));
            }
            case CRIME, LAW -> lines.add(new DialogueLine(name,
                    settlement != null && settlement.security() < 0.3
                            ? "Bandits grow bold — the guards are thin."
                            : "The guards keep order when they can.",
                    topic));
            case TRADE -> lines.add(new DialogueLine(name,
                    state.shipments().isEmpty()
                            ? "Few caravans pass lately."
                            : "Caravans still move goods between our towns.",
                    topic));
            case HISTORY -> {
                List<RumorState> known = new HistoryEngine().rumorsKnownBy(state, citizen);
                if (known.isEmpty()) {
                    lines.add(new DialogueLine(name, "I know little of distant chronicles.", topic));
                } else {
                    RumorState last = known.get(known.size() - 1);
                    lines.add(new DialogueLine(name, "I recall: " + last.subject() + ".", topic));
                }
            }
            case RUMORS, RECENT_EVENTS -> lines.add(new DialogueLine(name, rumorFromKnowledge(state, citizen, settlement), topic));
            case DIRECTIONS, ROUTES -> lines.add(new DialogueLine(name,
                    settlement == null ? "Follow the dirt track." :
                            "The roads from " + settlement.name() + " lead to the capital and neighboring towns.",
                    topic));
            case CULTURE, RELIGION -> {
                Optional<KingdomState> kingdom = kingdomOf(state, settlement);
                String faith = kingdom.map(KingdomState::religionKey).orElse("old ways");
                lines.add(new DialogueLine(name,
                        "Our faith is the " + faith.replace('_', ' ') + " — it shapes festival and law.",
                        topic));
            }
            case TECHNOLOGY, SCHOOL -> {
                String techNote = "Knowledge spreads slowly.";
                if (settlement != null) {
                    if (state.technology().knows(settlement.id(), TechnologyDefinition.ADVANCED_AGRICULTURE)) {
                        techNote = "Our fields use advanced agriculture now.";
                    } else if (state.technology().knows(settlement.id(), TechnologyDefinition.IRON_WORKING)) {
                        techNote = "The forge masters iron working here.";
                    } else if (state.technology().schoolCapacity(settlement.id()) > 0) {
                        techNote = "The school teaches letters and trades to the young.";
                    }
                }
                lines.add(new DialogueLine(name, techNote, topic));
            }
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
                    reputationLine(state, settlement, player), topic));
            default -> lines.add(new DialogueLine(name, "I have little to say on that.", topic));
        }
        return lines;
    }

    private static String reputationLine(CanonicalWorldState state, SettlementState settlement, PlayerId player) {
        if (player == null || settlement == null || settlement.ownerKingdom().isEmpty()) {
            return "Outsiders earn trust by trade, aid, and keeping the peace.";
        }
        var kingdomId = settlement.ownerKingdom().get();
        double rep = state.playerReputation().reputation(player, kingdomId);
        FactionStanding standing = state.playerReputation().standing(player, kingdomId);
        if (standing.atLeast(FactionStanding.OFFICIAL)) {
            return "You walk as an official among us (standing " + standing.name().toLowerCase(Locale.ROOT) + ").";
        }
        if (rep > 0.3) {
            return "Your deeds are spoken of kindly here.";
        }
        if (rep < -0.2) {
            return "Guards watch you closely — your name carries a stain.";
        }
        return "You are still mostly a stranger to us.";
    }

    private static boolean citizenKnowsTopic(CanonicalWorldState state, CitizenState citizen, String needle) {
        String n = needle.toLowerCase(Locale.ROOT);
        for (String id : citizen.knownRumorIds()) {
            RumorState rumor = state.rumors().get(id);
            if (rumor != null && rumor.subject().toLowerCase(Locale.ROOT).contains(n)) {
                return true;
            }
        }
        return false;
    }

    private String rumorFromKnowledge(CanonicalWorldState state, CitizenState citizen, SettlementState settlement) {
        List<RumorState> known = new HistoryEngine().rumorsKnownBy(state, citizen);
        if (!known.isEmpty()) {
            RumorState rumor = known.get(known.size() - 1);
            String hedge = rumor.confidence() > 0.7 ? "They say " : "I heard faintly that ";
            return hedge + rumor.subject() + ".";
        }
        // Fall back only to local observable facts — not global omniscience.
        if (settlement != null) {
            MarketState market = state.markets().get(settlement.id());
            if (market != null && market.price(ResourceType.GRAIN) > MarketState.basePrice(ResourceType.GRAIN) * 1.5) {
                return "Everyone complains about the price of bread.";
            }
            if (state.epidemics().values().stream().anyMatch(e ->
                    e.active() && e.affectedSettlements().contains(settlement.id()))) {
                return "People cough behind closed shutters tonight.";
            }
        }
        return "Quiet days — only small talk in the square.";
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

    private static Optional<KingdomState> kingdomOf(CanonicalWorldState state, SettlementState settlement) {
        if (settlement == null || settlement.ownerKingdom().isEmpty()) {
            return Optional.empty();
        }
        return state.kingdom(settlement.ownerKingdom().get());
    }

    private static boolean containsAny(String text, String... needles) {
        for (String n : needles) {
            if (text.contains(n)) return true;
        }
        return false;
    }

    public DiplomaticRelation relationHint(CanonicalWorldState state, KingdomState a, KingdomState b) {
        return state.diplomacy().relation(a.id(), b.id());
    }
}
