package com.livingmods.simulation.engine;

import com.livingmods.common.event.CivilizationEventType;
import com.livingmods.common.event.HistoricalEvent;
import com.livingmods.common.id.HistoricalEventId;
import com.livingmods.common.id.KingdomId;
import com.livingmods.common.id.PlayerId;
import com.livingmods.simulation.CanonicalWorldState;
import com.livingmods.simulation.state.KingdomState;
import com.livingmods.simulation.tick.RegionalWork;
import com.livingmods.simulation.tick.SimulationContext;
import com.livingmods.simulation.tick.SimulationScheduler;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public final class PlayerSystemsEngine implements SimulationSubsystem {
    @Override
    public String name() { return "player_systems"; }

    @Override
    public void phase1Regional(CanonicalWorldState state, RegionalWork work, SimulationContext ctx, SimulationScheduler scheduler) {}

    @Override
    public void phase2Global(CanonicalWorldState state, SimulationContext ctx, SimulationScheduler scheduler) {
        if (!scheduler.shouldRunGovernment(ctx.time())) return;
        // Player realms use the same kingdom/settlement systems; decay reputation slowly toward neutral.
        for (var entry : state.playerReputation().reputationByPlayer().entrySet()) {
            for (Map.Entry<KingdomId, Double> rep : entry.getValue().entrySet()) {
                double v = rep.getValue() * 0.99;
                entry.getValue().put(rep.getKey(), v);
            }
        }
    }

    public void recordPlayerAction(
            CanonicalWorldState state,
            PlayerId player,
            KingdomId kingdom,
            double reputationDelta,
            SimulationContext ctx
    ) {
        state.playerReputation().adjust(player, kingdom, reputationDelta);
        KingdomState k = state.kingdoms().get(kingdom);
        state.appendHistory(new HistoricalEvent(
                HistoricalEventId.deterministic(state.seed(), state.history().size()),
                CivilizationEventType.PLAYER_REPUTATION_CHANGED,
                ctx.time(),
                "Player action",
                "Reputation adjusted for " + (k != null ? k.name() : kingdom),
                Optional.empty(),
                Map.of("player", player.toString(), "delta", String.valueOf(reputationDelta))
        ));
    }

    public static PlayerId demoPlayer() {
        return PlayerId.of(UUID.fromString("00000000-0000-4000-8000-000000000001"));
    }
}
