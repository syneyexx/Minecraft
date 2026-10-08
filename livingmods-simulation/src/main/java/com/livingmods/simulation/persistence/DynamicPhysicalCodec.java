package com.livingmods.simulation.persistence;

import com.livingmods.common.geo.BlockPos2;
import com.livingmods.common.geo.BoundingBox2;
import com.livingmods.common.id.KingdomId;
import com.livingmods.common.id.PhysicalIntentId;
import com.livingmods.common.id.SettlementId;
import com.livingmods.common.id.StructureId;
import com.livingmods.common.model.BuildingRole;
import com.livingmods.common.model.PhysicalIntentStatus;
import com.livingmods.common.model.PhysicalIntentType;
import com.livingmods.common.model.ResourceType;
import com.livingmods.common.model.StructureIntegrityStatus;
import com.livingmods.common.time.SimulationTime;
import com.livingmods.protocol.BinaryCodec;
import com.livingmods.simulation.physical.DynamicPhysicalState;
import com.livingmods.simulation.physical.DynamicSettlementGeometry;
import com.livingmods.simulation.physical.DynamicStructureRecord;
import com.livingmods.simulation.physical.PhysicalIntent;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Codec for DynamicPhysicalState within canonical save schema ≥ 4. */
public final class DynamicPhysicalCodec {
    private DynamicPhysicalCodec() {}

    public static void write(DataOutputStream out, DynamicPhysicalState state) throws IOException {
        out.writeLong(state.physicalDeltaRevision());

        out.writeInt(state.intents().size());
        for (PhysicalIntent intent : state.intents().values()) {
            writeIntent(out, intent);
        }

        out.writeInt(state.structures().size());
        for (DynamicStructureRecord rec : state.structures().values()) {
            writeStructure(out, rec);
        }

        out.writeInt(state.settlementGeometry().size());
        for (DynamicSettlementGeometry geo : state.settlementGeometry().values()) {
            writeGeometry(out, geo);
        }
    }

    public static void read(DataInputStream in, DynamicPhysicalState state) throws IOException {
        long revision = in.readLong();
        while (state.physicalDeltaRevision() < revision) {
            state.bumpPhysicalDeltaRevision();
        }

        int intentCount = CanonicalSaveFormat.readCountPublic(in, DynamicPhysicalState.MAX_INTENTS, "physicalIntents");
        for (int i = 0; i < intentCount; i++) {
            PhysicalIntent intent = readIntent(in);
            if (state.intents().put(intent.id(), intent) != null) {
                throw new IOException("duplicate physical intent: " + intent.id());
            }
        }

        int structureCount = CanonicalSaveFormat.readCountPublic(in, DynamicPhysicalState.MAX_STRUCTURES, "dynamicStructures");
        for (int i = 0; i < structureCount; i++) {
            DynamicStructureRecord rec = readStructure(in);
            if (state.structures().put(rec.structureId(), rec) != null) {
                throw new IOException("duplicate dynamic structure: " + rec.structureId());
            }
        }

        int geoCount = CanonicalSaveFormat.readCountPublic(in, DynamicPhysicalState.MAX_SETTLEMENT_GEOMETRIES, "settlementGeometry");
        for (int i = 0; i < geoCount; i++) {
            DynamicSettlementGeometry geo = readGeometry(in);
            if (state.settlementGeometry().put(geo.settlementId(), geo) != null) {
                throw new IOException("duplicate settlement geometry: " + geo.settlementId());
            }
        }
    }

    private static void writeIntent(DataOutputStream out, PhysicalIntent intent) throws IOException {
        BinaryCodec.writeUuid(out, intent.id().value());
        out.writeInt(intent.type().ordinal());
        BinaryCodec.writeString(out, intent.authority());
        BinaryCodec.writeUuid(out, intent.sourceEntityId());
        writeOptionalUuid(out, intent.settlementId().map(SettlementId::value).orElse(null));
        writeOptionalUuid(out, intent.kingdomId().map(KingdomId::value).orElse(null));
        writeOptionalUuid(out, intent.structureId().map(StructureId::value).orElse(null));
        out.writeInt(intent.buildingRole().map(Enum::ordinal).orElse(-1));
        out.writeInt(intent.targetPosition().x());
        out.writeInt(intent.targetPosition().z());
        out.writeInt(intent.footprint().minX());
        out.writeInt(intent.footprint().minZ());
        out.writeInt(intent.footprint().maxX());
        out.writeInt(intent.footprint().maxZ());
        out.writeInt(intent.revision());
        out.writeInt(intent.status().ordinal());
        out.writeLong(intent.createdAt().absoluteTicks());
        out.writeInt(intent.priority());
        out.writeInt(intent.retryCount());
        BinaryCodec.writeString(out, intent.failureReason() == null ? "" : intent.failureReason());
        BinaryCodec.writeString(out, intent.cultureKey());
        out.writeLong(intent.lastUpdateTicks());

        out.writeInt(intent.dependencies().size());
        for (PhysicalIntentId dep : intent.dependencies()) {
            BinaryCodec.writeUuid(out, dep.value());
        }

        out.writeInt(intent.provenance().size());
        for (Map.Entry<String, String> e : intent.provenance().entrySet()) {
            BinaryCodec.writeString(out, e.getKey());
            BinaryCodec.writeString(out, e.getValue());
        }

        out.writeInt(intent.reservedCosts().size());
        for (Map.Entry<ResourceType, Double> e : intent.reservedCosts().entrySet()) {
            out.writeInt(e.getKey().ordinal());
            out.writeDouble(e.getValue());
        }

        out.writeInt(intent.pendingChunkKeys().size());
        for (long key : intent.pendingChunkKeys()) {
            out.writeLong(key);
        }
        out.writeInt(intent.appliedChunkKeys().size());
        for (long key : intent.appliedChunkKeys()) {
            out.writeLong(key);
        }
    }

    private static PhysicalIntent readIntent(DataInputStream in) throws IOException {
        PhysicalIntentId id = PhysicalIntentId.of(BinaryCodec.readUuid(in));
        PhysicalIntentType type = readEnum(in, PhysicalIntentType.class);
        String authority = BinaryCodec.readString(in);
        UUID source = BinaryCodec.readUuid(in);
        Optional<SettlementId> settlement = optionalSettlement(in);
        Optional<KingdomId> kingdom = optionalKingdom(in);
        Optional<StructureId> structure = optionalStructure(in);
        int roleOrd = in.readInt();
        Optional<BuildingRole> role = roleOrd < 0 ? Optional.empty()
                : Optional.of(BuildingRole.values()[Math.min(roleOrd, BuildingRole.values().length - 1)]);
        BlockPos2 target = BlockPos2.of(in.readInt(), in.readInt());
        BoundingBox2 footprint = BoundingBox2.of(in.readInt(), in.readInt(), in.readInt(), in.readInt());
        int revision = in.readInt();
        PhysicalIntentStatus status = readEnum(in, PhysicalIntentStatus.class);
        SimulationTime created = SimulationTime.ofTicks(in.readLong());
        int priority = in.readInt();
        int retryCount = in.readInt();
        String failure = BinaryCodec.readString(in);
        String culture = BinaryCodec.readString(in);
        long lastUpdate = in.readLong();

        PhysicalIntent intent = new PhysicalIntent(
                id, type, authority, source, settlement, kingdom, structure, role,
                target, footprint, revision, PhysicalIntentStatus.PLANNED, created, priority,
                new LinkedHashMap<>(), new EnumMap<>(ResourceType.class), culture
        );

        int depCount = CanonicalSaveFormat.readCountPublic(in, PhysicalIntent.MAX_DEPENDENCIES, "intentDeps");
        for (int i = 0; i < depCount; i++) {
            intent.addDependency(PhysicalIntentId.of(BinaryCodec.readUuid(in)));
        }
        int provCount = CanonicalSaveFormat.readCountPublic(in, 64, "intentProvenance");
        for (int i = 0; i < provCount; i++) {
            intent.provenance().put(BinaryCodec.readString(in), BinaryCodec.readString(in));
        }
        int costCount = CanonicalSaveFormat.readCountPublic(in, ResourceType.values().length, "intentCosts");
        for (int i = 0; i < costCount; i++) {
            ResourceType rt = readEnum(in, ResourceType.class);
            intent.reservedCosts().put(rt, in.readDouble());
        }
        int pending = CanonicalSaveFormat.readCountPublic(in, PhysicalIntent.MAX_CHUNK_SLICES, "pendingChunks");
        for (int i = 0; i < pending; i++) {
            long key = in.readLong();
            intent.markChunkPending(PhysicalIntent.chunkX(key), PhysicalIntent.chunkZ(key));
        }
        int applied = CanonicalSaveFormat.readCountPublic(in, PhysicalIntent.MAX_CHUNK_SLICES, "appliedChunks");
        for (int i = 0; i < applied; i++) {
            long key = in.readLong();
            intent.markChunkApplied(PhysicalIntent.chunkX(key), PhysicalIntent.chunkZ(key));
        }
        intent.restorePersistedState(status, retryCount, failure, lastUpdate);
        return intent;
    }

    private static void writeStructure(DataOutputStream out, DynamicStructureRecord rec) throws IOException {
        BinaryCodec.writeUuid(out, rec.structureId().value());
        BinaryCodec.writeUuid(out, rec.settlementId().value());
        out.writeInt(rec.role().ordinal());
        out.writeInt(rec.footprint().minX());
        out.writeInt(rec.footprint().minZ());
        out.writeInt(rec.footprint().maxX());
        out.writeInt(rec.footprint().maxZ());
        BinaryCodec.writeUuid(out, rec.sourceIntentId() == null ? null : rec.sourceIntentId().value());
        out.writeInt(rec.status().ordinal());
        out.writeDouble(rec.integrity());
        out.writeInt(rec.residentialSlots());
        out.writeInt(rec.workSlots());
        out.writeInt(rec.physicalRevision());
        BinaryCodec.writeString(out, rec.cultureKey());
        out.writeInt(rec.foundationY());
    }

    private static DynamicStructureRecord readStructure(DataInputStream in) throws IOException {
        StructureId structureId = StructureId.of(BinaryCodec.readUuid(in));
        SettlementId settlementId = SettlementId.of(BinaryCodec.readUuid(in));
        BuildingRole role = readEnum(in, BuildingRole.class);
        BoundingBox2 fp = BoundingBox2.of(in.readInt(), in.readInt(), in.readInt(), in.readInt());
        UUID intentUuid = BinaryCodec.readUuid(in);
        PhysicalIntentId intentId = intentUuid == null
                ? PhysicalIntentId.deterministic(0, 0)
                : PhysicalIntentId.of(intentUuid);
        StructureIntegrityStatus status = readEnum(in, StructureIntegrityStatus.class);
        double integrity = in.readDouble();
        int residential = in.readInt();
        int work = in.readInt();
        int revision = in.readInt();
        String culture = BinaryCodec.readString(in);
        int foundationY = in.readInt();
        return new DynamicStructureRecord(
                structureId, settlementId, role, fp, intentId, status, integrity,
                residential, work, revision, culture, foundationY
        );
    }

    private static void writeGeometry(DataOutputStream out, DynamicSettlementGeometry geo) throws IOException {
        BinaryCodec.writeUuid(out, geo.settlementId().value());
        out.writeInt(geo.center().x());
        out.writeInt(geo.center().z());
        out.writeInt(geo.boundary().minX());
        out.writeInt(geo.boundary().minZ());
        out.writeInt(geo.boundary().maxX());
        out.writeInt(geo.boundary().maxZ());
        out.writeBoolean(geo.playerFounded());
        out.writeInt(geo.expansionGeneration());
        out.writeInt(geo.expansionAnchors().size());
        for (BlockPos2 p : geo.expansionAnchors()) {
            out.writeInt(p.x());
            out.writeInt(p.z());
        }
        out.writeInt(geo.roadAnchors().size());
        for (BlockPos2 p : geo.roadAnchors()) {
            out.writeInt(p.x());
            out.writeInt(p.z());
        }
    }

    private static DynamicSettlementGeometry readGeometry(DataInputStream in) throws IOException {
        SettlementId sid = SettlementId.of(BinaryCodec.readUuid(in));
        BlockPos2 center = BlockPos2.of(in.readInt(), in.readInt());
        BoundingBox2 boundary = BoundingBox2.of(in.readInt(), in.readInt(), in.readInt(), in.readInt());
        boolean playerFounded = in.readBoolean();
        int generation = in.readInt();
        DynamicSettlementGeometry geo = new DynamicSettlementGeometry(sid, center, boundary, playerFounded);
        for (int i = 0; i < generation; i++) {
            geo.bumpExpansionGeneration();
        }
        int anchors = CanonicalSaveFormat.readCountPublic(in, 256, "expansionAnchors");
        for (int i = 0; i < anchors; i++) {
            geo.expansionAnchors().add(BlockPos2.of(in.readInt(), in.readInt()));
        }
        int roads = CanonicalSaveFormat.readCountPublic(in, 256, "roadAnchors");
        for (int i = 0; i < roads; i++) {
            geo.roadAnchors().add(BlockPos2.of(in.readInt(), in.readInt()));
        }
        return geo;
    }

    private static void writeOptionalUuid(DataOutputStream out, UUID uuid) throws IOException {
        BinaryCodec.writeUuid(out, uuid);
    }

    private static Optional<SettlementId> optionalSettlement(DataInputStream in) throws IOException {
        UUID u = BinaryCodec.readUuid(in);
        return u == null ? Optional.empty() : Optional.of(SettlementId.of(u));
    }

    private static Optional<KingdomId> optionalKingdom(DataInputStream in) throws IOException {
        UUID u = BinaryCodec.readUuid(in);
        return u == null ? Optional.empty() : Optional.of(KingdomId.of(u));
    }

    private static Optional<StructureId> optionalStructure(DataInputStream in) throws IOException {
        UUID u = BinaryCodec.readUuid(in);
        return u == null ? Optional.empty() : Optional.of(StructureId.of(u));
    }

    private static <E extends Enum<E>> E readEnum(DataInputStream in, Class<E> type) throws IOException {
        int ordinal = in.readInt();
        E[] values = type.getEnumConstants();
        if (ordinal < 0 || ordinal >= values.length) {
            throw new IOException("invalid " + type.getSimpleName() + " ordinal: " + ordinal);
        }
        return values[ordinal];
    }
}
