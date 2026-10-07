package com.livingmods.simulation.engine;

import com.livingmods.common.event.CivilizationEventType;
import com.livingmods.common.event.HistoricalEvent;
import com.livingmods.common.id.HistoricalEventId;
import com.livingmods.common.id.KingdomId;
import com.livingmods.common.model.Profession;
import com.livingmods.common.model.ResourceType;
import com.livingmods.common.model.TechnologyDefinition;
import com.livingmods.simulation.CanonicalWorldState;
import com.livingmods.simulation.state.CitizenState;
import com.livingmods.simulation.state.SettlementState;
import com.livingmods.simulation.state.StockpileState;
import com.livingmods.simulation.state.TechnologyState;
import com.livingmods.simulation.tick.RegionalWork;
import com.livingmods.simulation.tick.SimulationContext;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Local knowledge research + school transmission. Ensures advanced_agriculture is reachable
 * once agriculture, iron_working, and literacy are known.
 */
public final class TechnologyEngine implements SimulationSubsystem {
    @Override
    public String name() { return "technology"; }

    @Override
    public void phase1Regional(CanonicalWorldState state, RegionalWork work, SimulationContext ctx) {
        if (!ctx.schedule().runTechnology()) return;

        List<SettlementState> regional = new ArrayList<>();
        for (SettlementState s : state.settlements().values()) {
            if (s.region().equals(work.region())) regional.add(s);
        }
        regional.sort(Comparator.comparing(s -> s.id().value()));

        TechnologyState tech = state.technology();
        for (SettlementState s : regional) {
            int teachers = 0;
            int scholars = 0;
            int students = 0;
            double literacySum = 0.0;
            int alive = 0;
            for (CitizenState c : state.citizens().values()) {
                if (!c.alive() || !c.settlementId().equals(s.id())) continue;
                alive++;
                literacySum += c.literacy();
                if (c.profession() == Profession.TEACHER) teachers++;
                if (c.profession() == Profession.SCHOLAR) scholars++;
                if (c.isChild(ctx.time()) || c.profession() == Profession.CHILD) students++;
            }
            int schoolCap = Math.max(teachers * 8, tech.schoolCapacity(s.id()));
            if (tech.knows(s.id(), TechnologyDefinition.LITERACY)) {
                schoolCap = Math.max(schoolCap, 4);
            }
            final int finalSchoolCap = schoolCap;
            final int finalTeachers = teachers;
            final int finalScholars = scholars;
            final int finalStudents = students;
            final int finalAlive = Math.max(1, alive);
            final double avgLiteracy = literacySum / finalAlive;

            work.enqueueCommit(() -> {
                tech.setSchoolCapacity(s.id(), finalSchoolCap);
                double literacyGain = 0.002 * finalTeachers + 0.001 * finalScholars;
                if (finalSchoolCap > 0 && finalStudents > 0) {
                    literacyGain += 0.004 * Math.min(1.0, (double) finalStudents / finalSchoolCap);
                }
                tech.setLiteracy(s.id(), tech.literacy(s.id()) * 0.98 + Math.max(avgLiteracy, literacyGain));
                StockpileState stock = state.stockpiles().get(s.id());
                if (stock != null && (finalTeachers > 0 || finalScholars > 0)) {
                    stock.add(ResourceType.KNOWLEDGE, 0.2 * finalTeachers + 0.35 * finalScholars);
                }
            });

            // Teach children / apprentices in schools.
            for (CitizenState c : state.citizens().values()) {
                if (!c.alive() || !c.settlementId().equals(s.id())) continue;
                if (schoolCap <= 0) break;
                if (c.isChild(ctx.time()) || c.profession() == Profession.TEACHER
                        || c.profession() == Profession.SCHOLAR) {
                    work.enqueueCommit(() -> {
                        c.setLiteracy(c.literacy() + 0.01 * Math.max(1, finalTeachers));
                        c.setSkill(c.skill() + 0.005);
                        if (c.isChild(ctx.time())) {
                            c.setEducationYears(c.educationYears() + (finalTeachers > 0 ? 1 : 0));
                        }
                    });
                }
            }

            Set<String> known = tech.known(s.id());
            Map<String, Double> progress = tech.progress(s.id());
            for (TechnologyDefinition def : TechnologyDefinition.catalog()) {
                if (known.contains(def.key())) continue;
                if (!prerequisitesMet(known, def)) continue;
                if (def.requiresSchool() && schoolCap <= 0 && teachers + scholars == 0) continue;

                double knowledgeBoost = 0.0;
                StockpileState stock = state.stockpiles().get(s.id());
                if (stock != null) {
                    knowledgeBoost = Math.min(0.05, stock.get(ResourceType.KNOWLEDGE) * 0.002);
                }
                double literacyBoost = tech.literacy(s.id()) * 0.03;
                double scholarBoost = 0.01 * scholars + 0.008 * teachers;
                double delta = (0.04 + scholarBoost + literacyBoost + knowledgeBoost) / Math.max(0.4, def.researchDifficulty());
                double next = progress.getOrDefault(def.key(), 0.0) + delta;
                if (next >= 1.0) {
                    String techKey = def.key();
                    work.enqueueCommit(() -> {
                        known.add(techKey);
                        progress.remove(techKey);
                        s.ownerKingdom().ifPresent(k -> tech.factionKnown(k).add(techKey));
                        state.appendHistory(new HistoricalEvent(
                                HistoricalEventId.deterministic(state.seed(), state.history().size()),
                                CivilizationEventType.TECHNOLOGY_DISCOVERED,
                                ctx.time(),
                                "Technology discovered",
                                def.displayName() + " mastered in " + s.name(),
                                Optional.of(s.center()),
                                Map.of("settlement", s.id().toString(), "tech", techKey)
                        ));
                    });
                } else {
                    double finalNext = next;
                    String techKey = def.key();
                    work.enqueueCommit(() -> progress.put(techKey, finalNext));
                }
            }
        }
    }

    @Override
    public void phase2Global(CanonicalWorldState state, SimulationContext ctx) {
        if (!ctx.schedule().runTechnology()) return;

        List<SettlementState> settlements = new ArrayList<>(state.settlements().values());
        settlements.sort(Comparator.comparing(s -> s.id().value()));

        // Knowledge diffusion along proximity + trade-like contact.
        for (SettlementState source : settlements) {
            Set<String> sourceKnown = state.technology().known(source.id());
            if (sourceKnown.isEmpty()) continue;
            for (SettlementState neighbor : settlements) {
                if (neighbor.id().equals(source.id())) continue;
                if (source.center().distanceTo(neighbor.center()) > 640) continue;
                Set<String> known = state.technology().known(neighbor.id());
                for (String techKey : sourceKnown) {
                    if (known.contains(techKey)) continue;
                    TechnologyDefinition def = TechnologyDefinition.byKey(techKey);
                    if (def == null || !prerequisitesMet(known, def)) continue;
                    double chance = 0.04 + state.technology().literacy(neighbor.id()) * 0.08;
                    if (ctx.random().chance(chance)) {
                        known.add(techKey);
                        neighbor.ownerKingdom().ifPresent(k ->
                                state.technology().factionKnown(k).add(techKey));
                        state.appendHistory(new HistoricalEvent(
                                HistoricalEventId.deterministic(state.seed(), state.history().size()),
                                CivilizationEventType.TECHNOLOGY_DISCOVERED,
                                ctx.time(),
                                "Knowledge spread",
                                techKey + " reaches " + neighbor.name(),
                                Optional.of(neighbor.center()),
                                Map.of("settlement", neighbor.id().toString(), "tech", techKey)
                        ));
                        return;
                    }
                }
            }
        }

        // Faction-level knowledge roll-up.
        for (SettlementState s : settlements) {
            Optional<KingdomId> kingdom = s.ownerKingdom();
            if (kingdom.isEmpty()) continue;
            state.technology().factionKnown(kingdom.get())
                    .addAll(state.technology().known(s.id()));
        }
    }

    /** Production multiplier from known technologies for a resource. */
    public static double productionMultiplier(CanonicalWorldState state, SettlementState settlement, ResourceType type) {
        double mult = 1.0;
        for (String key : state.technology().known(settlement.id())) {
            TechnologyDefinition def = TechnologyDefinition.byKey(key);
            if (def == null) continue;
            mult *= def.productionMultipliers().getOrDefault(type, 1.0);
        }
        return mult;
    }

    private static boolean prerequisitesMet(Set<String> known, TechnologyDefinition def) {
        return known.containsAll(def.prerequisites());
    }
}
