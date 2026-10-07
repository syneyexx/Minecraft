package com.livingmods.simulation;

import com.livingmods.common.event.HistoricalEvent;
import com.livingmods.common.id.ArmyId;
import com.livingmods.common.id.CitizenId;
import com.livingmods.common.id.EpidemicId;
import com.livingmods.common.id.HouseholdId;
import com.livingmods.common.id.KingdomId;
import com.livingmods.common.id.MigrationGroupId;
import com.livingmods.common.id.SettlementId;
import com.livingmods.common.id.ShipmentId;
import com.livingmods.common.id.WarId;
import com.livingmods.common.time.SimulationTime;
import com.livingmods.common.util.Hashing;
import com.livingmods.simulation.state.ArmyState;
import com.livingmods.simulation.state.CitizenState;
import com.livingmods.simulation.state.DiplomacyState;
import com.livingmods.simulation.state.EcologyState;
import com.livingmods.simulation.state.EpidemicState;
import com.livingmods.simulation.state.HouseholdState;
import com.livingmods.simulation.state.KingdomState;
import com.livingmods.simulation.state.MarketState;
import com.livingmods.simulation.state.MigrationGroupState;
import com.livingmods.simulation.state.PlayerReputationState;
import com.livingmods.simulation.state.SettlementState;
import com.livingmods.simulation.state.ShipmentState;
import com.livingmods.simulation.state.StockpileState;
import com.livingmods.simulation.state.TechnologyState;
import com.livingmods.simulation.state.WarState;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Authoritative canonical civilization state. No Minecraft types.
 */
public final class CanonicalWorldState {
    public static final int MAX_HISTORY_EVENTS = 4096;

    private final long seed;
    private SimulationTime time;
    private long saveRevision;
    private final long planContentHash;

    private final Map<KingdomId, KingdomState> kingdoms = new LinkedHashMap<>();
    private final Map<SettlementId, SettlementState> settlements = new LinkedHashMap<>();
    private final Map<CitizenId, CitizenState> citizens = new LinkedHashMap<>();
    private final Map<HouseholdId, HouseholdState> households = new LinkedHashMap<>();
    private final Map<SettlementId, StockpileState> stockpiles = new LinkedHashMap<>();
    private final Map<SettlementId, MarketState> markets = new LinkedHashMap<>();
    private final DiplomacyState diplomacy = new DiplomacyState();
    private final Map<WarId, WarState> wars = new LinkedHashMap<>();
    private final Map<ArmyId, ArmyState> armies = new LinkedHashMap<>();
    private final Map<EpidemicId, EpidemicState> epidemics = new LinkedHashMap<>();
    private final Map<MigrationGroupId, MigrationGroupState> migrations = new LinkedHashMap<>();
    private final Map<ShipmentId, ShipmentState> shipments = new LinkedHashMap<>();
    private final EcologyState ecology = new EcologyState();
    private final TechnologyState technology = new TechnologyState();
    private final PlayerReputationState playerReputation = new PlayerReputationState();
    private final Deque<HistoricalEvent> history = new ArrayDeque<>();

    public CanonicalWorldState(long seed, SimulationTime time, long planContentHash) {
        this.seed = seed;
        this.time = time;
        this.planContentHash = planContentHash;
        this.saveRevision = 0L;
    }

    public long seed() { return seed; }
    public SimulationTime time() { return time; }
    public void setTime(SimulationTime time) { this.time = time; }
    public long saveRevision() { return saveRevision; }
    public void bumpSaveRevision() { saveRevision++; }
    public long planContentHash() { return planContentHash; }

    public Map<KingdomId, KingdomState> kingdoms() { return kingdoms; }
    public Map<SettlementId, SettlementState> settlements() { return settlements; }
    public Map<CitizenId, CitizenState> citizens() { return citizens; }
    public Map<HouseholdId, HouseholdState> households() { return households; }
    public Map<SettlementId, StockpileState> stockpiles() { return stockpiles; }
    public Map<SettlementId, MarketState> markets() { return markets; }
    public DiplomacyState diplomacy() { return diplomacy; }
    public Map<WarId, WarState> wars() { return wars; }
    public Map<ArmyId, ArmyState> armies() { return armies; }
    public Map<EpidemicId, EpidemicState> epidemics() { return epidemics; }
    public Map<MigrationGroupId, MigrationGroupState> migrations() { return migrations; }
    public Map<ShipmentId, ShipmentState> shipments() { return shipments; }
    public EcologyState ecology() { return ecology; }
    public TechnologyState technology() { return technology; }
    public PlayerReputationState playerReputation() { return playerReputation; }
    public Deque<HistoricalEvent> history() { return history; }

    public Optional<KingdomState> kingdom(KingdomId id) {
        return Optional.ofNullable(kingdoms.get(id));
    }

    public Optional<SettlementState> settlement(SettlementId id) {
        return Optional.ofNullable(settlements.get(id));
    }

    public Optional<CitizenState> citizen(CitizenId id) {
        return Optional.ofNullable(citizens.get(id));
    }

    public void appendHistory(HistoricalEvent event) {
        history.addLast(event);
        while (history.size() > MAX_HISTORY_EVENTS) {
            history.removeFirst();
        }
    }

    public List<HistoricalEvent> historySnapshot() {
        return List.copyOf(history);
    }

    /** Deterministic fingerprint for tests and save verification. */
    public long contentHash() {
        long h = Hashing.mix(seed, time.absoluteTicks());
        h = Hashing.mix(h, saveRevision);
        h = Hashing.mix(h, planContentHash);
        h = Hashing.mix(h, kingdoms.size());
        h = Hashing.mix(h, settlements.size());
        h = Hashing.mix(h, citizens.size());

        List<CitizenId> citizenIds = new ArrayList<>(citizens.keySet());
        citizenIds.sort(Comparator.naturalOrder());
        for (CitizenId id : citizenIds) {
            CitizenState c = citizens.get(id);
            h = Hashing.mix(h, id.hashCode());
            h = Hashing.mix(h, Double.hashCode(c.health()));
            h = Hashing.mix(h, Double.hashCode(c.wealth()));
            h = Hashing.mix(h, c.alive() ? 1 : 0);
            h = Hashing.mix(h, c.profession().ordinal());
        }

        List<SettlementId> settlementIds = new ArrayList<>(settlements.keySet());
        settlementIds.sort(Comparator.naturalOrder());
        for (SettlementId sid : settlementIds) {
            MarketState m = markets.get(sid);
            StockpileState sp = stockpiles.get(sid);
            if (m != null) {
                h = Hashing.mix(h, Double.hashCode(m.price(com.livingmods.common.model.ResourceType.GRAIN)));
            }
            if (sp != null) {
                h = Hashing.mix(h, Double.hashCode(sp.get(com.livingmods.common.model.ResourceType.GRAIN)));
            }
        }

        for (KingdomState k : kingdoms.values()) {
            h = Hashing.mix(h, k.rulerId().hashCode());
            h = Hashing.mix(h, Double.hashCode(k.treasury()));
        }
        h = Hashing.mix(h, wars.size());
        h = Hashing.mix(h, epidemics.size());
        h = Hashing.mix(h, history.size());
        return h;
    }
}
