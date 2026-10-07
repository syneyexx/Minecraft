package com.livingmods.simulation.persistence;

import com.livingmods.common.geo.BlockPos2;
import com.livingmods.common.id.CitizenId;
import com.livingmods.common.id.CultureId;
import com.livingmods.common.id.HouseholdId;
import com.livingmods.common.id.KingdomId;
import com.livingmods.common.id.SettlementId;
import com.livingmods.common.model.GovernmentType;
import com.livingmods.common.model.Profession;
import com.livingmods.common.model.ResourceType;
import com.livingmods.common.model.SettlementRole;
import com.livingmods.common.model.SettlementTier;
import com.livingmods.common.time.SimulationTime;
import com.livingmods.protocol.BinaryCodec;
import com.livingmods.simulation.CanonicalWorldState;
import com.livingmods.simulation.state.CitizenState;
import com.livingmods.simulation.state.KingdomState;
import com.livingmods.simulation.state.MarketState;
import com.livingmods.simulation.state.SettlementState;
import com.livingmods.simulation.state.StockpileState;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Schema version 1 snapshot encoding for canonical world state.
 * Serializes kingdoms, settlements, citizens, stockpiles, and markets.
 */
public final class CanonicalSaveFormat {
    public static final int SCHEMA_VERSION = 1;
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
        ByteArrayOutputStream bos = new ByteArrayOutputStream(64 * 1024);
        DataOutputStream out = new DataOutputStream(bos);
        out.writeInt(MAGIC);
        out.writeInt(SCHEMA_VERSION);
        out.writeLong(state.seed());
        out.writeLong(state.time().absoluteTicks());
        out.writeLong(state.saveRevision());
        out.writeLong(state.planContentHash());
        out.writeLong(state.contentHash());
        BinaryCodec.writeUuid(out, worldSessionId);

        out.writeInt(state.kingdoms().size());
        for (KingdomState k : state.kingdoms().values()) {
            writeKingdom(out, k);
        }

        out.writeInt(state.settlements().size());
        for (SettlementState s : state.settlements().values()) {
            writeSettlement(out, s);
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
            throw new IOException("unsupported schema: " + version);
        }
        long seed = in.readLong();
        long ticks = in.readLong();
        long revision = in.readLong();
        long planHash = in.readLong();
        in.readLong(); // contentHash (verified after load if desired)
        BinaryCodec.readUuid(in);

        CanonicalWorldState state = new CanonicalWorldState(seed, SimulationTime.ofTicks(ticks), planHash);
        while (state.saveRevision() < revision) {
            state.bumpSaveRevision();
        }

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
}
