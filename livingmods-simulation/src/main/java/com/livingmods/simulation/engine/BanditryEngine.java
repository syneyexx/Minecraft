package com.livingmods.simulation.engine;

import com.livingmods.common.event.CivilizationEventType;
import com.livingmods.common.event.HistoricalEvent;
import com.livingmods.common.geo.BlockPos2;
import com.livingmods.common.geo.BoundingBox2;
import com.livingmods.common.id.HistoricalEventId;
import com.livingmods.common.id.PhysicalIntentId;
import com.livingmods.common.id.SettlementId;
import com.livingmods.common.model.PhysicalIntentStatus;
import com.livingmods.common.model.PhysicalIntentType;
import com.livingmods.simulation.CanonicalWorldState;
import com.livingmods.simulation.physical.PhysicalIntent;
import com.livingmods.simulation.state.MarketState;
import com.livingmods.simulation.state.SettlementState;
import com.livingmods.simulation.state.ShipmentState;
import com.livingmods.simulation.tick.RegionalWork;
import com.livingmods.simulation.tick.SimulationContext;

import java.util.Map;
import java.util.Optional;

/**
 * Dynamic banditry from canonical causes (security, trade, unrest, war) —
 * not seed-time random placement alone.
 */
public final class BanditryEngine implements SimulationSubsystem {
    @Override
    public String name() { return "banditry"; }

    @Override
    public void phase1Regional(CanonicalWorldState state, RegionalWork work, SimulationContext ctx) {}

    @Override
    public void phase2Global(CanonicalWorldState state, SimulationContext ctx) {
        if (!ctx.schedule().runGovernment()) return;

        int activeCamps = 0;
        for (PhysicalIntent intent : state.dynamicPhysical().intents().values()) {
            if ((intent.type() == PhysicalIntentType.CREATE_BANDIT_CAMP
                    || intent.type() == PhysicalIntentType.UPGRADE_BANDIT_CAMP)
                    && intent.status().isActive()) {
                activeCamps++;
            }
        }
        if (activeCamps >= 12) return;

        for (SettlementState s : state.settlements().values()) {
            double risk = banditRisk(state, s);
            if (risk < 0.45) continue;
            if (state.dynamicPhysical().hasOpenIntent(s.id(), PhysicalIntentType.CREATE_BANDIT_CAMP)) {
                continue;
            }
            long h = s.id().value().getMostSignificantBits() ^ state.time().absoluteTicks();
            int dist = 48 + (int) (Math.abs(h) % 40);
            int angle = (int) (Math.abs(h >> 11) % 360);
            double rad = Math.toRadians(angle);
            BlockPos2 site = BlockPos2.of(
                    s.center().x() + (int) Math.round(Math.cos(rad) * dist),
                    s.center().z() + (int) Math.round(Math.sin(rad) * dist)
            );
            String variant = risk > 0.75 ? "STRONGHOLD" : risk > 0.6 ? "FOREST_CAMP" : "ROAD_CAMP";
            PhysicalIntentId id = PhysicalIntentId.deterministic(
                    state.seed(), state.dynamicPhysical().intents().size() + 300_000L + s.id().hashCode());
            PhysicalIntent intent = new PhysicalIntent(
                    id,
                    PhysicalIntentType.CREATE_BANDIT_CAMP,
                    "banditry",
                    id.value(),
                    Optional.of(s.id()),
                    s.ownerKingdom(),
                    Optional.empty(),
                    Optional.empty(),
                    site,
                    BoundingBox2.around(site, variant.equals("STRONGHOLD") ? 10 : 6),
                    1,
                    PhysicalIntentStatus.READY,
                    ctx.time(),
                    3,
                    Map.of(
                            "cause", riskCause(state, s),
                            "variant", variant,
                            "campId", id.value().toString(),
                            "strength", String.format(java.util.Locale.ROOT, "%.2f", risk)
                    ),
                    Map.of(),
                    ""
            );
            seedChunks(intent);
            state.dynamicPhysical().putIntent(intent);
            s.setSecurity(Math.max(0, s.security() - 0.04));
            state.tradeNetwork().addHotspot(site);

            state.appendHistory(new HistoricalEvent(
                    HistoricalEventId.deterministic(state.seed(), state.history().size()),
                    CivilizationEventType.BANDIT_ACTIVITY_CHANGED,
                    ctx.time(),
                    "Bandit camp forms",
                    variant + " near " + s.name(),
                    Optional.of(site),
                    Map.of("settlement", s.id().toString(), "variant", variant)
            ));
            activeCamps++;
            if (activeCamps >= 12) break;
        }

        for (PhysicalIntent intent : state.dynamicPhysical().intents().values()) {
            if (intent.type() != PhysicalIntentType.CREATE_BANDIT_CAMP) continue;
            if (intent.status() != PhysicalIntentStatus.MATERIALIZED) continue;
            SettlementId settlementId = intent.settlementId().orElse(null);
            if (settlementId == null) continue;
            SettlementState s = state.settlements().get(settlementId);
            if (s == null || banditRisk(state, s) < 0.7) continue;
            if (state.dynamicPhysical().hasOpenIntent(s.id(), PhysicalIntentType.UPGRADE_BANDIT_CAMP)) continue;
            PhysicalIntentId upId = PhysicalIntentId.deterministic(
                    state.seed(), state.dynamicPhysical().intents().size() + 310_000L);
            PhysicalIntent upgrade = new PhysicalIntent(
                    upId,
                    PhysicalIntentType.UPGRADE_BANDIT_CAMP,
                    "banditry",
                    intent.sourceEntityId() != null ? intent.sourceEntityId() : upId.value(),
                    Optional.of(s.id()),
                    s.ownerKingdom(),
                    Optional.empty(),
                    Optional.empty(),
                    intent.targetPosition(),
                    intent.footprint().expand(4),
                    intent.revision() + 1,
                    PhysicalIntentStatus.READY,
                    ctx.time(),
                    3,
                    Map.of("cause", "entrenched", "variant", "RUINED_FORT",
                            "campId", intent.provenance().getOrDefault("campId", intent.id().value().toString())),
                    Map.of(),
                    ""
            );
            upgrade.addDependency(intent.id());
            seedChunks(upgrade);
            state.dynamicPhysical().putIntent(upgrade);
        }
    }

    private static void seedChunks(PhysicalIntent intent) {
        int minCx = intent.footprint().minX() >> 4;
        int maxCx = intent.footprint().maxX() >> 4;
        int minCz = intent.footprint().minZ() >> 4;
        int maxCz = intent.footprint().maxZ() >> 4;
        for (int cx = minCx; cx <= maxCx; cx++) {
            for (int cz = minCz; cz <= maxCz; cz++) {
                intent.markChunkPending(cx, cz);
            }
        }
    }

    private static double banditRisk(CanonicalWorldState state, SettlementState s) {
        MarketState market = state.markets().get(s.id());
        double tradeVolume = 0;
        for (ShipmentState sh : state.shipments().values()) {
            if (!sh.delivered() && !sh.looted()
                    && (sh.source().equals(s.id()) || sh.destination().equals(s.id()))) {
                tradeVolume += sh.quantity();
            }
        }
        double wealth = market == null ? 0.3 : Math.min(1.0, market.crisisSeverity() + tradeVolume / 200.0);
        double war = state.wars().values().stream().anyMatch(w -> w.active()) ? 0.15 : 0;
        return Math.min(1.0,
                (1.0 - s.security()) * 0.45
                        + s.unrest() * 0.25
                        + s.hunger() * 0.15
                        + wealth * 0.2
                        + war);
    }

    private static String riskCause(CanonicalWorldState state, SettlementState s) {
        if (s.security() < 0.3) return "low_security";
        if (s.unrest() > 0.5) return "unrest";
        if (s.hunger() > 0.4) return "famine";
        if (state.wars().values().stream().anyMatch(w -> w.active())) return "war";
        return "trade_opportunity";
    }
}
