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
import com.livingmods.simulation.physical.DynamicUrbanPlanner;
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
                    && (intent.status().isActive() || intent.status() == PhysicalIntentStatus.MATERIALIZED)) {
                if (intent.status() != PhysicalIntentStatus.REMOVED
                        && intent.status() != PhysicalIntentStatus.SUPERSEDED) {
                    activeCamps++;
                }
            }
        }
        if (activeCamps >= 12) return;

        DynamicUrbanPlanner planner = new DynamicUrbanPlanner(state.seed());

        for (SettlementState s : state.settlements().values()) {
            double risk = banditRisk(state, s);
            if (risk < 0.45) continue;
            if (state.dynamicPhysical().hasOpenIntent(s.id(), PhysicalIntentType.CREATE_BANDIT_CAMP)) {
                continue;
            }
            String preferred = risk > 0.75 ? "STRONGHOLD" : risk > 0.6 ? "FOREST_CAMP" : "ROAD_CAMP";
            Optional<DynamicUrbanPlanner.CampSite> siteOpt = planner.chooseBanditSite(
                    state, s, preferred, state.dynamicPhysical().intents().size() + s.id().hashCode());
            if (siteOpt.isEmpty()) continue;
            DynamicUrbanPlanner.CampSite site = siteOpt.get();

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
                    site.center(),
                    site.footprint(),
                    1,
                    PhysicalIntentStatus.READY,
                    ctx.time(),
                    3,
                    Map.of(
                            "cause", riskCause(state, s),
                            "variant", site.variant(),
                            "campId", id.value().toString(),
                            "strength", String.format(java.util.Locale.ROOT, "%.2f", risk),
                            "siteScore", String.format(java.util.Locale.ROOT, "%.2f", site.score())
                    ),
                    Map.of(),
                    CultureResolver.forSettlement(state, s)
            );
            seedChunks(intent);
            state.dynamicPhysical().putIntent(intent);
            s.setSecurity(Math.max(0, s.security() - 0.04));
            state.tradeNetwork().addHotspot(site.center());

            state.appendHistory(new HistoricalEvent(
                    HistoricalEventId.deterministic(state.seed(), state.history().size()),
                    CivilizationEventType.BANDIT_ACTIVITY_CHANGED,
                    ctx.time(),
                    "Bandit camp forms",
                    site.variant() + " near " + s.name(),
                    Optional.of(site.center()),
                    Map.of("settlement", s.id().toString(), "variant", site.variant(),
                            "campId", id.value().toString())
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
                    intent.cultureKey()
            );
            upgrade.addDependency(intent.id());
            seedChunks(upgrade);
            state.dynamicPhysical().putIntent(upgrade);
        }
    }

    /** Emit REMOVE_BANDIT_CAMP when security recovers enough that the camp should clear. */
    public static void offerCampRemoval(
            CanonicalWorldState state,
            PhysicalIntent campIntent,
            SimulationContext ctx,
            String reason
    ) {
        if (campIntent.type() != PhysicalIntentType.CREATE_BANDIT_CAMP
                && campIntent.type() != PhysicalIntentType.UPGRADE_BANDIT_CAMP) {
            return;
        }
        SettlementId sid = campIntent.settlementId().orElse(null);
        if (sid != null && state.dynamicPhysical().hasOpenIntent(sid, PhysicalIntentType.REMOVE_BANDIT_CAMP)) {
            return;
        }
        PhysicalIntentId removeId = PhysicalIntentId.deterministic(
                state.seed(), state.dynamicPhysical().intents().size() + 320_000L);
        PhysicalIntent remove = new PhysicalIntent(
                removeId,
                PhysicalIntentType.REMOVE_BANDIT_CAMP,
                "banditry",
                campIntent.sourceEntityId() != null ? campIntent.sourceEntityId() : removeId.value(),
                campIntent.settlementId(),
                campIntent.kingdomId(),
                campIntent.structureId(),
                Optional.empty(),
                campIntent.targetPosition(),
                campIntent.footprint(),
                campIntent.revision() + 1,
                PhysicalIntentStatus.READY,
                ctx.time(),
                2,
                Map.of(
                        "cause", reason,
                        "campId", campIntent.provenance().getOrDefault("campId", campIntent.id().value().toString()),
                        "mode", "abandon_ruin"
                ),
                Map.of(),
                campIntent.cultureKey()
        );
        remove.addDependency(campIntent.id());
        seedChunks(remove);
        state.dynamicPhysical().putIntent(remove);
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
