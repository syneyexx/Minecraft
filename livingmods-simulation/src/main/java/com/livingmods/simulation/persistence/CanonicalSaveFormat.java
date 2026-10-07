package com.livingmods.simulation.persistence;

import com.livingmods.common.event.CivilizationEventType;
import com.livingmods.common.event.HistoricalEvent;
import com.livingmods.common.geo.BlockPos2;
import com.livingmods.common.geo.RegionCoord;
import com.livingmods.common.id.ArmyId;
import com.livingmods.common.id.CitizenId;
import com.livingmods.common.id.CrimeId;
import com.livingmods.common.id.CultureId;
import com.livingmods.common.id.DynastyId;
import com.livingmods.common.id.EpidemicId;
import com.livingmods.common.id.FactionId;
import com.livingmods.common.id.HistoricalEventId;
import com.livingmods.common.id.HouseholdId;
import com.livingmods.common.id.KingdomId;
import com.livingmods.common.id.MigrationGroupId;
import com.livingmods.common.id.PlayerId;
import com.livingmods.common.id.RoadId;
import com.livingmods.common.id.SettlementId;
import com.livingmods.common.id.ShipmentId;
import com.livingmods.common.id.SiegeId;
import com.livingmods.common.id.SpeciesId;
import com.livingmods.common.id.StructureId;
import com.livingmods.common.id.TreatyId;
import com.livingmods.common.id.WarId;
import com.livingmods.common.model.ArmyStatus;
import com.livingmods.common.model.CasusBelli;
import com.livingmods.common.model.CrimeStatus;
import com.livingmods.common.model.CrimeType;
import com.livingmods.common.model.CrimeVerdict;
import com.livingmods.common.model.DiplomaticRelation;
import com.livingmods.common.model.FamilyRelationType;
import com.livingmods.common.model.GovernmentType;
import com.livingmods.common.model.MilitaryRole;
import com.livingmods.common.model.Personality;
import com.livingmods.common.model.Profession;
import com.livingmods.common.model.ResourceType;
import com.livingmods.common.model.ScheduleState;
import com.livingmods.common.model.SettlementRole;
import com.livingmods.common.model.SettlementTier;
import com.livingmods.common.model.TreatyType;
import com.livingmods.common.model.WarObjective;
import com.livingmods.common.model.WealthClass;
import com.livingmods.common.time.SimulationTime;
import com.livingmods.protocol.BinaryCodec;
import com.livingmods.protocol.ProtocolConstants;
import com.livingmods.simulation.CanonicalWorldState;
import com.livingmods.simulation.state.ArmyState;
import com.livingmods.simulation.state.CitizenState;
import com.livingmods.simulation.state.CrimeState;
import com.livingmods.simulation.state.DiplomacyPair;
import com.livingmods.simulation.state.DiplomacyState;
import com.livingmods.simulation.state.DynastyState;
import com.livingmods.simulation.state.EcologyState;
import com.livingmods.simulation.state.EpidemicState;
import com.livingmods.simulation.state.FactionState;
import com.livingmods.simulation.state.FamilyRelationState;
import com.livingmods.simulation.state.HouseholdState;
import com.livingmods.simulation.state.IntelligenceState;
import com.livingmods.simulation.state.KingdomState;
import com.livingmods.simulation.state.MarketState;
import com.livingmods.simulation.state.MigrationGroupState;
import com.livingmods.simulation.state.PlayerReputationState;
import com.livingmods.simulation.state.SettlementState;
import com.livingmods.simulation.state.ShipmentState;
import com.livingmods.simulation.state.SiegeState;
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
 * Schema version 3 snapshot encoding for the full canonical world graph
 * (citizen family/housing/schedule, economy/trade caravans, politics/war/justice).
 */
public final class CanonicalSaveFormat {
    public static final int SCHEMA_VERSION = 3;
    public static final int MAGIC = 0x4C4D4353; // LMCS
    public static final int MAX_ENTITIES = 500_000;
    public static final int MAX_NESTED = 65_536;
    public static final int MAX_STRING = ProtocolConstants.MAX_STRING_LENGTH;

    private CanonicalSaveFormat() {}

    private static int readCount(DataInputStream in, int max, String label) throws IOException {
        int n = in.readInt();
        if (n < 0 || n > max) {
            throw new IOException("invalid " + label + " count: " + n + " (max " + max + ")");
        }
        return n;
    }

    private static <E extends Enum<E>> E readEnum(DataInputStream in, Class<E> type) throws IOException {
        int ordinal = in.readInt();
        E[] values = type.getEnumConstants();
        if (ordinal < 0 || ordinal >= values.length) {
            throw new IOException("invalid " + type.getSimpleName() + " ordinal: " + ordinal);
        }
        return values[ordinal];
    }

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

        out.writeInt(state.familyRelations().size());
        for (FamilyRelationState rel : state.familyRelations().values()) {
            writeFamilyRelation(out, rel);
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
        writeDynasties(out, state);
        writeCrimes(out, state);
        writeSieges(out, state);
        writeFactions(out, state);
        writeIntelligence(out, state.intelligence());

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

        int kingdomCount = readCount(in, MAX_ENTITIES, "kingdoms");
        for (int i = 0; i < kingdomCount; i++) {
            KingdomState k = readKingdom(in);
            if (state.kingdoms().put(k.id(), k) != null) {
                throw new IOException("duplicate kingdom id: " + k.id());
            }
        }

        int settlementCount = readCount(in, MAX_ENTITIES, "settlements");
        for (int i = 0; i < settlementCount; i++) {
            SettlementState s = readSettlement(in);
            if (state.settlements().put(s.id(), s) != null) {
                throw new IOException("duplicate settlement id: " + s.id());
            }
        }

        int householdCount = readCount(in, MAX_ENTITIES, "households");
        for (int i = 0; i < householdCount; i++) {
            HouseholdState h = readHousehold(in);
            if (state.households().put(h.id(), h) != null) {
                throw new IOException("duplicate household id: " + h.id());
            }
        }

        int citizenCount = readCount(in, MAX_ENTITIES, "citizens");
        for (int i = 0; i < citizenCount; i++) {
            CitizenState c = readCitizen(in);
            if (state.citizens().put(c.id(), c) != null) {
                throw new IOException("duplicate citizen id: " + c.id());
            }
        }

        int relationCount = readCount(in, MAX_ENTITIES, "familyRelations");
        for (int i = 0; i < relationCount; i++) {
            FamilyRelationState rel = readFamilyRelation(in);
            if (state.familyRelations().put(rel.id(), rel) != null) {
                throw new IOException("duplicate family relation id: " + rel.id());
            }
        }

        int stockpileCount = readCount(in, MAX_ENTITIES, "stockpiles");
        for (int i = 0; i < stockpileCount; i++) {
            StockpileState sp = readStockpile(in);
            state.stockpiles().put(sp.settlementId(), sp);
        }

        int marketCount = readCount(in, MAX_ENTITIES, "markets");
        for (int i = 0; i < marketCount; i++) {
            MarketState m = readMarket(in);
            state.markets().put(m.settlementId(), m);
        }

        readDiplomacy(in, state.diplomacy());

        int warCount = readCount(in, MAX_NESTED, "wars");
        for (int i = 0; i < warCount; i++) {
            WarState w = readWar(in);
            if (state.wars().put(w.id(), w) != null) {
                throw new IOException("duplicate war id: " + w.id());
            }
        }

        int armyCount = readCount(in, MAX_NESTED, "armies");
        for (int i = 0; i < armyCount; i++) {
            ArmyState a = readArmy(in);
            if (state.armies().put(a.id(), a) != null) {
                throw new IOException("duplicate army id: " + a.id());
            }
        }

        int epidemicCount = readCount(in, MAX_NESTED, "epidemics");
        for (int i = 0; i < epidemicCount; i++) {
            EpidemicState e = readEpidemic(in);
            if (state.epidemics().put(e.id(), e) != null) {
                throw new IOException("duplicate epidemic id: " + e.id());
            }
        }

        int migrationCount = readCount(in, MAX_NESTED, "migrations");
        for (int i = 0; i < migrationCount; i++) {
            MigrationGroupState m = readMigration(in);
            if (state.migrations().put(m.id(), m) != null) {
                throw new IOException("duplicate migration id: " + m.id());
            }
        }

        int shipmentCount = readCount(in, CanonicalWorldState.MAX_SHIPMENTS, "shipments");
        for (int i = 0; i < shipmentCount; i++) {
            ShipmentState s = readShipment(in);
            if (state.shipments().put(s.id(), s) != null) {
                throw new IOException("duplicate shipment id: " + s.id());
            }
        }

        readEcology(in, state.ecology());
        readTechnology(in, state.technology());
        readPlayerReputation(in, state.playerReputation());
        readHistory(in, state);
        readDynasties(in, state);
        readCrimes(in, state);
        readSieges(in, state);
        readFactions(in, state);
        readIntelligence(in, state.intelligence());

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
        BinaryCodec.writeUuid(out, k.dynastyId() == null ? null : k.dynastyId().value());
        BinaryCodec.writeString(out, k.religionKey());
        out.writeInt(k.adjacentKingdoms().size());
        for (KingdomId adj : k.adjacentKingdoms()) {
            BinaryCodec.writeUuid(out, adj.value());
        }
        out.writeDouble(k.warExhaustion());
        out.writeDouble(k.publicOpinion());
        out.writeDouble(k.propaganda());
        out.writeDouble(k.unrest());
    }

    private static KingdomState readKingdom(DataInputStream in) throws IOException {
        KingdomId id = KingdomId.of(BinaryCodec.readUuid(in));
        String name = BinaryCodec.readString(in);
        CultureId cultureId = CultureId.of(BinaryCodec.readUuid(in));
        GovernmentType gov = readEnum(in, GovernmentType.class);
        SettlementId capitalId = SettlementId.of(BinaryCodec.readUuid(in));
        CitizenId rulerId = CitizenId.of(BinaryCodec.readUuid(in));
        int n = readCount(in, MAX_NESTED, "kingdom.settlements");
        List<SettlementId> settlements = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            settlements.add(SettlementId.of(BinaryCodec.readUuid(in)));
        }
        double treasury = in.readDouble();
        double tax = in.readDouble();
        double legitimacy = in.readDouble();
        KingdomState k = new KingdomState(id, name, cultureId, gov, capitalId, rulerId, settlements, treasury, tax, legitimacy);
        UUID dynasty = BinaryCodec.readUuid(in);
        if (dynasty != null) k.setDynastyId(DynastyId.of(dynasty));
        k.setReligionKey(BinaryCodec.readString(in));
        int adjN = readCount(in, MAX_NESTED, "kingdom.adjacent");
        for (int i = 0; i < adjN; i++) {
            k.adjacentKingdoms().add(KingdomId.of(BinaryCodec.readUuid(in)));
        }
        k.setWarExhaustion(in.readDouble());
        k.setPublicOpinion(in.readDouble());
        k.setPropaganda(in.readDouble());
        k.setUnrest(in.readDouble());
        return k;
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
        out.writeDouble(s.unrest());
        out.writeDouble(s.security());
        out.writeDouble(s.hunger());
    }

    private static SettlementState readSettlement(DataInputStream in) throws IOException {
        SettlementId id = SettlementId.of(BinaryCodec.readUuid(in));
        String name = BinaryCodec.readString(in);
        SettlementTier tier = readEnum(in, SettlementTier.class);
        SettlementRole role = readEnum(in, SettlementRole.class);
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
        SettlementState s = new SettlementState(id, name, tier, role, center, owner, capital, rulerId,
                legitimacy, deficit, capacity, housing, employed);
        s.setUnrest(in.readDouble());
        s.setSecurity(in.readDouble());
        s.setHunger(in.readDouble());
        return s;
    }

    private static void writeHousehold(DataOutputStream out, HouseholdState h) throws IOException {
        BinaryCodec.writeUuid(out, h.id().value());
        BinaryCodec.writeUuid(out, h.settlementId().value());
        out.writeInt(h.memberCount());
        out.writeDouble(h.foodStores());
        out.writeInt(h.housingQuality());
        BinaryCodec.writeUuid(out, h.homeStructureId() == null ? null : h.homeStructureId().value());
        BinaryCodec.writeUuid(out, h.headId() == null ? null : h.headId().value());
        BinaryCodec.writeString(out, h.institutionKind());
    }

    private static HouseholdState readHousehold(DataInputStream in) throws IOException {
        HouseholdId id = HouseholdId.of(BinaryCodec.readUuid(in));
        SettlementId settlementId = SettlementId.of(BinaryCodec.readUuid(in));
        int members = in.readInt();
        double food = in.readDouble();
        int quality = in.readInt();
        HouseholdState h = new HouseholdState(id, settlementId, members, food, quality);
        UUID home = BinaryCodec.readUuid(in);
        if (home != null) {
            h.setHomeStructureId(StructureId.of(home));
        }
        UUID head = BinaryCodec.readUuid(in);
        if (head != null) {
            h.setHeadId(CitizenId.of(head));
        }
        h.setInstitutionKind(BinaryCodec.readString(in));
        return h;
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
        BinaryCodec.writeUuid(out, c.motherId() == null ? null : c.motherId().value());
        BinaryCodec.writeUuid(out, c.fatherId() == null ? null : c.fatherId().value());
        BinaryCodec.writeUuid(out, c.spouseId() == null ? null : c.spouseId().value());
        BinaryCodec.writeUuid(out, c.guardianId() == null ? null : c.guardianId().value());
        out.writeBoolean(c.adoptive());
        BinaryCodec.writeUuid(out, c.dynastyId() == null ? null : c.dynastyId().value());
        BinaryCodec.writeUuid(out, c.homeStructureId() == null ? null : c.homeStructureId().value());
        BinaryCodec.writeUuid(out, c.workStructureId() == null ? null : c.workStructureId().value());
        out.writeInt(c.schedule().ordinal());
        out.writeInt(c.wealthClass().ordinal());
        out.writeInt(c.militaryRole().ordinal());
        out.writeInt(c.educationYears());
        out.writeBoolean(c.orphanageResident());
        out.writeLong(c.projectionRevision());
        out.writeLong(c.lastBirthDay());
        out.writeDouble(c.literacy());
        out.writeDouble(c.skill());
        out.writeLong(c.sentenceEndsDay());
        out.writeBoolean(c.noble());
        out.writeBoolean(c.heir());
        out.writeBoolean(c.personality() != null);
        if (c.personality() != null) {
            Personality p = c.personality();
            out.writeDouble(p.aggression());
            out.writeDouble(p.caution());
            out.writeDouble(p.greed());
            out.writeDouble(p.loyalty());
            out.writeDouble(p.ambition());
            out.writeDouble(p.sociability());
            out.writeDouble(p.tradeAffinity());
            out.writeDouble(p.treachery());
        }
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
        Profession profession = readEnum(in, Profession.class);
        double health = in.readDouble();
        double wealth = in.readDouble();
        boolean alive = in.readBoolean();
        boolean ruler = in.readBoolean();
        CitizenState c = new CitizenState(id, given, family, female, birth, cultureId, settlementId,
                householdId, profession, health, wealth, alive, ruler);
        c.setCrimeStrikes(in.readInt());
        c.setIncarcerated(in.readBoolean());
        UUID mother = BinaryCodec.readUuid(in);
        UUID father = BinaryCodec.readUuid(in);
        UUID spouse = BinaryCodec.readUuid(in);
        UUID guardian = BinaryCodec.readUuid(in);
        if (mother != null) c.setMotherId(CitizenId.of(mother));
        if (father != null) c.setFatherId(CitizenId.of(father));
        if (spouse != null) c.setSpouseId(CitizenId.of(spouse));
        if (guardian != null) c.setGuardianId(CitizenId.of(guardian));
        c.setAdoptive(in.readBoolean());
        UUID dynasty = BinaryCodec.readUuid(in);
        if (dynasty != null) c.setDynastyId(DynastyId.of(dynasty));
        UUID home = BinaryCodec.readUuid(in);
        UUID work = BinaryCodec.readUuid(in);
        if (home != null) c.setHomeStructureId(StructureId.of(home));
        if (work != null) c.setWorkStructureId(StructureId.of(work));
        c.setSchedule(readEnum(in, ScheduleState.class));
        c.setWealthClass(readEnum(in, WealthClass.class));
        c.setMilitaryRole(readEnum(in, MilitaryRole.class));
        c.setEducationYears(in.readInt());
        c.setOrphanageResident(in.readBoolean());
        c.setProjectionRevision(in.readLong());
        c.setLastBirthDay(in.readLong());
        c.setLiteracy(in.readDouble());
        c.setSkill(in.readDouble());
        c.setSentenceEndsDay(in.readLong());
        c.setNoble(in.readBoolean());
        c.setHeir(in.readBoolean());
        if (in.readBoolean()) {
            c.setPersonality(new Personality(
                    in.readDouble(), in.readDouble(), in.readDouble(), in.readDouble(),
                    in.readDouble(), in.readDouble(), in.readDouble(), in.readDouble()));
        }
        return c;
    }

    private static void writeFamilyRelation(DataOutputStream out, FamilyRelationState rel) throws IOException {
        BinaryCodec.writeUuid(out, rel.id());
        BinaryCodec.writeUuid(out, rel.from().value());
        BinaryCodec.writeUuid(out, rel.to().value());
        out.writeInt(rel.type().ordinal());
        out.writeBoolean(rel.active());
    }

    private static FamilyRelationState readFamilyRelation(DataInputStream in) throws IOException {
        UUID id = BinaryCodec.readUuid(in);
        CitizenId from = CitizenId.of(BinaryCodec.readUuid(in));
        CitizenId to = CitizenId.of(BinaryCodec.readUuid(in));
        FamilyRelationType type = readEnum(in, FamilyRelationType.class);
        boolean active = in.readBoolean();
        return new FamilyRelationState(id, from, to, type, active);
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
            qty.put(readEnum(in, ResourceType.class), in.readDouble());
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
        out.writeDouble(m.transportCostFactor());
        out.writeDouble(m.taxPressure());
        out.writeDouble(m.riskPressure());
        out.writeDouble(m.warPressure());
        out.writeDouble(m.diseasePressure());
        out.writeDouble(m.securityResponse());
    }

    private static MarketState readMarket(DataInputStream in) throws IOException {
        SettlementId id = SettlementId.of(BinaryCodec.readUuid(in));
        MarketState m = new MarketState(id);
        m.setCrisisSeverity(in.readDouble());
        int n = in.readInt();
        for (int i = 0; i < n; i++) {
            m.setPrice(readEnum(in, ResourceType.class), in.readDouble());
        }
        m.setTransportCostFactor(in.readDouble());
        m.setTaxPressure(in.readDouble());
        m.setRiskPressure(in.readDouble());
        m.setWarPressure(in.readDouble());
        m.setDiseasePressure(in.readDouble());
        m.setSecurityResponse(in.readDouble());
        return m;
    }

    private static void writeDiplomacy(DataOutputStream out, DiplomacyState dip) throws IOException {
        out.writeInt(dip.relations().size());
        for (Map.Entry<DiplomacyPair, DiplomaticRelation> e : dip.relations().entrySet()) {
            BinaryCodec.writeUuid(out, e.getKey().first().value());
            BinaryCodec.writeUuid(out, e.getKey().second().value());
            out.writeInt(e.getValue().ordinal());
        }
        out.writeInt(dip.scores().size());
        for (Map.Entry<DiplomacyPair, Double> e : dip.scores().entrySet()) {
            BinaryCodec.writeUuid(out, e.getKey().first().value());
            BinaryCodec.writeUuid(out, e.getKey().second().value());
            out.writeDouble(e.getValue());
        }
        out.writeInt(dip.historicFriction().size());
        for (Map.Entry<DiplomacyPair, Double> e : dip.historicFriction().entrySet()) {
            BinaryCodec.writeUuid(out, e.getKey().first().value());
            BinaryCodec.writeUuid(out, e.getKey().second().value());
            out.writeDouble(e.getValue());
        }
        out.writeInt(dip.treaties().size());
        for (DiplomacyState.TreatyRecord t : dip.treaties().values()) {
            BinaryCodec.writeUuid(out, t.id().value());
            BinaryCodec.writeUuid(out, t.a().value());
            BinaryCodec.writeUuid(out, t.b().value());
            out.writeInt(t.type().ordinal());
            out.writeLong(t.signedDay());
            out.writeLong(t.expirationDay());
            out.writeBoolean(t.active());
            BinaryCodec.writeString(out, t.effectsKey());
            out.writeBoolean(t.violated());
        }
    }

    private static void readDiplomacy(DataInputStream in, DiplomacyState dip) throws IOException {
        int relCount = in.readInt();
        for (int i = 0; i < relCount; i++) {
            KingdomId a = KingdomId.of(BinaryCodec.readUuid(in));
            KingdomId b = KingdomId.of(BinaryCodec.readUuid(in));
            DiplomaticRelation rel = readEnum(in, DiplomaticRelation.class);
            dip.setRelation(a, b, rel);
        }
        int scoreCount = in.readInt();
        for (int i = 0; i < scoreCount; i++) {
            KingdomId a = KingdomId.of(BinaryCodec.readUuid(in));
            KingdomId b = KingdomId.of(BinaryCodec.readUuid(in));
            dip.setScore(a, b, in.readDouble());
        }
        int frictionCount = in.readInt();
        for (int i = 0; i < frictionCount; i++) {
            KingdomId a = KingdomId.of(BinaryCodec.readUuid(in));
            KingdomId b = KingdomId.of(BinaryCodec.readUuid(in));
            dip.addHistoricFriction(a, b, in.readDouble());
        }
        int treatyCount = in.readInt();
        for (int i = 0; i < treatyCount; i++) {
            TreatyId id = TreatyId.of(BinaryCodec.readUuid(in));
            KingdomId a = KingdomId.of(BinaryCodec.readUuid(in));
            KingdomId b = KingdomId.of(BinaryCodec.readUuid(in));
            TreatyType type = readEnum(in, TreatyType.class);
            long signedDay = in.readLong();
            long expiration = in.readLong();
            boolean active = in.readBoolean();
            String effects = BinaryCodec.readString(in);
            boolean violated = in.readBoolean();
            dip.treaties().put(id, new DiplomacyState.TreatyRecord(
                    id, a, b, type, signedDay, expiration, active, effects, violated));
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
        out.writeInt(w.casusBelli().ordinal());
        out.writeInt(w.primaryObjective().ordinal());
        BinaryCodec.writeUuid(out, w.objectiveSettlement() == null ? null : w.objectiveSettlement().value());
        out.writeDouble(w.warExhaustionAggressor());
        out.writeDouble(w.warExhaustionDefender());
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
        w.setCasusBelli(readEnum(in, CasusBelli.class));
        w.setPrimaryObjective(readEnum(in, WarObjective.class));
        UUID obj = BinaryCodec.readUuid(in);
        if (obj != null) w.setObjectiveSettlement(SettlementId.of(obj));
        w.setWarExhaustionAggressor(in.readDouble());
        w.setWarExhaustionDefender(in.readDouble());
        return w;
    }

    private static void writeArmy(DataOutputStream out, ArmyState a) throws IOException {
        BinaryCodec.writeUuid(out, a.id().value());
        BinaryCodec.writeUuid(out, a.owner().value());
        out.writeInt(a.position().x());
        out.writeInt(a.position().z());
        out.writeInt(a.manpower());
        out.writeInt(a.morale());
        out.writeDouble(a.supply());
        out.writeBoolean(a.siegeTarget() != null);
        if (a.siegeTarget() != null) {
            BinaryCodec.writeUuid(out, a.siegeTarget().value());
        }
        out.writeInt(a.siegeProgress());
        BinaryCodec.writeUuid(out, a.commanderId() == null ? null : a.commanderId().value());
        out.writeInt(a.infantry());
        out.writeInt(a.cavalry());
        out.writeInt(a.equipment());
        out.writeInt(a.status().ordinal());
        out.writeInt(a.objective().ordinal());
        BinaryCodec.writeUuid(out, a.objectiveSettlement() == null ? null : a.objectiveSettlement().value());
        BinaryCodec.writeUuid(out, a.warId() == null ? null : a.warId().value());
        out.writeInt(a.route().size());
        for (BlockPos2 p : a.route()) {
            out.writeInt(p.x());
            out.writeInt(p.z());
        }
        out.writeInt(a.routeIndex());
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
        UUID commander = BinaryCodec.readUuid(in);
        if (commander != null) a.setCommanderId(CitizenId.of(commander));
        a.setInfantry(in.readInt());
        a.setCavalry(in.readInt());
        a.setEquipment(in.readInt());
        a.setStatus(readEnum(in, ArmyStatus.class));
        a.setObjective(readEnum(in, WarObjective.class));
        UUID obj = BinaryCodec.readUuid(in);
        if (obj != null) a.setObjectiveSettlement(SettlementId.of(obj));
        UUID war = BinaryCodec.readUuid(in);
        if (war != null) a.setWarId(WarId.of(war));
        int routeLen = in.readInt();
        a.route().clear();
        for (int i = 0; i < routeLen; i++) {
            a.route().add(BlockPos2.of(in.readInt(), in.readInt()));
        }
        a.setRouteIndex(in.readInt());
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
        out.writeInt(s.roadSegments().size());
        for (RoadId road : s.roadSegments()) {
            BinaryCodec.writeUuid(out, road.value());
        }
        out.writeInt(s.routeIndex());
        out.writeBoolean(s.delivered());
        out.writeBoolean(s.looted());
        out.writeInt(s.guards());
        out.writeDouble(s.risk());
        out.writeLong(s.departedDay());
        out.writeLong(s.etaDay());
    }

    private static ShipmentState readShipment(DataInputStream in) throws IOException {
        ShipmentId id = ShipmentId.of(BinaryCodec.readUuid(in));
        SettlementId source = SettlementId.of(BinaryCodec.readUuid(in));
        SettlementId dest = SettlementId.of(BinaryCodec.readUuid(in));
        ResourceType goods = readEnum(in, ResourceType.class);
        double qty = in.readDouble();
        int routeLen = in.readInt();
        List<BlockPos2> route = new ArrayList<>(routeLen);
        for (int i = 0; i < routeLen; i++) {
            route.add(BlockPos2.of(in.readInt(), in.readInt()));
        }
        int roadLen = in.readInt();
        List<RoadId> roads = new ArrayList<>(roadLen);
        for (int i = 0; i < roadLen; i++) {
            roads.add(RoadId.of(BinaryCodec.readUuid(in)));
        }
        int routeIndex = in.readInt();
        boolean delivered = in.readBoolean();
        boolean looted = in.readBoolean();
        int guards = in.readInt();
        double risk = in.readDouble();
        long departed = in.readLong();
        long eta = in.readLong();
        ShipmentState s = new ShipmentState(id, source, dest, goods, qty, route, roads, guards, risk, departed, eta);
        s.setRouteIndex(routeIndex);
        s.setDelivered(delivered);
        s.setLooted(looted);
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
        int count = readCount(in, CanonicalWorldState.MAX_HISTORY_EVENTS, "history");
        for (int i = 0; i < count; i++) {
            HistoricalEventId id = HistoricalEventId.of(BinaryCodec.readUuid(in));
            CivilizationEventType type = readEnum(in, CivilizationEventType.class);
            SimulationTime when = SimulationTime.ofTicks(in.readLong());
            String title = BinaryCodec.readString(in);
            String summary = BinaryCodec.readString(in);
            Optional<BlockPos2> location = Optional.empty();
            if (in.readBoolean()) {
                location = Optional.of(BlockPos2.of(in.readInt(), in.readInt()));
            }
            int tagCount = readCount(in, 64, "history.tags");
            Map<String, String> tags = new HashMap<>();
            for (int j = 0; j < tagCount; j++) {
                tags.put(BinaryCodec.readString(in), BinaryCodec.readString(in));
            }
            state.history().addLast(new HistoricalEvent(id, type, when, title, summary, location, Map.copyOf(tags)));
        }
    }

    private static void writeDynasties(DataOutputStream out, CanonicalWorldState state) throws IOException {
        out.writeInt(state.dynasties().size());
        for (DynastyState d : state.dynasties().values()) {
            BinaryCodec.writeUuid(out, d.id().value());
            BinaryCodec.writeString(out, d.name());
            BinaryCodec.writeUuid(out, d.founderId() == null ? null : d.founderId().value());
            BinaryCodec.writeUuid(out, d.currentHeadId() == null ? null : d.currentHeadId().value());
            out.writeDouble(d.prestige());
            out.writeInt(d.members().size());
            for (CitizenId m : d.members()) {
                BinaryCodec.writeUuid(out, m.value());
            }
            out.writeInt(d.kingdomClaims().size());
            for (KingdomId k : d.kingdomClaims()) {
                BinaryCodec.writeUuid(out, k.value());
            }
            out.writeInt(d.settlementClaims().size());
            for (SettlementId s : d.settlementClaims()) {
                BinaryCodec.writeUuid(out, s.value());
            }
        }
    }

    private static void readDynasties(DataInputStream in, CanonicalWorldState state) throws IOException {
        int count = readCount(in, MAX_NESTED, "dynasties");
        for (int i = 0; i < count; i++) {
            DynastyId id = DynastyId.of(BinaryCodec.readUuid(in));
            String name = BinaryCodec.readString(in);
            UUID founder = BinaryCodec.readUuid(in);
            UUID head = BinaryCodec.readUuid(in);
            DynastyState d = new DynastyState(
                    id, name,
                    founder == null ? null : CitizenId.of(founder),
                    head == null ? null : CitizenId.of(head));
            d.setPrestige(in.readDouble());
            int members = readCount(in, MAX_NESTED, "dynasty.members");
            for (int j = 0; j < members; j++) {
                d.members().add(CitizenId.of(BinaryCodec.readUuid(in)));
            }
            int kc = readCount(in, MAX_NESTED, "dynasty.kingdomClaims");
            for (int j = 0; j < kc; j++) {
                d.kingdomClaims().add(KingdomId.of(BinaryCodec.readUuid(in)));
            }
            int sc = readCount(in, MAX_NESTED, "dynasty.settlementClaims");
            for (int j = 0; j < sc; j++) {
                d.settlementClaims().add(SettlementId.of(BinaryCodec.readUuid(in)));
            }
            if (state.dynasties().put(id, d) != null) {
                throw new IOException("duplicate dynasty id: " + id);
            }
        }
    }

    private static void writeCrimes(DataOutputStream out, CanonicalWorldState state) throws IOException {
        out.writeInt(state.crimes().size());
        for (CrimeState c : state.crimes().values()) {
            BinaryCodec.writeUuid(out, c.id().value());
            out.writeInt(c.type().ordinal());
            BinaryCodec.writeUuid(out, c.suspectId().value());
            BinaryCodec.writeUuid(out, c.victimId().value());
            BinaryCodec.writeUuid(out, c.jurisdiction().value());
            out.writeLong(c.committedDay());
            out.writeInt(c.witnesses().size());
            for (CitizenId w : c.witnesses()) {
                BinaryCodec.writeUuid(out, w.value());
            }
            out.writeDouble(c.evidence());
            out.writeInt(c.status().ordinal());
            out.writeInt(c.verdict().ordinal());
            out.writeInt(c.sentenceDays());
            out.writeLong(c.sentenceEndsDay());
        }
    }

    private static void readCrimes(DataInputStream in, CanonicalWorldState state) throws IOException {
        int count = readCount(in, CanonicalWorldState.MAX_CRIMES, "crimes");
        for (int i = 0; i < count; i++) {
            CrimeId id = CrimeId.of(BinaryCodec.readUuid(in));
            CrimeType type = readEnum(in, CrimeType.class);
            CitizenId suspect = CitizenId.of(BinaryCodec.readUuid(in));
            CitizenId victim = CitizenId.of(BinaryCodec.readUuid(in));
            SettlementId jurisdiction = SettlementId.of(BinaryCodec.readUuid(in));
            long day = in.readLong();
            CrimeState c = new CrimeState(id, type, suspect, victim, jurisdiction, day);
            int wn = readCount(in, 64, "crime.witnesses");
            for (int j = 0; j < wn; j++) {
                c.witnesses().add(CitizenId.of(BinaryCodec.readUuid(in)));
            }
            c.setEvidence(in.readDouble());
            c.setStatus(readEnum(in, CrimeStatus.class));
            c.setVerdict(readEnum(in, CrimeVerdict.class));
            c.setSentenceDays(in.readInt());
            c.setSentenceEndsDay(in.readLong());
            if (state.crimes().put(id, c) != null) {
                throw new IOException("duplicate crime id: " + id);
            }
        }
    }

    private static void writeSieges(DataOutputStream out, CanonicalWorldState state) throws IOException {
        out.writeInt(state.sieges().size());
        for (SiegeState s : state.sieges().values()) {
            BinaryCodec.writeUuid(out, s.id().value());
            BinaryCodec.writeUuid(out, s.target().value());
            BinaryCodec.writeUuid(out, s.warId().value());
            out.writeInt(s.attackers().size());
            for (ArmyId a : s.attackers()) BinaryCodec.writeUuid(out, a.value());
            out.writeInt(s.defenders().size());
            for (ArmyId a : s.defenders()) BinaryCodec.writeUuid(out, a.value());
            out.writeDouble(s.attackerSupplies());
            out.writeDouble(s.defenderSupplies());
            out.writeBoolean(s.blockade());
            out.writeDouble(s.progress());
            out.writeInt(s.breaches());
            out.writeBoolean(s.surrendered());
            out.writeBoolean(s.active());
        }
    }

    private static void readSieges(DataInputStream in, CanonicalWorldState state) throws IOException {
        int count = readCount(in, MAX_NESTED, "sieges");
        for (int i = 0; i < count; i++) {
            SiegeId id = SiegeId.of(BinaryCodec.readUuid(in));
            SettlementId target = SettlementId.of(BinaryCodec.readUuid(in));
            WarId warId = WarId.of(BinaryCodec.readUuid(in));
            SiegeState s = new SiegeState(id, target, warId);
            int an = readCount(in, MAX_NESTED, "siege.attackers");
            for (int j = 0; j < an; j++) s.attackers().add(ArmyId.of(BinaryCodec.readUuid(in)));
            int dn = readCount(in, MAX_NESTED, "siege.defenders");
            for (int j = 0; j < dn; j++) s.defenders().add(ArmyId.of(BinaryCodec.readUuid(in)));
            s.setAttackerSupplies(in.readDouble());
            s.setDefenderSupplies(in.readDouble());
            s.setBlockade(in.readBoolean());
            s.setProgress(in.readDouble());
            s.setBreaches(in.readInt());
            s.setSurrendered(in.readBoolean());
            s.setActive(in.readBoolean());
            if (state.sieges().put(id, s) != null) {
                throw new IOException("duplicate siege id: " + id);
            }
        }
    }

    private static void writeFactions(DataOutputStream out, CanonicalWorldState state) throws IOException {
        out.writeInt(state.factions().size());
        for (FactionState f : state.factions().values()) {
            BinaryCodec.writeUuid(out, f.id().value());
            BinaryCodec.writeString(out, f.name());
            BinaryCodec.writeUuid(out, f.origin().value());
            BinaryCodec.writeUuid(out, f.againstKingdom().value());
            BinaryCodec.writeString(out, f.causeKey());
            out.writeLong(f.formedDay());
            BinaryCodec.writeUuid(out, f.armyId() == null ? null : f.armyId().value());
            out.writeDouble(f.strength());
            out.writeBoolean(f.active());
        }
    }

    private static void readFactions(DataInputStream in, CanonicalWorldState state) throws IOException {
        int count = readCount(in, MAX_NESTED, "factions");
        for (int i = 0; i < count; i++) {
            FactionId id = FactionId.of(BinaryCodec.readUuid(in));
            String name = BinaryCodec.readString(in);
            SettlementId origin = SettlementId.of(BinaryCodec.readUuid(in));
            KingdomId against = KingdomId.of(BinaryCodec.readUuid(in));
            String cause = BinaryCodec.readString(in);
            long formed = in.readLong();
            FactionState f = new FactionState(id, name, origin, against, cause, formed);
            UUID army = BinaryCodec.readUuid(in);
            if (army != null) f.setArmyId(ArmyId.of(army));
            f.setStrength(in.readDouble());
            f.setActive(in.readBoolean());
            if (state.factions().put(id, f) != null) {
                throw new IOException("duplicate faction id: " + id);
            }
        }
    }

    private static void writeIntelligence(DataOutputStream out, IntelligenceState intel) throws IOException {
        out.writeInt(intel.knowledge().size());
        for (Map.Entry<KingdomId, Map<KingdomId, Double>> e : intel.knowledge().entrySet()) {
            BinaryCodec.writeUuid(out, e.getKey().value());
            out.writeInt(e.getValue().size());
            for (Map.Entry<KingdomId, Double> row : e.getValue().entrySet()) {
                BinaryCodec.writeUuid(out, row.getKey().value());
                out.writeDouble(row.getValue());
            }
        }
    }

    private static void readIntelligence(DataInputStream in, IntelligenceState intel) throws IOException {
        int observers = readCount(in, MAX_NESTED, "intelligence.observers");
        for (int i = 0; i < observers; i++) {
            KingdomId observer = KingdomId.of(BinaryCodec.readUuid(in));
            int n = readCount(in, MAX_NESTED, "intelligence.subjects");
            for (int j = 0; j < n; j++) {
                KingdomId subject = KingdomId.of(BinaryCodec.readUuid(in));
                intel.setQuality(observer, subject, in.readDouble());
            }
        }
    }
}
