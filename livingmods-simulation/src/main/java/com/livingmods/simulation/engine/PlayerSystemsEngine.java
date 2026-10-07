package com.livingmods.simulation.engine;

import com.livingmods.common.event.CivilizationEventType;
import com.livingmods.common.event.HistoricalEvent;
import com.livingmods.common.geo.BlockPos2;
import com.livingmods.common.id.CitizenId;
import com.livingmods.common.id.CultureId;
import com.livingmods.common.id.HistoricalEventId;
import com.livingmods.common.id.KingdomId;
import com.livingmods.common.id.PlayerId;
import com.livingmods.common.id.SettlementId;
import com.livingmods.common.model.FactionStanding;
import com.livingmods.common.model.GovernmentType;
import com.livingmods.common.model.Profession;
import com.livingmods.common.model.ResourceType;
import com.livingmods.common.model.SettlementRole;
import com.livingmods.common.model.SettlementTier;
import com.livingmods.simulation.CanonicalWorldState;
import com.livingmods.simulation.state.CitizenState;
import com.livingmods.simulation.state.KingdomState;
import com.livingmods.simulation.state.MarketState;
import com.livingmods.simulation.state.SettlementState;
import com.livingmods.simulation.state.StockpileState;
import com.livingmods.simulation.tick.RegionalWork;
import com.livingmods.simulation.tick.SimulationContext;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Player reputation, faction standing, and player-founded kingdom rulership.
 * Player sets high-level policy; NPC administration continues routine.
 */
public final class PlayerSystemsEngine implements SimulationSubsystem {
    public record FoundationRequirements(
            int minReputation,
            int minSettlementHousing,
            double minTreasuryContribution,
            boolean requireTrustedStanding
    ) {
        public static FoundationRequirements defaults() {
            return new FoundationRequirements(0, 4, 25.0, false);
        }
    }

    public record FoundationResult(boolean success, String message, KingdomId kingdomId) {}

    @Override
    public String name() { return "player_systems"; }

    @Override
    public void phase1Regional(CanonicalWorldState state, RegionalWork work, SimulationContext ctx) {}

    @Override
    public void phase2Global(CanonicalWorldState state, SimulationContext ctx) {
        if (!ctx.schedule().runGovernment()) return;
        for (var entry : state.playerReputation().reputationByPlayer().entrySet()) {
            for (Map.Entry<KingdomId, Double> rep : entry.getValue().entrySet()) {
                double v = rep.getValue() * 0.995;
                entry.getValue().put(rep.getKey(), v);
            }
        }
        // Player realms: NPC admin continues tax collection / legitimacy maintenance.
        for (Map.Entry<PlayerId, KingdomId> ruled : state.playerReputation().ruledKingdoms().entrySet()) {
            KingdomState kingdom = state.kingdoms().get(ruled.getValue());
            if (kingdom == null) continue;
            kingdom.setLegitimacy(Math.min(100, kingdom.legitimacy() + 0.05));
            // Routine treasury drip from settlements.
            for (SettlementId sid : kingdom.settlementIds()) {
                StockpileState stock = state.stockpiles().get(sid);
                if (stock != null && stock.get(ResourceType.GRAIN) > 10) {
                    kingdom.setTreasury(kingdom.treasury() + kingdom.taxRate() * 0.5);
                }
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
        if (k != null) {
            for (SettlementId sid : k.settlementIds()) {
                state.playerReputation().adjustSettlement(player, sid, reputationDelta * 0.5);
            }
        }
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

    public FactionStanding factionStanding(CanonicalWorldState state, PlayerId player, KingdomId kingdom) {
        return state.playerReputation().standing(player, kingdom);
    }

    public void setFactionMembership(
            CanonicalWorldState state,
            PlayerId player,
            KingdomId kingdom,
            FactionStanding standing
    ) {
        state.playerReputation().setStanding(player, kingdom, standing);
        if (standing.atLeast(FactionStanding.CITIZEN)) {
            state.playerReputation().adjust(player, kingdom, 0.05);
        }
    }

    /**
     * Practical foundation workflow under configurable requirements.
     * New realm enters territory/government/population/economy/diplomacy/military graphs.
     */
    public FoundationResult foundKingdom(
            CanonicalWorldState state,
            PlayerId player,
            String realmName,
            BlockPos2 center,
            CultureId cultureId,
            FoundationRequirements requirements,
            SimulationContext ctx
    ) {
        if (state.playerReputation().ruledKingdom(player) != null) {
            return new FoundationResult(false, "You already rule a realm.", null);
        }
        FoundationRequirements req = requirements == null ? FoundationRequirements.defaults() : requirements;

        // Prefer claiming an independent nearby settlement; otherwise found a hamlet.
        SettlementState capital = findClaimableSettlement(state, center, player, req);
        if (capital == null) {
            capital = createCapitalSettlement(state, realmName, center, cultureId, ctx);
        }

        if (capital.housingUnits() < req.minSettlementHousing()) {
            return new FoundationResult(false,
                    "Capital needs at least " + req.minSettlementHousing() + " housing units.", null);
        }

        KingdomId kingdomId = KingdomId.deterministic(state.seed(), state.kingdoms().size() + 500);
        CitizenId stewardId = ensureSteward(state, capital, cultureId, ctx);

        KingdomState kingdom = new KingdomState(
                kingdomId,
                realmName == null || realmName.isBlank() ? "Player Realm" : realmName,
                cultureId,
                GovernmentType.PLAYER_REALM,
                capital.id(),
                stewardId,
                new ArrayList<>(List.of(capital.id())),
                req.minTreasuryContribution(),
                0.08,
                60.0
        );
        kingdom.setReligionKey("solar_cult");
        state.kingdoms().put(kingdomId, kingdom);

        // Re-home capital under player kingdom by replacing settlement ownership via new instance.
        SettlementState owned = new SettlementState(
                capital.id(),
                capital.name(),
                capital.tier(),
                capital.role(),
                capital.center(),
                Optional.of(kingdomId),
                true,
                stewardId,
                capital.legitimacy(),
                capital.developmentDeficit(),
                capital.physicalCapacity(),
                capital.housingUnits(),
                capital.employedSlots()
        );
        owned.setUnrest(capital.unrest());
        owned.setSecurity(capital.security());
        owned.setHunger(capital.hunger());
        state.putSettlement(owned);

        state.playerReputation().setRuledKingdom(player, kingdomId);
        state.playerReputation().adjust(player, kingdomId, 1.0);

        // Diplomacy: neutral to all existing kingdoms.
        for (KingdomId other : state.kingdoms().keySet()) {
            if (other.equals(kingdomId)) continue;
            state.diplomacy().setRelation(kingdomId, other,
                    com.livingmods.common.model.DiplomaticRelation.NEUTRAL);
        }

        // Seed economy if missing.
        state.stockpiles().computeIfAbsent(owned.id(), StockpileState::new);
        state.markets().computeIfAbsent(owned.id(), MarketState::new);
        StockpileState stock = state.stockpiles().get(owned.id());
        stock.add(ResourceType.GRAIN, 30);
        stock.add(ResourceType.WOOD, 20);
        stock.add(ResourceType.STONE, 15);

        state.appendHistory(new HistoricalEvent(
                HistoricalEventId.deterministic(state.seed(), state.history().size()),
                CivilizationEventType.SETTLEMENT_FOUNDED,
                ctx.time(),
                "Realm founded",
                realmName + " rises under player rule",
                Optional.of(center),
                Map.of("kingdom", kingdomId.toString(), "player", player.toString())
        ));

        return new FoundationResult(true, "Founded " + kingdom.name(), kingdomId);
    }

    public void setPolicyTaxRate(CanonicalWorldState state, PlayerId player, double taxRate) {
        KingdomId id = state.playerReputation().ruledKingdom(player);
        if (id == null) return;
        KingdomState k = state.kingdoms().get(id);
        if (k != null) {
            k.setTaxRate(taxRate);
        }
    }

    private static SettlementState findClaimableSettlement(
            CanonicalWorldState state,
            BlockPos2 center,
            PlayerId player,
            FoundationRequirements req
    ) {
        SettlementState best = null;
        double bestDist = Double.MAX_VALUE;
        for (SettlementState s : state.settlements().values()) {
            if (s.ownerKingdom().isPresent()) continue;
            double d = s.center().distanceTo(center);
            if (d < bestDist && d < 256) {
                best = s;
                bestDist = d;
            }
        }
        return best;
    }

    private static SettlementState createCapitalSettlement(
            CanonicalWorldState state,
            String realmName,
            BlockPos2 center,
            CultureId cultureId,
            SimulationContext ctx
    ) {
        SettlementId id = SettlementId.deterministic(state.seed(), state.settlements().size() + 8000);
        SettlementState settlement = new SettlementState(
                id,
                (realmName == null ? "New" : realmName) + " Hold",
                SettlementTier.VILLAGE,
                SettlementRole.FARMING,
                center,
                Optional.empty(),
                true,
                null,
                65.0,
                1.0,
                40.0,
                6,
                4
        );
        state.putSettlement(settlement);
        state.stockpiles().put(id, new StockpileState(id));
        state.markets().put(id, new MarketState(id));
        return settlement;
    }

    private static CitizenId ensureSteward(
            CanonicalWorldState state,
            SettlementState capital,
            CultureId cultureId,
            SimulationContext ctx
    ) {
        for (CitizenId cid : state.citizensInSettlement(capital.id())) {
            CitizenState c = state.citizens().get(cid);
            if (c != null && c.alive() && c.isAdult(ctx.time())) {
                c.setProfession(Profession.GOVERNMENT_OFFICIAL);
                c.setRuler(true);
                capital.setRulerId(c.id());
                return c.id();
            }
        }
        CitizenId id = CitizenId.deterministic(state.seed(), state.citizens().size() + 7000);
        var hh = state.households().values().stream()
                .filter(h -> h.settlementId().equals(capital.id()))
                .findFirst()
                .orElse(null);
        com.livingmods.common.id.HouseholdId hhId = hh != null ? hh.id()
                : com.livingmods.common.id.HouseholdId.deterministic(state.seed(), state.households().size() + 7000);
        if (hh == null) {
            state.putHousehold(new com.livingmods.simulation.state.HouseholdState(hhId, capital.id(), 1, 12, 2));
        }
        CitizenState steward = new CitizenState(
                id, "Steward", "Keep", false, ctx.time().plusDays(-30 * 365L),
                cultureId, capital.id(), hhId, Profession.GOVERNMENT_OFFICIAL,
                90, 20, true, true
        );
        state.putCitizen(steward);
        capital.setRulerId(id);
        return id;
    }

    public static PlayerId demoPlayer() {
        return PlayerId.of(UUID.fromString("00000000-0000-4000-8000-000000000001"));
    }
}
