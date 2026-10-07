package com.livingmods.simulation;

import com.livingmods.common.event.HistoricalEvent;
import com.livingmods.common.id.ArmyId;
import com.livingmods.common.id.CitizenId;
import com.livingmods.common.id.CrimeId;
import com.livingmods.common.id.DynastyId;
import com.livingmods.common.id.EpidemicId;
import com.livingmods.common.id.FactionId;
import com.livingmods.common.id.HouseholdId;
import com.livingmods.common.id.KingdomId;
import com.livingmods.common.id.MigrationGroupId;
import com.livingmods.common.id.SettlementId;
import com.livingmods.common.id.ShipmentId;
import com.livingmods.common.id.SiegeId;
import com.livingmods.common.id.WarId;
import com.livingmods.common.time.SimulationTime;
import com.livingmods.common.util.Hashing;
import com.livingmods.simulation.spatial.SpatialIndex;
import com.livingmods.simulation.state.ArmyState;
import com.livingmods.simulation.state.CitizenState;
import com.livingmods.simulation.state.CrimeState;
import com.livingmods.simulation.state.DiplomacyState;
import com.livingmods.simulation.state.DynastyState;
import com.livingmods.simulation.state.EcologyState;
import com.livingmods.simulation.state.EmergentTaskState;
import com.livingmods.simulation.state.EpidemicState;
import com.livingmods.simulation.state.FactionState;
import com.livingmods.simulation.state.FamilyRelationState;
import com.livingmods.simulation.state.HistoryMarkerState;
import com.livingmods.simulation.state.HouseholdState;
import com.livingmods.simulation.state.IntelligenceState;
import com.livingmods.simulation.state.KingdomState;
import com.livingmods.simulation.state.MarketState;
import com.livingmods.simulation.state.MigrationGroupState;
import com.livingmods.simulation.state.PlayerReputationState;
import com.livingmods.simulation.state.RumorState;
import com.livingmods.simulation.state.SettlementState;
import com.livingmods.simulation.state.ShipmentState;
import com.livingmods.simulation.state.SiegeState;
import com.livingmods.simulation.state.StockpileState;
import com.livingmods.simulation.state.TechnologyState;
import com.livingmods.simulation.state.TradeNetwork;
import com.livingmods.simulation.state.WarState;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Authoritative canonical civilization state. No Minecraft types.
 */
public final class CanonicalWorldState {
    public static final int MAX_HISTORY_EVENTS = 4096;

    private UUID worldId;
    private final long seed;
    private SimulationTime time;
    private long saveRevision;
    private final long planContentHash;

    private final Map<KingdomId, KingdomState> kingdoms = new LinkedHashMap<>();
    private final Map<SettlementId, SettlementState> settlements = new LinkedHashMap<>();
    private final Map<CitizenId, CitizenState> citizens = new LinkedHashMap<>();
    private final Map<HouseholdId, HouseholdState> households = new LinkedHashMap<>();
    private final Map<UUID, FamilyRelationState> familyRelations = new LinkedHashMap<>();
    private final Map<SettlementId, StockpileState> stockpiles = new LinkedHashMap<>();
    private final Map<SettlementId, MarketState> markets = new LinkedHashMap<>();
    private final DiplomacyState diplomacy = new DiplomacyState();
    private final Map<WarId, WarState> wars = new LinkedHashMap<>();
    private final Map<ArmyId, ArmyState> armies = new LinkedHashMap<>();
    private final Map<EpidemicId, EpidemicState> epidemics = new LinkedHashMap<>();
    private final Map<MigrationGroupId, MigrationGroupState> migrations = new LinkedHashMap<>();
    private final Map<ShipmentId, ShipmentState> shipments = new LinkedHashMap<>();
    private final Map<DynastyId, DynastyState> dynasties = new LinkedHashMap<>();
    private final Map<CrimeId, CrimeState> crimes = new LinkedHashMap<>();
    private final Map<SiegeId, SiegeState> sieges = new LinkedHashMap<>();
    private final Map<FactionId, FactionState> factions = new LinkedHashMap<>();
    private final IntelligenceState intelligence = new IntelligenceState();
    private final TradeNetwork tradeNetwork = new TradeNetwork();
    private final EcologyState ecology = new EcologyState();
    private final TechnologyState technology = new TechnologyState();
    private final PlayerReputationState playerReputation = new PlayerReputationState();
    private final Deque<HistoricalEvent> history = new ArrayDeque<>();
    private final Map<String, RumorState> rumors = new LinkedHashMap<>();
    private final Map<UUID, EmergentTaskState> emergentTasks = new LinkedHashMap<>();
    private final Map<String, HistoryMarkerState> historyMarkers = new LinkedHashMap<>();

    private final Map<SettlementId, Set<CitizenId>> citizensBySettlement = new LinkedHashMap<>();
    private final Map<HouseholdId, Set<CitizenId>> citizensByHousehold = new LinkedHashMap<>();
    private final Map<KingdomId, Set<SettlementId>> settlementsByKingdom = new LinkedHashMap<>();
    private final SpatialIndex spatialIndex = new SpatialIndex(this);

    public CanonicalWorldState(long seed, SimulationTime time, long planContentHash) {
        this(new UUID(seed, planContentHash), seed, time, planContentHash);
    }

    public CanonicalWorldState(UUID worldId, long seed, SimulationTime time, long planContentHash) {
        this.worldId = worldId == null ? new UUID(seed, planContentHash) : worldId;
        this.seed = seed;
        this.time = time;
        this.planContentHash = planContentHash;
        this.saveRevision = 0L;
    }

    public UUID worldId() { return worldId; }
    public void setWorldId(UUID worldId) {
        this.worldId = worldId == null ? new UUID(seed, planContentHash) : worldId;
    }

    public long seed() { return seed; }
    public SimulationTime time() { return time; }
    public void setTime(SimulationTime time) { this.time = time; }
    public long saveRevision() { return saveRevision; }
    public void bumpSaveRevision() { saveRevision++; }
    /** Assign absolute save revision (used when freezing a snapshot before serialize). */
    public void setSaveRevision(long saveRevision) {
        if (saveRevision < 0) {
            throw new IllegalArgumentException("negative save revision");
        }
        this.saveRevision = saveRevision;
    }
    public long planContentHash() { return planContentHash; }

    public Map<KingdomId, KingdomState> kingdoms() { return kingdoms; }
    public Map<SettlementId, SettlementState> settlements() { return settlements; }
    public Map<CitizenId, CitizenState> citizens() { return citizens; }
    public Map<HouseholdId, HouseholdState> households() { return households; }
    public Map<UUID, FamilyRelationState> familyRelations() { return familyRelations; }
    public Map<SettlementId, StockpileState> stockpiles() { return stockpiles; }
    public Map<SettlementId, MarketState> markets() { return markets; }
    public DiplomacyState diplomacy() { return diplomacy; }
    public Map<WarId, WarState> wars() { return wars; }
    public Map<ArmyId, ArmyState> armies() { return armies; }
    public Map<EpidemicId, EpidemicState> epidemics() { return epidemics; }
    public Map<MigrationGroupId, MigrationGroupState> migrations() { return migrations; }
    public Map<ShipmentId, ShipmentState> shipments() { return shipments; }
    public Map<DynastyId, DynastyState> dynasties() { return dynasties; }
    public Map<CrimeId, CrimeState> crimes() { return crimes; }
    public Map<SiegeId, SiegeState> sieges() { return sieges; }
    public Map<FactionId, FactionState> factions() { return factions; }
    public IntelligenceState intelligence() { return intelligence; }
    public TradeNetwork tradeNetwork() { return tradeNetwork; }
    public EcologyState ecology() { return ecology; }
    public TechnologyState technology() { return technology; }
    public PlayerReputationState playerReputation() { return playerReputation; }
    public Deque<HistoricalEvent> history() { return history; }
    public Map<String, RumorState> rumors() { return rumors; }
    public Map<UUID, EmergentTaskState> emergentTasks() { return emergentTasks; }
    public Map<String, HistoryMarkerState> historyMarkers() { return historyMarkers; }

    public void transferCitizen(
            CitizenState citizen,
            SettlementId newSettlement,
            HouseholdId newHousehold
    ) {
        SettlementId prevSettlement = citizen.settlementId();
        HouseholdId prevHousehold = citizen.householdId();
        citizen.setSettlementId(newSettlement);
        citizen.setHouseholdId(newHousehold);
        reindexCitizen(citizen, prevSettlement, prevHousehold);
    }

    public Map<SettlementId, Set<CitizenId>> citizensBySettlement() { return citizensBySettlement; }
    public Map<HouseholdId, Set<CitizenId>> citizensByHousehold() { return citizensByHousehold; }
    public Map<KingdomId, Set<SettlementId>> settlementsByKingdom() { return settlementsByKingdom; }
    public SpatialIndex spatialIndex() { return spatialIndex; }

    public Optional<KingdomState> kingdom(KingdomId id) {
        return Optional.ofNullable(kingdoms.get(id));
    }

    public Optional<SettlementState> settlement(SettlementId id) {
        return Optional.ofNullable(settlements.get(id));
    }

    public Optional<CitizenState> citizen(CitizenId id) {
        return Optional.ofNullable(citizens.get(id));
    }

    public void putSettlement(SettlementState settlement) {
        SettlementState previous = settlements.put(settlement.id(), settlement);
        if (previous != null) {
            previous.ownerKingdom().ifPresent(k -> {
                Set<SettlementId> set = settlementsByKingdom.get(k);
                if (set != null) {
                    set.remove(previous.id());
                }
            });
        }
        settlement.ownerKingdom().ifPresent(k ->
                settlementsByKingdom.computeIfAbsent(k, id -> new LinkedHashSet<>()).add(settlement.id()));
    }

    public void putHousehold(HouseholdState household) {
        households.put(household.id(), household);
        citizensByHousehold.computeIfAbsent(household.id(), id -> new LinkedHashSet<>());
    }

    public void putCitizen(CitizenState citizen) {
        CitizenState previous = citizens.put(citizen.id(), citizen);
        if (previous != null) {
            removeCitizenFromIndexes(previous);
        }
        citizensBySettlement.computeIfAbsent(citizen.settlementId(), id -> new LinkedHashSet<>()).add(citizen.id());
        citizensByHousehold.computeIfAbsent(citizen.householdId(), id -> new LinkedHashSet<>()).add(citizen.id());
    }

    public void reindexCitizen(CitizenState citizen, SettlementId previousSettlement, HouseholdId previousHousehold) {
        if (previousSettlement != null) {
            Set<CitizenId> bySettlement = citizensBySettlement.get(previousSettlement);
            if (bySettlement != null) {
                bySettlement.remove(citizen.id());
            }
        }
        if (previousHousehold != null) {
            Set<CitizenId> byHousehold = citizensByHousehold.get(previousHousehold);
            if (byHousehold != null) {
                byHousehold.remove(citizen.id());
            }
        }
        citizensBySettlement.computeIfAbsent(citizen.settlementId(), id -> new LinkedHashSet<>()).add(citizen.id());
        citizensByHousehold.computeIfAbsent(citizen.householdId(), id -> new LinkedHashSet<>()).add(citizen.id());
    }

    public void putFamilyRelation(FamilyRelationState relation) {
        familyRelations.put(relation.id(), relation);
    }

    public void removeCitizen(CitizenId id) {
        CitizenState previous = citizens.remove(id);
        if (previous != null) {
            removeCitizenFromIndexes(previous);
        }
    }

    private void removeCitizenFromIndexes(CitizenState citizen) {
        Set<CitizenId> bySettlement = citizensBySettlement.get(citizen.settlementId());
        if (bySettlement != null) {
            bySettlement.remove(citizen.id());
        }
        Set<CitizenId> byHousehold = citizensByHousehold.get(citizen.householdId());
        if (byHousehold != null) {
            byHousehold.remove(citizen.id());
        }
    }

    /** Rebuild secondary indexes from primary maps (call after bulk load). */
    public void rebuildIndexes() {
        citizensBySettlement.clear();
        citizensByHousehold.clear();
        settlementsByKingdom.clear();
        for (SettlementState settlement : settlements.values()) {
            settlement.ownerKingdom().ifPresent(k ->
                    settlementsByKingdom.computeIfAbsent(k, id -> new LinkedHashSet<>()).add(settlement.id()));
        }
        for (HouseholdState household : households.values()) {
            citizensByHousehold.computeIfAbsent(household.id(), id -> new LinkedHashSet<>());
        }
        for (CitizenState citizen : citizens.values()) {
            citizensBySettlement.computeIfAbsent(citizen.settlementId(), id -> new LinkedHashSet<>()).add(citizen.id());
            citizensByHousehold.computeIfAbsent(citizen.householdId(), id -> new LinkedHashSet<>()).add(citizen.id());
        }
    }

    public Set<CitizenId> citizensInSettlement(SettlementId settlementId) {
        Set<CitizenId> set = citizensBySettlement.get(settlementId);
        return set == null ? Set.of() : Collections.unmodifiableSet(set);
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
        h = Hashing.mix(h, worldId.getMostSignificantBits());
        h = Hashing.mix(h, worldId.getLeastSignificantBits());
        h = Hashing.mix(h, saveRevision);
        h = Hashing.mix(h, planContentHash);
        h = Hashing.mix(h, kingdoms.size());
        h = Hashing.mix(h, settlements.size());
        h = Hashing.mix(h, citizens.size());
        h = Hashing.mix(h, households.size());

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
        h = Hashing.mix(h, armies.size());
        h = Hashing.mix(h, migrations.size());
        h = Hashing.mix(h, shipments.size());
        h = Hashing.mix(h, dynasties.size());
        h = Hashing.mix(h, crimes.size());
        h = Hashing.mix(h, sieges.size());
        h = Hashing.mix(h, factions.size());
        h = Hashing.mix(h, history.size());
        return h;
    }
}

