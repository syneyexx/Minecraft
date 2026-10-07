package com.livingmods.simulation.engine;

import com.livingmods.common.event.CivilizationEventType;
import com.livingmods.common.event.HistoricalEvent;
import com.livingmods.common.geo.BlockPos2;
import com.livingmods.common.id.HistoricalEventId;
import com.livingmods.common.id.SettlementId;
import com.livingmods.simulation.CanonicalWorldState;
import com.livingmods.simulation.state.CitizenState;
import com.livingmods.simulation.state.HistoryMarkerState;
import com.livingmods.simulation.state.RumorState;
import com.livingmods.simulation.state.SettlementState;
import com.livingmods.simulation.tick.RegionalWork;
import com.livingmods.simulation.tick.SimulationContext;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Persists meaningful events, creates physicalization hooks, and spreads rumors
 * that citizens learn — dialogue must use citizen knowledge, not omniscience.
 */
public final class HistoryEngine implements SimulationSubsystem {
    private static final Set<CivilizationEventType> MEANINGFUL = EnumSet.of(
            CivilizationEventType.WAR_STARTED,
            CivilizationEventType.WAR_ENDED,
            CivilizationEventType.RULER_DIED,
            CivilizationEventType.SUCCESSION_RESOLVED,
            CivilizationEventType.EPIDEMIC_STARTED,
            CivilizationEventType.EPIDEMIC_ENDED,
            CivilizationEventType.TREATY_SIGNED,
            CivilizationEventType.TREATY_BROKEN,
            CivilizationEventType.MIGRATION_STARTED,
            CivilizationEventType.SETTLEMENT_FOUNDED,
            CivilizationEventType.SETTLEMENT_DESTROYED,
            CivilizationEventType.REBELLION_STARTED,
            CivilizationEventType.TECHNOLOGY_DISCOVERED,
            CivilizationEventType.HISTORICAL_MONUMENT_CREATED,
            CivilizationEventType.PLAYER_REPUTATION_CHANGED,
            CivilizationEventType.MARKET_CRISIS,
            CivilizationEventType.REFUGEE_GROUP_ENTERING_REGION
    );

    @Override
    public String name() { return "history"; }

    @Override
    public void phase1Regional(CanonicalWorldState state, RegionalWork work, SimulationContext ctx) {
        // Regional rumor aging / local learning.
        if (!ctx.schedule().runGovernment()) return;

        for (RumorState rumor : state.rumors().values()) {
            SettlementState origin = state.settlements().get(rumor.origin());
            if (origin == null || !origin.region().equals(work.region())) continue;
            work.enqueueCommit(() -> {
                rumor.tickAge();
                rumor.setConfidence(rumor.confidence() * 0.998);
                for (CitizenState c : state.citizens().values()) {
                    if (!c.alive() || !c.settlementId().equals(rumor.origin())) continue;
                    if (c.knownRumorIds().contains(rumor.id())) continue;
                    if (ctx.random().chance(0.05 * rumor.confidence())) {
                        c.knownRumorIds().add(rumor.id());
                    }
                }
            });
        }
    }

    @Override
    public void phase3Independent(CanonicalWorldState state, SimulationContext ctx) {
        if (!ctx.schedule().runGovernment()) return;

        // Bound history and drop trivial noise if any slipped in.
        while (state.history().size() > CanonicalWorldState.MAX_HISTORY_EVENTS) {
            state.history().removeFirst();
        }

        // Seed rumors + markers from recent meaningful events.
        List<HistoricalEvent> recent = recent(state, 12);
        for (HistoricalEvent event : recent) {
            if (!MEANINGFUL.contains(event.type())) continue;
            String rumorId = "rumor-" + event.id().value();
            if (state.rumors().containsKey(rumorId)) continue;

            SettlementId origin = inferOrigin(state, event);
            if (origin == null && !state.settlements().isEmpty()) {
                origin = state.settlements().keySet().iterator().next();
            }
            if (origin == null) continue;

            RumorState rumor = new RumorState(
                    rumorId,
                    event.id(),
                    event.title() + ": " + event.summary(),
                    origin,
                    event.location(),
                    confidenceFor(event.type()),
                    event.when()
            );
            state.rumors().put(rumorId, rumor);

            Optional<HistoryMarkerState> marker = markerFor(event);
            marker.ifPresent(m -> state.historyMarkers().put(m.id(), m));
        }

        // Spread rumors along nearby settlements.
        for (RumorState rumor : List.copyOf(state.rumors().values())) {
            if (rumor.confidence() < 0.05) continue;
            SettlementState origin = state.settlements().get(rumor.origin());
            if (origin == null) continue;
            for (CitizenState c : state.citizens().values()) {
                if (!c.alive() || c.knownRumorIds().contains(rumor.id())) continue;
                SettlementState home = state.settlements().get(c.settlementId());
                if (home == null) continue;
                double dist = home.center().distanceTo(origin.center());
                double chance = dist < 256 ? 0.08 : dist < 768 ? 0.03 : 0.005;
                if (ctx.random().chance(chance * rumor.confidence())) {
                    c.knownRumorIds().add(rumor.id());
                }
            }
        }
    }

    public List<HistoricalEvent> recent(CanonicalWorldState state, int limit) {
        List<HistoricalEvent> events = new ArrayList<>(state.historySnapshot());
        events.sort(Comparator.<HistoricalEvent>comparingLong(e -> e.when().absoluteTicks()).reversed());
        if (events.size() > limit) {
            return events.subList(0, limit);
        }
        return events;
    }

    public List<RumorState> rumorsKnownBy(CanonicalWorldState state, CitizenState citizen) {
        List<RumorState> list = new ArrayList<>();
        for (String id : citizen.knownRumorIds()) {
            RumorState rumor = state.rumors().get(id);
            if (rumor != null) {
                list.add(rumor);
            }
        }
        list.sort(Comparator.comparingInt(RumorState::ageHours));
        return list;
    }

    private static double confidenceFor(CivilizationEventType type) {
        return switch (type) {
            case WAR_STARTED, WAR_ENDED, RULER_DIED, SETTLEMENT_DESTROYED -> 0.9;
            case EPIDEMIC_STARTED, REBELLION_STARTED, TREATY_SIGNED -> 0.75;
            case MIGRATION_STARTED, SETTLEMENT_FOUNDED, TECHNOLOGY_DISCOVERED -> 0.6;
            default -> 0.45;
        };
    }

    private static SettlementId inferOrigin(CanonicalWorldState state, HistoricalEvent event) {
        String settlementTag = event.tags().get("settlement");
        if (settlementTag != null) {
            try {
                return SettlementId.of(java.util.UUID.fromString(settlementTag.replace("settlement:", "")));
            } catch (Exception ignored) {
            }
        }
        if (event.location().isPresent()) {
            BlockPos2 loc = event.location().get();
            SettlementState nearest = null;
            double best = Double.MAX_VALUE;
            for (SettlementState s : state.settlements().values()) {
                double d = s.center().distanceTo(loc);
                if (d < best) {
                    best = d;
                    nearest = s;
                }
            }
            if (nearest != null) return nearest.id();
        }
        return null;
    }

    private static Optional<HistoryMarkerState> markerFor(HistoricalEvent event) {
        if (event.location().isEmpty()) {
            return Optional.empty();
        }
        HistoryMarkerState.Kind kind = switch (event.type()) {
            case SETTLEMENT_DESTROYED -> HistoryMarkerState.Kind.RUIN;
            case WAR_STARTED, WAR_ENDED, REBELLION_STARTED -> HistoryMarkerState.Kind.BATTLE_SITE;
            case HISTORICAL_MONUMENT_CREATED, RULER_DIED, SUCCESSION_RESOLVED -> HistoryMarkerState.Kind.MONUMENT;
            case MIGRATION_STARTED, REFUGEE_GROUP_ENTERING_REGION -> HistoryMarkerState.Kind.ABANDONED_ROAD;
            case SETTLEMENT_FOUNDED -> HistoryMarkerState.Kind.RENAMED_PLACE;
            default -> null;
        };
        if (kind == null) {
            return Optional.empty();
        }
        String id = "marker-" + event.id().value();
        return Optional.of(new HistoryMarkerState(
                id,
                event.id(),
                kind,
                event.location().get(),
                event.title()
        ));
    }

    /** Helper for other engines to record only meaningful events. */
    public static void recordMeaningful(
            CanonicalWorldState state,
            CivilizationEventType type,
            SimulationContext ctx,
            String title,
            String summary,
            Optional<BlockPos2> location,
            java.util.Map<String, String> tags
    ) {
        if (!MEANINGFUL.contains(type) && type != CivilizationEventType.PLAYER_REPUTATION_CHANGED) {
            return;
        }
        state.appendHistory(new HistoricalEvent(
                HistoricalEventId.deterministic(state.seed(), state.history().size()),
                type,
                ctx.time(),
                title,
                summary,
                location,
                tags
        ));
    }
}
