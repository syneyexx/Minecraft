package com.livingmods.simulation.persistence;

import com.livingmods.common.event.CivilizationEventType;
import com.livingmods.common.event.HistoricalEvent;
import com.livingmods.common.geo.BlockPos2;
import com.livingmods.common.geo.RegionCoord;
import com.livingmods.common.id.ArmyId;
import com.livingmods.common.id.CitizenId;
import com.livingmods.common.id.CultureId;
import com.livingmods.common.id.EpidemicId;
import com.livingmods.common.id.HistoricalEventId;
import com.livingmods.common.id.HouseholdId;
import com.livingmods.common.id.KingdomId;
import com.livingmods.common.id.MigrationGroupId;
import com.livingmods.common.id.PlayerId;
import com.livingmods.common.id.SettlementId;
import com.livingmods.common.id.ShipmentId;
import com.livingmods.common.id.SpeciesId;
import com.livingmods.common.id.TreatyId;
import com.livingmods.common.id.WarId;
import com.livingmods.common.model.DiplomaticRelation;
import com.livingmods.common.model.GovernmentType;
import com.livingmods.common.model.Profession;
import com.livingmods.common.model.ResourceType;
import com.livingmods.common.model.SettlementRole;
import com.livingmods.common.model.SettlementTier;
import com.livingmods.common.model.TreatyType;
import com.livingmods.common.time.SimulationTime;
import com.livingmods.protocol.BinaryCodec;
import com.livingmods.simulation.CanonicalWorldState;
import com.livingmods.simulation.state.ArmyState;
import com.livingmods.simulation.state.CitizenState;
import com.livingmods.simulation.state.DiplomacyPair;
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

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Schema version 2 snapshot encoding for the full canonical world graph.
 */
public final class CanonicalSaveFormat {
    public static final int SCHEMA_VERSION = 2;
    public static final int MAGIC = 0x4C4D4353; // LMCS

    private CanonicalSaveFormat() {}

    public record SnapshotMeta(
            int schemaVersion,
            long seed,
            long timeTicks,
            long saveRevision,
            long planContentHash,
            long contentHash,
            UUID worldSessionId
    ) {}

    public static byte[] writeSnapshot(CanonicalWorldState state, UUID worldSessionId) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream(128 * 1024);
        DataOutputStream out = new DataOutputStream(bos);
        out.writeInt(MAGIC);
        out.writeInt(SCHEMA_VERSION);
        out.writeLong(state.seed());
        out.writeLong(state.time().absoluteTicks());
        out.writeLong(state.saveRevision());
        out.writeLong(state.planContentHash());
        out.writeLong(state.contentHash());
        UUID session = worldSessionId != null ? worldSessionId : state.worldId();
        BinaryCodec.writeUuid(out, session);
        BinaryCodec.writeUuid(out, state.worldId());

        out.writeInt(state.kingdoms().size());
        for (KingdomState k : state.kingdoms().values()) {
            writeKingdom(out, k);
        }

        out.writeInt(state.settlements().size());
        for (SettlementState s : state.settlements().values()) {
            writeSettlement(out, s);
        }

        out.writeInt(state.households().size());
        for (HouseholdState h : state.households().values()) {
            writeHousehold(out, h);
        }

        out.writeInt(state.citizens().size());
        for (CitizenState c : state.citizens().values()) {
            writeCitizen(out, c);
        }

        out.writeInt(state.stockpiles().size());
        for (StockpileState sp : state.stockpiles().values()) {
            writeStockpile(out, sp);
        }

        out.writeInt(state.markets().size());
        for (MarketState m : state.markets().values()) {
            writeMarket(out, m);
        }

        writeDiplomacy(out, state.diplomacy());

        out.writeInt(state.wars().size());
        for (WarState w : state.wars().values()) {
            writeWar(out, w);
        }

        out.writeInt(state.armies().size());
        for (ArmyState a : state.armies().values()) {
            writeArmy(out, a);
        }

        out.writeInt(state.epidemics().size());
        for (EpidemicState e : state.epidemics().values()) {
            writeEpidemic(out, e);
        }

        out.writeInt(state.migrations().size());
        for (MigrationGroupState m : state.migrations().values()) {
            writeMigration(out, m);
        }

        out.writeInt(state.shipments().size());
        for (ShipmentState s : state.shipments().values()) {
            writeShipment(out, s);
        }

        writeEcology(out, state.ecology());
        writeTechnology(out, state.technology());
        writePlayerReputation(out, state.playerReputation());
        writeHistory(out, state);

        out.flush();
        return bos.toByteArray();
    }

    public static CanonicalWorldState readFullState(byte[] bytes) throws IOException {
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes));
        int magic = in.readInt();
        if (magic != MAGIC) {
            throw new IOException("bad save magic");
        }
        int version = in.readInt();
        if (version != SCHEMA_VERSION) {
            throw new IOException("unsupported schema: " + version + " (expected " + SCHEMA_VERSION + ")");
        }
        long seed = in.readLong();
        long ticks = in.readLong();
        long revision = in.readLong();
        long planHash = in.readLong();
        in.readLong(); // contentHash
        UUID session = BinaryCodec.readUuid(in);
        UUID worldId = BinaryCodec.readUuid(in);
        if (worldId == null) {
            worldId = session != null ? session : new UUID(seed, planHash);
        }

        CanonicalWorldState state = new CanonicalWorldState(worldId, seed, SimulationTime.ofTicks(ticks), planHash);
        state.setSaveRevision(revision);

        int kingdomCount = in.readInt();
        for (int i = 0; i < kingdomCount; i++) {
            KingdomState k = readKingdom(in);
            state.kingdoms().put(k.id(), k);
        }

        int settlementCount = in.readInt();
        for (int i = 0; i < settlementCount; i++) {
            SettlementState s = readSettlement(in);
            state.settlements().put(s.id(), s);
        }

        int householdCount = in.readInt();
        for (int i = 0; i < householdCount; i++) {
            HouseholdState h = readHousehold(in);
            state.households().put(h.id(), h);
        }

        int citizenCount = in.readInt();
        for (int i = 0; i < citizenCount; i++) {
            CitizenState c = readCitizen(in);
            state.citizens().put(c.id(), c);
        }

        int stockpileCount = in.readInt();
        for (int i = 0; i < stockpileCount; i++) {
            StockpileState sp = readStockpile(in);
            state.stockpiles().put(sp.settlementId(), sp);
        }

        int marketCount = in.readInt();
        for (int i = 0; i < marketCount; i++) {
            MarketState m = readMarket(in);
            state.markets().put(m.settlementId(), m);
        }

        readDiplomacy(in, state.diplomacy());

        int warCount = in.readInt();
        for (int i = 0; i < warCount; i++) {
            WarState w = readWar(in);
            state.wars().put(w.id(), w);
        }

        int armyCount = in.readInt();
        for (int i = 0; i < armyCount; i++) {
            ArmyState a = readArmy(in);
            state.armies().put(a.id(), a);
        }

        int epidemicCount = in.readInt();
        for (int i = 0; i < epidemicCount; i++) {
            EpidemicState e = readEpidemic(in);
            state.epidemics().put(e.id(), e);
        }

        int migrationCount = in.readInt();
        for (int i = 0; i < migrationCount; i++) {
            MigrationGroupState m = readMigration(in);
            state.migrations().put(m.id(), m);
        }

        int shipmentCount = in.readInt();
        for (int i = 0; i < shipmentCount; i++) {
            ShipmentState s = readShipment(in);
            state.shipments().put(s.id(), s);
        }

        readEcology(in, state.ecology());
        readTechnology(in, state.technology());
        readPlayerReputation(in, state.playerReputation());
        readHistory(in, state);

        state.rebuildIndexes();
        return state;
    }

    public static SnapshotMeta readSnapshot(byte[] bytes) throws IOException {
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes));
        int magic = in.readInt();
        if (magic != MAGIC) {
            throw new IOException("bad save magic");
        }
        int version = in.readInt();
        if (version != SCHEMA_VERSION) {
            throw new IOException("unsupported schema: " + version);
        }
        long seed = in.readLong();
        long ticks = in.readLong();
        long revision = in.readLong();
        long planHash = in.readLong();
        long contentHash = in.readLong();
        UUID session = BinaryCodec.readUuid(in);
        return new SnapshotMeta(version, seed, ticks, revision, planHash, contentHash, session);
    }

    private static void writeKingdom(DataOutputStream out, KingdomState k) throws IOException {
        BinaryCodec.writeUuid(out, k.id().value());
        BinaryCodec.writeString(out, k.name());
        BinaryCodec.writeUuid(out, k.cultureId().value());
        out.writeInt(k.governmentType().ordinal());
        BinaryCodec.writeUuid(out, k.capitalId().value());
        BinaryCodec.writeUuid(out, k.rulerId().value());
        out.writeInt(k.settlementIds().size());
        for (SettlementId sid : k.settlementIds()) {
            BinaryCodec.writeUuid(out, sid.value());
        }
        out.writeDouble(k.treasury());
        out.writeDouble(k.taxRate());
        out.writeDouble(k.legitimacy());
    }

    private static KingdomState readKingdom(DataInputStream in) throws IOException {
        KingdomId id = KingdomId.of(BinaryCodec.readUuid(in));
        String name = BinaryCodec.readString(in);
        CultureId cultureId = CultureId.of(BinaryCodec.readUuid(in));
        GovernmentType gov = GovernmentType.values()[in.readInt()];
        SettlementId capitalId = SettlementId.of(BinaryCodec.readUuid(in));
        CitizenId rulerId = CitizenId.of(BinaryCodec.readUuid(in));
        int n = in.readInt();
        List<SettlementId> settlements = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            settlements.add(SettlementId.of(BinaryCodec.readUuid(in)));
        }
        double treasury = in.readDouble();
        double tax = in.readDouble();
        double legitimacy = in.readDouble();
        return new KingdomState(id, name, cultureId, gov, capitalId, rulerId, settlements, treasury, tax, legitimacy);
    }

    private static void writeSettlement(DataOutputStream out, SettlementState s) throws IOException {
        BinaryCodec.writeUuid(out, s.id().value());
        BinaryCodec.writeString(out, s.name());
        out.writeInt(s.tier().ordinal());
        out.writeInt(s.role().ordinal());
        out.writeInt(s.center().x());
        out.writeInt(s.center().z());
        out.writeBoolean(s.ownerKingdom().isPresent());
        if (s.ownerKingdom().isPresent()) {
            BinaryCodec.writeUuid(out, s.ownerKingdom().get().value());
        }
        out.writeBoolean(s.capital());
        BinaryCodec.writeUuid(out, s.rulerId() == null ? null : s.rulerId().value());
        out.writeDouble(s.legitimacy());
        out.writeDouble(s.developmentDeficit());
        out.writeDouble(s.physicalCapacity());
        out.writeInt(s.housingUnits());
        out.writeInt(s.employedSlots());
    }

    private static SettlementState readSettlement(DataInputStream in) throws IOException {
        SettlementId id = SettlementId.of(BinaryCodec.readUuid(in));
        String name = BinaryCodec.readString(in);
        SettlementTier tier = SettlementTier.values()[in.readInt()];
        SettlementRole role = SettlementRole.values()[in.readInt()];
        BlockPos2 center = BlockPos2.of(in.readInt(), in.readInt());
        Optional<KingdomId> owner = Optional.empty();
        if (in.readBoolean()) {
            owner = Optional.of(KingdomId.of(BinaryCodec.readUuid(in)));
        }
        boolean capital = in.readBoolean();
        UUID rulerUuid = BinaryCodec.readUuid(in);
        CitizenId rulerId = rulerUuid == null ? null : CitizenId.of(rulerUuid);
        double legitimacy = in.readDouble();
        double deficit = in.readDouble();
        double capacity = in.readDouble();
        int housing = in.readInt();
        int employed = in.readInt();
        return new SettlementState(id, name, tier, role, center, owner, capital, rulerId,
                legitimacy, deficit, capacity, housing, employed);
    }

    private static void writeHousehold(DataOutputStream out, HouseholdState h) throws IOException {
        BinaryCodec.writeUuid(out, h.id().value());
        BinaryCodec.writeUuid(out, h.settlementId().value());
        out.writeInt(h.memberCount());
        out.writeDouble(h.foodStores());
        out.writeInt(h.housingQuality());
    }

    private static HouseholdState readHousehold(DataInputStream in) throws IOException {
        HouseholdId id = HouseholdId.of(BinaryCodec.readUuid(in));
        SettlementId settlementId = SettlementId.of(BinaryCodec.readUuid(in));
        int members = in.readInt();
        double food = in.readDouble();
        int quality = in.readInt();
        return new HouseholdState(id, settlementId, members, food, quality);
    }

    private static void writeCitizen(DataOutputStream out, CitizenState c) throws IOException {
        BinaryCodec.writeUuid(out, c.id().value());
        BinaryCodec.writeString(out, c.givenName());
        BinaryCodec.writeString(out, c.familyName());
        out.writeBoolean(c.female());
        out.writeLong(c.birthDate().absoluteTicks());
        BinaryCodec.writeUuid(out, c.cultureId().value());
        BinaryCodec.writeUuid(out, c.settlementId().value());
        BinaryCodec.writeUuid(out, c.householdId().value());
        out.writeInt(c.profession().ordinal());
        out.writeDouble(c.health());
        out.writeDouble(c.wealth());
        out.writeBoolean(c.alive());
        out.writeBoolean(c.ruler());
        out.writeInt(c.crimeStrikes());
        out.writeBoolean(c.incarcerated());
    }

    private static CitizenState readCitizen(DataInputStream in) throws IOException {
        CitizenId id = CitizenId.of(BinaryCodec.readUuid(in));
        String given = BinaryCodec.readString(in);
        String family = BinaryCodec.readString(in);
        boolean female = in.readBoolean();
        SimulationTime birth = SimulationTime.ofTicks(in.readLong());
        CultureId cultureId = CultureId.of(BinaryCodec.readUuid(in));
        SettlementId settlementId = SettlementId.of(BinaryCodec.readUuid(in));
        HouseholdId householdId = HouseholdId.of(BinaryCodec.readUuid(in));
        Profession profession = Profession.values()[in.readInt()];
        double health = in.readDouble();
        double wealth = in.readDouble();
        boolean alive = in.readBoolean();
        boolean ruler = in.readBoolean();
        CitizenState c = new CitizenState(id, given, family, female, birth, cultureId, settlementId,
                householdId, profession, health, wealth, alive, ruler);
        c.setCrimeStrikes(in.readInt());
        c.setIncarcerated(in.readBoolean());
        return c;
    }

    private static void writeStockpile(DataOutputStream out, StockpileState sp) throws IOException {
        BinaryCodec.writeUuid(out, sp.settlementId().value());
        out.writeInt(sp.quantities().size());
        for (Map.Entry<ResourceType, Double> e : sp.quantities().entrySet()) {
            out.writeInt(e.getKey().ordinal());
            out.writeDouble(e.getValue());
        }
    }

    private static StockpileState readStockpile(DataInputStream in) throws IOException {
        SettlementId id = SettlementId.of(BinaryCodec.readUuid(in));
        int n = in.readInt();
        Map<ResourceType, Double> qty = new EnumMap<>(ResourceType.class);
        for (int i = 0; i < n; i++) {
            qty.put(ResourceType.values()[in.readInt()], in.readDouble());
        }
        return new StockpileState(id, qty);
    }

    private static void writeMarket(DataOutputStream out, MarketState m) throws IOException {
        BinaryCodec.writeUuid(out, m.settlementId().value());
        out.writeDouble(m.crisisSeverity());
        out.writeInt(m.prices().size());
        for (Map.Entry<ResourceType, Double> e : m.prices().entrySet()) {
            out.writeInt(e.getKey().ordinal());
            out.writeDouble(e.getValue());
        }
    }

    private static MarketState readMarket(DataInputStream in) throws IOException {
        SettlementId id = SettlementId.of(BinaryCodec.readUuid(in));
        MarketState m = new MarketState(id);
        m.setCrisisSeverity(in.readDouble());
        int n = in.readInt();
        for (int i = 0; i < n; i++) {
            m.setPrice(ResourceType.values()[in.readInt()], in.readDouble());
        }
        return m;
    }

    private static void writeDiplomacy(DataOutputStream out, DiplomacyState dip) throws IOException {
        out.writeInt(dip.relations().size());
        for (Map.Entry<DiplomacyPair, DiplomaticRelation> e : dip.relations().entrySet()) {
            BinaryCodec.writeUuid(out, e.getKey().first().value());
            BinaryCodec.writeUuid(out, e.getKey().second().value());
            out.writeInt(e.getValue().ordinal());
        }
        out.writeInt(dip.treaties().size());
        for (DiplomacyState.TreatyRecord t : dip.treaties().values()) {
            BinaryCodec.writeUuid(out, t.id().value());
            BinaryCodec.writeUuid(out, t.a().value());
            BinaryCodec.writeUuid(out, t.b().value());
            out.writeInt(t.type().ordinal());
            out.writeLong(t.signedDay());
        }
    }

    private static void readDiplomacy(DataInputStream in, DiplomacyState dip) throws IOException {
        int relCount = in.readInt();
        for (int i = 0; i < relCount; i++) {
            KingdomId a = KingdomId.of(BinaryCodec.readUuid(in));
            KingdomId b = KingdomId.of(BinaryCodec.readUuid(in));
            DiplomaticRelation rel = DiplomaticRelation.values()[in.readInt()];
            dip.setRelation(a, b, rel);
        }
        int treatyCount = in.readInt();
        for (int i = 0; i < treatyCount; i++) {
            TreatyId id = TreatyId.of(BinaryCodec.readUuid(in));
            KingdomId a = KingdomId.of(BinaryCodec.readUuid(in));
            KingdomId b = KingdomId.of(BinaryCodec.readUuid(in));
            TreatyType type = TreatyType.values()[in.readInt()];
            long signedDay = in.readLong();
            dip.treaties().put(id, new DiplomacyState.TreatyRecord(id, a, b, type, signedDay));
        }
    }

    private static void writeWar(DataOutputStream out, WarState w) throws IOException {
        BinaryCodec.writeUuid(out, w.id().value());
        BinaryCodec.writeUuid(out, w.aggressor().value());
        BinaryCodec.writeUuid(out, w.defender().value());
        out.writeLong(w.startedDay());
        out.writeBoolean(w.active());
        out.writeInt(w.participants().size());
        for (KingdomId id : w.participants()) {
            BinaryCodec.writeUuid(out, id.value());
        }
    }

    private static WarState readWar(DataInputStream in) throws IOException {
        WarId id = WarId.of(BinaryCodec.readUuid(in));
        KingdomId aggressor = KingdomId.of(BinaryCodec.readUuid(in));
        KingdomId defender = KingdomId.of(BinaryCodec.readUuid(in));
        long startedDay = in.readLong();
        WarState w = new WarState(id, aggressor, defender, startedDay);
        w.setActive(in.readBoolean());
        int n = in.readInt();
        for (int i = 0; i < n; i++) {
            w.participants().add(KingdomId.of(BinaryCodec.readUuid(in)));
        }
        return w;
    }

    private static void writeArmy(DataOutputStream out, ArmyState a) throws IOException {
        BinaryCodec.writeUuid(out, a.id().value());
        BinaryCodec.writeUuid(out, a.owner().value());
        out.writeInt(a.position().x());
        out.writeInt(a.position().z());
        out.writeInt(a.strength());
        out.writeInt(a.morale());
        out.writeDouble(a.supply());
        out.writeBoolean(a.siegeTarget() != null);
        if (a.siegeTarget() != null) {
            BinaryCodec.writeUuid(out, a.siegeTarget().value());
        }
        out.writeInt(a.siegeProgress());
    }

    private static ArmyState readArmy(DataInputStream in) throws IOException {
        ArmyId id = ArmyId.of(BinaryCodec.readUuid(in));
        KingdomId owner = KingdomId.of(BinaryCodec.readUuid(in));
        BlockPos2 pos = BlockPos2.of(in.readInt(), in.readInt());
        int strength = in.readInt();
        ArmyState a = new ArmyState(id, owner, pos, strength);
        a.setMorale(in.readInt());
        a.setSupply(in.readDouble());
        if (in.readBoolean()) {
            a.setSiegeTarget(SettlementId.of(BinaryCodec.readUuid(in)));
        }
        a.setSiegeProgress(in.readInt());
        return a;
    }

    private static void writeEpidemic(DataOutputStream out, EpidemicState e) throws IOException {
        BinaryCodec.writeUuid(out, e.id().value());
        BinaryCodec.writeString(out, e.pathogenKey());
        BinaryCodec.writeUuid(out, e.origin().value());
        out.writeLong(e.startedDay());
        out.writeDouble(e.transmissionRate());
        out.writeDouble(e.mortalityRate());
        out.writeBoolean(e.active());
        out.writeInt(e.affectedSettlements().size());
        for (SettlementId sid : e.affectedSettlements()) {
            BinaryCodec.writeUuid(out, sid.value());
        }
    }

    private static EpidemicState readEpidemic(DataInputStream in) throws IOException {
        EpidemicId id = EpidemicId.of(BinaryCodec.readUuid(in));
        String pathogen = BinaryCodec.readString(in);
        SettlementId origin = SettlementId.of(BinaryCodec.readUuid(in));
        long startedDay = in.readLong();
        EpidemicState e = new EpidemicState(id, pathogen, origin, startedDay);
        e.setTransmissionRate(in.readDouble());
        e.setMortalityRate(in.readDouble());
        e.setActive(in.readBoolean());
        int n = in.readInt();
        for (int i = 0; i < n; i++) {
            e.affectedSettlements().add(SettlementId.of(BinaryCodec.readUuid(in)));
        }
        return e;
    }

    private static void writeMigration(DataOutputStream out, MigrationGroupState m) throws IOException {
        BinaryCodec.writeUuid(out, m.id().value());
        BinaryCodec.writeUuid(out, m.source().value());
        BinaryCodec.writeUuid(out, m.destination().value());
        out.writeInt(m.population());
        out.writeInt(m.position().x());
        out.writeInt(m.position().z());
        BinaryCodec.writeString(out, m.reasonKey());
        out.writeBoolean(m.arrived());
    }

    private static MigrationGroupState readMigration(DataInputStream in) throws IOException {
        MigrationGroupId id = MigrationGroupId.of(BinaryCodec.readUuid(in));
        SettlementId source = SettlementId.of(BinaryCodec.readUuid(in));
        SettlementId dest = SettlementId.of(BinaryCodec.readUuid(in));
        int pop = in.readInt();
        BlockPos2 pos = BlockPos2.of(in.readInt(), in.readInt());
        String reason = BinaryCodec.readString(in);
        MigrationGroupState m = new MigrationGroupState(id, source, dest, pop, pos, reason);
        m.setArrived(in.readBoolean());
        return m;
    }

    private static void writeShipment(DataOutputStream out, ShipmentState s) throws IOException {
        BinaryCodec.writeUuid(out, s.id().value());
        BinaryCodec.writeUuid(out, s.source().value());
        BinaryCodec.writeUuid(out, s.destination().value());
        out.writeInt(s.goods().ordinal());
        out.writeDouble(s.quantity());
        out.writeInt(s.route().size());
        for (BlockPos2 p : s.route()) {
            out.writeInt(p.x());
            out.writeInt(p.z());
        }
        out.writeInt(s.routeIndex());
        out.writeBoolean(s.delivered());
    }

    private static ShipmentState readShipment(DataInputStream in) throws IOException {
        ShipmentId id = ShipmentId.of(BinaryCodec.readUuid(in));
        SettlementId source = SettlementId.of(BinaryCodec.readUuid(in));
        SettlementId dest = SettlementId.of(BinaryCodec.readUuid(in));
        ResourceType goods = ResourceType.values()[in.readInt()];
        double qty = in.readDouble();
        int routeLen = in.readInt();
        List<BlockPos2> route = new ArrayList<>(routeLen);
        for (int i = 0; i < routeLen; i++) {
            route.add(BlockPos2.of(in.readInt(), in.readInt()));
        }
        ShipmentState s = new ShipmentState(id, source, dest, goods, qty, route);
        s.setRouteIndex(in.readInt());
        s.setDelivered(in.readBoolean());
        return s;
    }

    private static void writeEcology(DataOutputStream out, EcologyState ecology) throws IOException {
        out.writeInt(ecology.cohorts().size());
        for (Map.Entry<RegionCoord, Map<SpeciesId, EcologyState.SpeciesCohort>> regionEntry : ecology.cohorts().entrySet()) {
            out.writeInt(regionEntry.getKey().x());
            out.writeInt(regionEntry.getKey().z());
            out.writeInt(regionEntry.getValue().size());
            for (EcologyState.SpeciesCohort cohort : regionEntry.getValue().values()) {
                BinaryCodec.writeUuid(out, cohort.species().value());
                out.writeDouble(cohort.population());
                out.writeDouble(cohort.biomass());
            }
        }
    }

    private static void readEcology(DataInputStream in, EcologyState ecology) throws IOException {
        int regionCount = in.readInt();
        for (int i = 0; i < regionCount; i++) {
            RegionCoord region = RegionCoord.of(in.readInt(), in.readInt());
            int speciesCount = in.readInt();
            Map<SpeciesId, EcologyState.SpeciesCohort> map = new HashMap<>();
            for (int j = 0; j < speciesCount; j++) {
                SpeciesId species = SpeciesId.of(BinaryCodec.readUuid(in));
                double pop = in.readDouble();
                double biomass = in.readDouble();
                map.put(species, new EcologyState.SpeciesCohort(species, pop, biomass));
            }
            ecology.cohorts().put(region, map);
        }
    }

    private static void writeTechnology(DataOutputStream out, TechnologyState tech) throws IOException {
        out.writeInt(tech.knownTechnologies().size());
        for (Map.Entry<SettlementId, Set<String>> e : tech.knownTechnologies().entrySet()) {
            BinaryCodec.writeUuid(out, e.getKey().value());
            out.writeInt(e.getValue().size());
            for (String key : e.getValue()) {
                BinaryCodec.writeString(out, key);
            }
        }
        out.writeInt(tech.researchProgress().size());
        for (Map.Entry<SettlementId, Map<String, Double>> e : tech.researchProgress().entrySet()) {
            BinaryCodec.writeUuid(out, e.getKey().value());
            out.writeInt(e.getValue().size());
            for (Map.Entry<String, Double> p : e.getValue().entrySet()) {
                BinaryCodec.writeString(out, p.getKey());
                out.writeDouble(p.getValue());
            }
        }
    }

    private static void readTechnology(DataInputStream in, TechnologyState tech) throws IOException {
        int knownCount = in.readInt();
        for (int i = 0; i < knownCount; i++) {
            SettlementId sid = SettlementId.of(BinaryCodec.readUuid(in));
            int n = in.readInt();
            Set<String> known = new HashSet<>();
            for (int j = 0; j < n; j++) {
                known.add(BinaryCodec.readString(in));
            }
            tech.knownTechnologies().put(sid, known);
        }
        int progressCount = in.readInt();
        for (int i = 0; i < progressCount; i++) {
            SettlementId sid = SettlementId.of(BinaryCodec.readUuid(in));
            int n = in.readInt();
            Map<String, Double> progress = new HashMap<>();
            for (int j = 0; j < n; j++) {
                progress.put(BinaryCodec.readString(in), in.readDouble());
            }
            tech.researchProgress().put(sid, progress);
        }
    }

    private static void writePlayerReputation(DataOutputStream out, PlayerReputationState rep) throws IOException {
        out.writeInt(rep.reputationByPlayer().size());
        for (Map.Entry<PlayerId, Map<KingdomId, Double>> e : rep.reputationByPlayer().entrySet()) {
            BinaryCodec.writeUuid(out, e.getKey().value());
            out.writeInt(e.getValue().size());
            for (Map.Entry<KingdomId, Double> k : e.getValue().entrySet()) {
                BinaryCodec.writeUuid(out, k.getKey().value());
                out.writeDouble(k.getValue());
            }
        }
    }

    private static void readPlayerReputation(DataInputStream in, PlayerReputationState rep) throws IOException {
        int playerCount = in.readInt();
        for (int i = 0; i < playerCount; i++) {
            PlayerId player = PlayerId.of(BinaryCodec.readUuid(in));
            int n = in.readInt();
            Map<KingdomId, Double> map = new HashMap<>();
            for (int j = 0; j < n; j++) {
                map.put(KingdomId.of(BinaryCodec.readUuid(in)), in.readDouble());
            }
            rep.reputationByPlayer().put(player, map);
        }
    }

    private static void writeHistory(DataOutputStream out, CanonicalWorldState state) throws IOException {
        List<HistoricalEvent> events = state.historySnapshot();
        int count = Math.min(events.size(), CanonicalWorldState.MAX_HISTORY_EVENTS);
        int start = events.size() - count;
        out.writeInt(count);
        for (int i = start; i < events.size(); i++) {
            HistoricalEvent e = events.get(i);
            BinaryCodec.writeUuid(out, e.id().value());
            out.writeInt(e.type().ordinal());
            out.writeLong(e.when().absoluteTicks());
            BinaryCodec.writeString(out, e.title());
            BinaryCodec.writeString(out, e.summary());
            out.writeBoolean(e.location().isPresent());
            if (e.location().isPresent()) {
                out.writeInt(e.location().get().x());
                out.writeInt(e.location().get().z());
            }
            out.writeInt(e.tags().size());
            for (Map.Entry<String, String> tag : e.tags().entrySet()) {
                BinaryCodec.writeString(out, tag.getKey());
                BinaryCodec.writeString(out, tag.getValue());
            }
        }
    }

    private static void readHistory(DataInputStream in, CanonicalWorldState state) throws IOException {
        int count = in.readInt();
        for (int i = 0; i < count; i++) {
            HistoricalEventId id = HistoricalEventId.of(BinaryCodec.readUuid(in));
            CivilizationEventType type = CivilizationEventType.values()[in.readInt()];
            SimulationTime when = SimulationTime.ofTicks(in.readLong());
            String title = BinaryCodec.readString(in);
            String summary = BinaryCodec.readString(in);
            Optional<BlockPos2> location = Optional.empty();
            if (in.readBoolean()) {
                location = Optional.of(BlockPos2.of(in.readInt(), in.readInt()));
            }
            int tagCount = in.readInt();
            Map<String, String> tags = new HashMap<>();
            for (int j = 0; j < tagCount; j++) {
                tags.put(BinaryCodec.readString(in), BinaryCodec.readString(in));
            }
            state.history().addLast(new HistoricalEvent(id, type, when, title, summary, location, Map.copyOf(tags)));
        }
    }
}
