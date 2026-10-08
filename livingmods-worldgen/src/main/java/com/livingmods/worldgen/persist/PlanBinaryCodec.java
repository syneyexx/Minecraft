package com.livingmods.worldgen.persist;

import com.livingmods.common.geo.BlockPos2;
import com.livingmods.common.geo.BoundingBox2;
import com.livingmods.common.id.CultureId;
import com.livingmods.common.id.DistrictId;
import com.livingmods.common.id.KingdomId;
import com.livingmods.common.id.LotId;
import com.livingmods.common.id.RoadId;
import com.livingmods.common.id.SettlementId;
import com.livingmods.common.id.StructureId;
import com.livingmods.common.model.BuildingRole;
import com.livingmods.common.model.DistrictType;
import com.livingmods.common.model.GovernmentType;
import com.livingmods.common.model.ResourceType;
import com.livingmods.common.model.RoadClass;
import com.livingmods.common.model.SettlementRole;
import com.livingmods.common.model.SettlementTier;
import com.livingmods.common.model.WealthClass;
import com.livingmods.worldgen.plan.PlannedBanditCamp;
import com.livingmods.worldgen.plan.PlannedBridge;
import com.livingmods.worldgen.plan.PlannedBuilding;
import com.livingmods.worldgen.plan.PlannedDistrict;
import com.livingmods.worldgen.plan.PlannedKingdom;
import com.livingmods.worldgen.plan.PlannedLot;
import com.livingmods.worldgen.plan.PlannedResourceSite;
import com.livingmods.worldgen.plan.PlannedRoad;
import com.livingmods.worldgen.plan.PlannedRuin;
import com.livingmods.worldgen.plan.PlannedSettlement;
import com.livingmods.worldgen.plan.WorldPlan;
import com.livingmods.worldgen.territory.TerritoryMap;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Compact binary codec for immutable world-plan planning data. */
final class PlanBinaryCodec {
    private PlanBinaryCodec() {}

    static void writePlan(DataOutput out, WorldPlan plan) throws IOException {
        out.writeLong(plan.seed());
        out.writeLong(plan.contentHash());
        writeKingdoms(out, plan.kingdoms());
        writeSettlements(out, new ArrayList<>(plan.settlements().values()));
        writeRoads(out, plan.roads());
        writeRuins(out, plan.ruins());
        writeResources(out, plan.resourceSites());
        writeCamps(out, plan.banditCamps());
        writeTerritory(out, plan.territories());
    }

    static WorldPlan readPlan(DataInput in, int worldgenVersion) throws IOException {
        long seed = in.readLong();
        long hash = in.readLong();
        List<PlannedKingdom> kingdoms = readKingdoms(in);
        List<PlannedSettlement> settlements = readSettlements(in, worldgenVersion);
        List<PlannedRoad> roads = readRoads(in);
        List<PlannedRuin> ruins = readRuins(in);
        List<PlannedResourceSite> resources = readResources(in);
        List<PlannedBanditCamp> camps = readCamps(in);
        TerritoryMap territories = readTerritory(in);
        return new WorldPlan(seed, kingdoms, settlements, roads, ruins, resources, camps, territories, hash);
    }

    private static void writeKingdoms(DataOutput out, List<PlannedKingdom> kingdoms) throws IOException {
        out.writeInt(kingdoms.size());
        for (PlannedKingdom k : kingdoms) {
            writeUuid(out, k.id().value());
            writeString(out, k.name());
            writeUuid(out, k.cultureId().value());
            writeString(out, k.cultureKey());
            writeEnum(out, k.governmentType());
            writeUuid(out, k.capitalId().value());
            writePos(out, k.capitalCenter());
            out.writeInt(k.settlementIds().size());
            for (SettlementId id : k.settlementIds()) writeUuid(out, id.value());
            writePosList(out, k.territoryPolygon());
            out.writeInt(k.adjacentKingdomIds().size());
            for (KingdomId id : k.adjacentKingdomIds()) writeUuid(out, id.value());
            out.writeBoolean(k.underground());
            writeString(out, k.religionKey());
        }
    }

    private static List<PlannedKingdom> readKingdoms(DataInput in) throws IOException {
        int n = in.readInt();
        List<PlannedKingdom> list = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            KingdomId id = KingdomId.of(readUuid(in));
            String name = readString(in);
            CultureId cultureId = CultureId.of(readUuid(in));
            String cultureKey = readString(in);
            GovernmentType gov = readEnum(in, GovernmentType.class);
            SettlementId capitalId = SettlementId.of(readUuid(in));
            BlockPos2 capital = readPos(in);
            int sc = in.readInt();
            List<SettlementId> settlements = new ArrayList<>(sc);
            for (int j = 0; j < sc; j++) settlements.add(SettlementId.of(readUuid(in)));
            List<BlockPos2> poly = readPosList(in);
            int ac = in.readInt();
            List<KingdomId> adjacent = new ArrayList<>(ac);
            for (int j = 0; j < ac; j++) adjacent.add(KingdomId.of(readUuid(in)));
            boolean underground = in.readBoolean();
            String religion = readString(in);
            list.add(new PlannedKingdom(
                    id, name, cultureId, cultureKey, gov, capitalId, capital,
                    settlements, poly, adjacent, underground, religion
            ));
        }
        return list;
    }

    private static void writeSettlements(DataOutput out, List<PlannedSettlement> settlements) throws IOException {
        out.writeInt(settlements.size());
        for (PlannedSettlement s : settlements) {
            writeUuid(out, s.id().value());
            writeString(out, s.name());
            writeEnum(out, s.tier());
            writeEnum(out, s.role());
            writePos(out, s.center());
            writeBox(out, s.bounds());
            out.writeBoolean(s.ownerKingdom().isPresent());
            if (s.ownerKingdom().isPresent()) writeUuid(out, s.ownerKingdom().get().value());
            writeUuid(out, s.cultureId().value());
            writeString(out, s.cultureKey());
            out.writeBoolean(s.capital());
            out.writeBoolean(s.walls());
            out.writeBoolean(s.underground());
            out.writeInt(s.plannedPopulation());
            writeDistricts(out, s.districts());
            writeLots(out, s.lots());
            writeBuildings(out, s.buildings());
            writePosList(out, s.streetNetwork());
            writePosList(out, s.wallPath());
            writePosList(out, s.gatePositions());
        }
    }

    private static List<PlannedSettlement> readSettlements(DataInput in, int worldgenVersion) throws IOException {
        int n = in.readInt();
        List<PlannedSettlement> list = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            SettlementId id = SettlementId.of(readUuid(in));
            String name = readString(in);
            SettlementTier tier = readEnum(in, SettlementTier.class);
            SettlementRole role = readEnum(in, SettlementRole.class);
            BlockPos2 center = readPos(in);
            BoundingBox2 bounds = readBox(in);
            Optional<KingdomId> owner = in.readBoolean()
                    ? Optional.of(KingdomId.of(readUuid(in))) : Optional.empty();
            CultureId cultureId = CultureId.of(readUuid(in));
            String cultureKey = readString(in);
            boolean capital = in.readBoolean();
            boolean walls = in.readBoolean();
            boolean underground = in.readBoolean();
            int pop = in.readInt();
            List<PlannedDistrict> districts = readDistricts(in);
            List<PlannedLot> lots = readLots(in);
            List<PlannedBuilding> buildings = readBuildings(in, worldgenVersion);
            List<BlockPos2> streets = readPosList(in);
            List<BlockPos2> wall = readPosList(in);
            List<BlockPos2> gates = readPosList(in);
            list.add(new PlannedSettlement(
                    id, name, tier, role, center, bounds, owner, cultureId, cultureKey,
                    capital, walls, underground, pop, districts, lots, buildings, streets, wall, gates
            ));
        }
        return list;
    }

    private static void writeDistricts(DataOutput out, List<PlannedDistrict> districts) throws IOException {
        out.writeInt(districts.size());
        for (PlannedDistrict d : districts) {
            writeUuid(out, d.id().value());
            writeUuid(out, d.settlementId().value());
            writeEnum(out, d.type());
            writeBox(out, d.bounds());
        }
    }

    private static List<PlannedDistrict> readDistricts(DataInput in) throws IOException {
        int n = in.readInt();
        List<PlannedDistrict> list = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            list.add(new PlannedDistrict(
                    DistrictId.of(readUuid(in)),
                    SettlementId.of(readUuid(in)),
                    readEnum(in, DistrictType.class),
                    readBox(in)
            ));
        }
        return list;
    }

    private static void writeLots(DataOutput out, List<PlannedLot> lots) throws IOException {
        out.writeInt(lots.size());
        for (PlannedLot lot : lots) {
            writeUuid(out, lot.id().value());
            writeUuid(out, lot.settlementId().value());
            writeUuid(out, lot.districtId().value());
            writeEnum(out, lot.districtType());
            writeBox(out, lot.bounds());
            out.writeInt(lot.entranceDirection());
            out.writeDouble(lot.slope());
            out.writeInt(lot.allowedRoles().size());
            for (BuildingRole role : lot.allowedRoles()) writeEnum(out, role);
            out.writeBoolean(lot.occupied());
        }
    }

    private static List<PlannedLot> readLots(DataInput in) throws IOException {
        int n = in.readInt();
        List<PlannedLot> list = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            LotId id = LotId.of(readUuid(in));
            SettlementId sid = SettlementId.of(readUuid(in));
            DistrictId did = DistrictId.of(readUuid(in));
            DistrictType dtype = readEnum(in, DistrictType.class);
            BoundingBox2 box = readBox(in);
            int entrance = in.readInt();
            double slope = in.readDouble();
            int rc = in.readInt();
            EnumSet<BuildingRole> roles = EnumSet.noneOf(BuildingRole.class);
            for (int j = 0; j < rc; j++) roles.add(readEnum(in, BuildingRole.class));
            boolean occupied = in.readBoolean();
            list.add(new PlannedLot(id, sid, did, dtype, box, entrance, slope, roles, occupied));
        }
        return list;
    }

    private static void writeBuildings(DataOutput out, List<PlannedBuilding> buildings) throws IOException {
        out.writeInt(buildings.size());
        for (PlannedBuilding b : buildings) {
            writeUuid(out, b.id().value());
            writeUuid(out, b.lotId().value());
            writeUuid(out, b.settlementId().value());
            writeUuid(out, b.districtId().value());
            writeEnum(out, b.role());
            writeEnum(out, b.wealthClass());
            writeBox(out, b.footprint());
            out.writeInt(b.rotationY());
            out.writeInt(b.foundationY());
            writeString(out, b.cultureKey());
            writeString(out, b.paletteKey());
            out.writeInt(b.seed());
            out.writeInt(b.floorCount());
            out.writeBoolean(b.hasBasement());
            out.writeBoolean(b.hasAttic());
            writeStringList(out, b.roomTags());
            writeStringList(out, b.wallSegments());
            writeStringList(out, b.windowPositions());
            writeString(out, b.entranceFacing());
            out.writeInt(b.capacity());
            out.writeInt(b.workSlots());
            out.writeInt(b.residentialSlots());
            writeString(out, b.assetId() == null ? "" : b.assetId());
            writeString(out, b.archetype() == null ? "" : b.archetype());
        }
    }

    private static List<PlannedBuilding> readBuildings(DataInput in) throws IOException {
        return readBuildings(in, Integer.MAX_VALUE);
    }

    private static List<PlannedBuilding> readBuildings(DataInput in, int worldgenVersion) throws IOException {
        int n = in.readInt();
        List<PlannedBuilding> list = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            StructureId id = StructureId.of(readUuid(in));
            LotId lotId = LotId.of(readUuid(in));
            SettlementId settlementId = SettlementId.of(readUuid(in));
            DistrictId districtId = DistrictId.of(readUuid(in));
            BuildingRole role = readEnum(in, BuildingRole.class);
            WealthClass wealth = readEnum(in, WealthClass.class);
            BoundingBox2 box = readBox(in);
            int rotationY = in.readInt();
            int foundationY = in.readInt();
            String cultureKey = readString(in);
            String paletteKey = readString(in);
            int seed = in.readInt();
            int floors = in.readInt();
            boolean basement = in.readBoolean();
            boolean attic = in.readBoolean();
            List<String> rooms = readStringList(in);
            List<String> walls = readStringList(in);
            List<String> windows = readStringList(in);
            String entrance = readString(in);
            int capacity = in.readInt();
            int workSlots = in.readInt();
            int residentialSlots = in.readInt();
            String assetId = "";
            String archetype = "";
            if (worldgenVersion >= 3) {
                assetId = readString(in);
                archetype = readString(in);
            }
            list.add(new PlannedBuilding(
                    id, lotId, settlementId, districtId, role, wealth, box, rotationY, foundationY,
                    cultureKey, paletteKey, seed, floors, basement, attic, rooms, walls, windows,
                    entrance, capacity, workSlots, residentialSlots, assetId, archetype
            ));
        }
        return list;
    }

    private static void writeRoads(DataOutput out, List<PlannedRoad> roads) throws IOException {
        out.writeInt(roads.size());
        for (PlannedRoad r : roads) {
            writeUuid(out, r.id().value());
            writeEnum(out, r.roadClass());
            writePosList(out, r.path());
            out.writeBoolean(r.fromSettlement().isPresent());
            if (r.fromSettlement().isPresent()) writeUuid(out, r.fromSettlement().get().value());
            out.writeBoolean(r.toSettlement().isPresent());
            if (r.toSettlement().isPresent()) writeUuid(out, r.toSettlement().get().value());
            out.writeInt(r.bridges().size());
            for (PlannedBridge b : r.bridges()) {
                writePos(out, b.start());
                writePos(out, b.end());
                writeEnum(out, b.kind());
                writeString(out, b.cultureKey());
            }
            writeString(out, r.cultureKey());
        }
    }

    private static List<PlannedRoad> readRoads(DataInput in) throws IOException {
        int n = in.readInt();
        List<PlannedRoad> list = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            RoadId id = RoadId.of(readUuid(in));
            RoadClass cls = readEnum(in, RoadClass.class);
            List<BlockPos2> path = readPosList(in);
            Optional<SettlementId> from = in.readBoolean()
                    ? Optional.of(SettlementId.of(readUuid(in))) : Optional.empty();
            Optional<SettlementId> to = in.readBoolean()
                    ? Optional.of(SettlementId.of(readUuid(in))) : Optional.empty();
            int bc = in.readInt();
            List<PlannedBridge> bridges = new ArrayList<>(bc);
            for (int j = 0; j < bc; j++) {
                bridges.add(new PlannedBridge(
                        readPos(in), readPos(in),
                        readEnum(in, PlannedBridge.BridgeKind.class),
                        readString(in)
                ));
            }
            String culture = readString(in);
            list.add(new PlannedRoad(id, cls, path, from, to, bridges, culture));
        }
        return list;
    }

    private static void writeRuins(DataOutput out, List<PlannedRuin> ruins) throws IOException {
        out.writeInt(ruins.size());
        for (PlannedRuin r : ruins) {
            writeBox(out, r.bounds());
            writeEnum(out, r.originalRole());
            writeString(out, r.cultureKey());
            out.writeInt(r.decaySeed());
            writeString(out, r.historicalNote());
        }
    }

    private static List<PlannedRuin> readRuins(DataInput in) throws IOException {
        int n = in.readInt();
        List<PlannedRuin> list = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            list.add(new PlannedRuin(
                    readBox(in),
                    readEnum(in, BuildingRole.class),
                    readString(in),
                    in.readInt(),
                    readString(in)
            ));
        }
        return list;
    }

    private static void writeResources(DataOutput out, List<PlannedResourceSite> sites) throws IOException {
        out.writeInt(sites.size());
        for (PlannedResourceSite s : sites) {
            writePos(out, s.center());
            writeEnum(out, s.resource());
            out.writeDouble(s.richness());
            out.writeBoolean(s.claimedBy().isPresent());
            if (s.claimedBy().isPresent()) writeUuid(out, s.claimedBy().get().value());
        }
    }

    private static List<PlannedResourceSite> readResources(DataInput in) throws IOException {
        int n = in.readInt();
        List<PlannedResourceSite> list = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            BlockPos2 center = readPos(in);
            ResourceType type = readEnum(in, ResourceType.class);
            double richness = in.readDouble();
            Optional<KingdomId> claim = in.readBoolean()
                    ? Optional.of(KingdomId.of(readUuid(in))) : Optional.empty();
            list.add(new PlannedResourceSite(center, type, richness, claim));
        }
        return list;
    }

    private static void writeCamps(DataOutput out, List<PlannedBanditCamp> camps) throws IOException {
        out.writeInt(camps.size());
        for (PlannedBanditCamp c : camps) {
            writePos(out, c.center());
            out.writeInt(c.size());
            writeString(out, c.reason());
            writeEnum(out, c.variant());
        }
    }

    private static List<PlannedBanditCamp> readCamps(DataInput in) throws IOException {
        int n = in.readInt();
        List<PlannedBanditCamp> list = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            list.add(new PlannedBanditCamp(
                    readPos(in),
                    in.readInt(),
                    readString(in),
                    readEnum(in, PlannedBanditCamp.CampVariant.class)
            ));
        }
        return list;
    }

    private static void writeTerritory(DataOutput out, TerritoryMap map) throws IOException {
        out.writeInt(map.cellSize());
        out.writeInt(map.originX());
        out.writeInt(map.originZ());
        out.writeInt(map.widthCells());
        out.writeInt(map.heightCells());
        out.writeInt(map.kingdomIndex().size());
        for (KingdomId id : map.kingdomIndex()) writeUuid(out, id.value());
        short[] owners = map.ownerOrdinalRaw();
        out.writeInt(owners.length);
        for (short s : owners) out.writeShort(s);
        byte[] zones = map.zoneOrdinalRaw();
        out.writeInt(zones.length);
        out.write(zones);
        out.writeInt(map.adjacency().size());
        for (Map.Entry<KingdomId, List<KingdomId>> e : map.adjacency().entrySet()) {
            writeUuid(out, e.getKey().value());
            out.writeInt(e.getValue().size());
            for (KingdomId id : e.getValue()) writeUuid(out, id.value());
        }
    }

    private static TerritoryMap readTerritory(DataInput in) throws IOException {
        int cellSize = in.readInt();
        int originX = in.readInt();
        int originZ = in.readInt();
        int width = in.readInt();
        int height = in.readInt();
        int kc = in.readInt();
        List<KingdomId> index = new ArrayList<>(kc);
        for (int i = 0; i < kc; i++) index.add(KingdomId.of(readUuid(in)));
        int oc = in.readInt();
        short[] owners = new short[oc];
        for (int i = 0; i < oc; i++) owners[i] = in.readShort();
        int zc = in.readInt();
        byte[] zones = new byte[zc];
        in.readFully(zones);
        int ac = in.readInt();
        Map<KingdomId, List<KingdomId>> adjacency = new LinkedHashMap<>();
        for (int i = 0; i < ac; i++) {
            KingdomId key = KingdomId.of(readUuid(in));
            int n = in.readInt();
            List<KingdomId> vals = new ArrayList<>(n);
            for (int j = 0; j < n; j++) vals.add(KingdomId.of(readUuid(in)));
            adjacency.put(key, vals);
        }
        return new TerritoryMap(cellSize, originX, originZ, width, height, index, owners, zones, adjacency);
    }

    private static void writePos(DataOutput out, BlockPos2 p) throws IOException {
        out.writeInt(p.x());
        out.writeInt(p.z());
    }

    private static BlockPos2 readPos(DataInput in) throws IOException {
        return BlockPos2.of(in.readInt(), in.readInt());
    }

    private static void writePosList(DataOutput out, List<BlockPos2> list) throws IOException {
        out.writeInt(list.size());
        for (BlockPos2 p : list) writePos(out, p);
    }

    private static List<BlockPos2> readPosList(DataInput in) throws IOException {
        int n = in.readInt();
        List<BlockPos2> list = new ArrayList<>(n);
        for (int i = 0; i < n; i++) list.add(readPos(in));
        return list;
    }

    private static void writeBox(DataOutput out, BoundingBox2 b) throws IOException {
        out.writeInt(b.minX());
        out.writeInt(b.minZ());
        out.writeInt(b.maxX());
        out.writeInt(b.maxZ());
    }

    private static BoundingBox2 readBox(DataInput in) throws IOException {
        return BoundingBox2.of(in.readInt(), in.readInt(), in.readInt(), in.readInt());
    }

    private static void writeUuid(DataOutput out, UUID uuid) throws IOException {
        out.writeLong(uuid.getMostSignificantBits());
        out.writeLong(uuid.getLeastSignificantBits());
    }

    private static UUID readUuid(DataInput in) throws IOException {
        return new UUID(in.readLong(), in.readLong());
    }

    private static void writeString(DataOutput out, String s) throws IOException {
        byte[] bytes = (s == null ? "" : s).getBytes(StandardCharsets.UTF_8);
        out.writeInt(bytes.length);
        out.write(bytes);
    }

    private static String readString(DataInput in) throws IOException {
        int n = in.readInt();
        byte[] bytes = new byte[n];
        in.readFully(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static void writeStringList(DataOutput out, List<String> list) throws IOException {
        out.writeInt(list.size());
        for (String s : list) writeString(out, s);
    }

    private static List<String> readStringList(DataInput in) throws IOException {
        int n = in.readInt();
        List<String> list = new ArrayList<>(n);
        for (int i = 0; i < n; i++) list.add(readString(in));
        return list;
    }

    private static <E extends Enum<E>> void writeEnum(DataOutput out, E value) throws IOException {
        writeString(out, value.name());
    }

    private static <E extends Enum<E>> E readEnum(DataInput in, Class<E> type) throws IOException {
        return Enum.valueOf(type, readString(in));
    }
}
