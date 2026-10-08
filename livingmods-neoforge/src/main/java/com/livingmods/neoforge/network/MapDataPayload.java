package com.livingmods.neoforge.network;

import com.livingmods.neoforge.LivingModsMod;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;

/**
 * Server → client map snapshot. Clients must not read WorldPlanCache unsafely for UI.
 */
public record MapDataPayload(
        int originX,
        int originZ,
        int tileSize,
        int width,
        int height,
        byte[] terrainTiles,
        List<SettlementMarker> settlements,
        List<RoadSegment> roads,
        List<KingdomOverlay> kingdoms,
        List<ArmyMarker> armies,
        List<EpidemicMarker> epidemics,
        List<MigrationMarker> migrations,
        int playerX,
        int playerZ,
        long revision
) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<MapDataPayload> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(LivingModsMod.MOD_ID, "map_data"));

    public static final StreamCodec<RegistryFriendlyByteBuf, MapDataPayload> STREAM_CODEC =
            StreamCodec.of(MapDataPayload::encode, MapDataPayload::decode);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    /**
     * @param knowledge UNKNOWN/RUMORED/KNOWN/OBSERVED — server-filtered; client must not invent markers.
     */
    public record SettlementMarker(String id, String name, String kingdom, String tier, int x, int z, boolean capital, String knowledge) {
        public SettlementMarker(String id, String name, String kingdom, String tier, int x, int z, boolean capital) {
            this(id, name, kingdom, tier, x, z, capital, "KNOWN");
        }
    }
    public record RoadSegment(int x1, int z1, int x2, int z2, String roadClass) {}
    public record KingdomOverlay(String id, String name, int color, int capitalX, int capitalZ, List<Integer> borderXZ, String knowledge) {
        public KingdomOverlay(String id, String name, int color, int capitalX, int capitalZ, List<Integer> borderXZ) {
            this(id, name, color, capitalX, capitalZ, borderXZ, "KNOWN");
        }
    }
    public record ArmyMarker(String id, String kingdom, int x, int z, int strength) {}
    public record EpidemicMarker(String pathogen, int x, int z, double severity) {}
    public record MigrationMarker(String reason, int x, int z, int population) {}

    private static void encode(RegistryFriendlyByteBuf buf, MapDataPayload payload) {
        buf.writeVarInt(payload.originX);
        buf.writeVarInt(payload.originZ);
        buf.writeVarInt(payload.tileSize);
        buf.writeVarInt(payload.width);
        buf.writeVarInt(payload.height);
        ByteBufCodecs.BYTE_ARRAY.encode(buf, payload.terrainTiles);
        buf.writeVarInt(payload.settlements.size());
        for (SettlementMarker s : payload.settlements) {
            buf.writeUtf(s.id);
            buf.writeUtf(s.name);
            buf.writeUtf(s.kingdom);
            buf.writeUtf(s.tier);
            buf.writeVarInt(s.x);
            buf.writeVarInt(s.z);
            buf.writeBoolean(s.capital);
            buf.writeUtf(s.knowledge == null ? "KNOWN" : s.knowledge, 16);
        }
        buf.writeVarInt(payload.roads.size());
        for (RoadSegment r : payload.roads) {
            buf.writeVarInt(r.x1);
            buf.writeVarInt(r.z1);
            buf.writeVarInt(r.x2);
            buf.writeVarInt(r.z2);
            buf.writeUtf(r.roadClass);
        }
        buf.writeVarInt(payload.kingdoms.size());
        for (KingdomOverlay k : payload.kingdoms) {
            buf.writeUtf(k.id);
            buf.writeUtf(k.name);
            buf.writeInt(k.color);
            buf.writeVarInt(k.capitalX);
            buf.writeVarInt(k.capitalZ);
            buf.writeVarInt(k.borderXZ.size());
            for (int v : k.borderXZ) {
                buf.writeVarInt(v);
            }
            buf.writeUtf(k.knowledge == null ? "KNOWN" : k.knowledge, 16);
        }
        buf.writeVarInt(payload.armies.size());
        for (ArmyMarker a : payload.armies) {
            buf.writeUtf(a.id);
            buf.writeUtf(a.kingdom);
            buf.writeVarInt(a.x);
            buf.writeVarInt(a.z);
            buf.writeVarInt(a.strength);
        }
        buf.writeVarInt(payload.epidemics.size());
        for (EpidemicMarker e : payload.epidemics) {
            buf.writeUtf(e.pathogen);
            buf.writeVarInt(e.x);
            buf.writeVarInt(e.z);
            buf.writeDouble(e.severity);
        }
        buf.writeVarInt(payload.migrations.size());
        for (MigrationMarker m : payload.migrations) {
            buf.writeUtf(m.reason);
            buf.writeVarInt(m.x);
            buf.writeVarInt(m.z);
            buf.writeVarInt(m.population);
        }
        buf.writeVarInt(payload.playerX);
        buf.writeVarInt(payload.playerZ);
        buf.writeLong(payload.revision);
    }

    public static final int MAX_MARKERS = 512;
    public static final int MAX_BORDER_POINTS = 2048;
    public static final int MAX_TILE_DIM = 256;

    private static MapDataPayload decode(RegistryFriendlyByteBuf buf) {
        int originX = buf.readVarInt();
        int originZ = buf.readVarInt();
        int tileSize = buf.readVarInt();
        int width = Math.min(MAX_TILE_DIM, Math.max(0, buf.readVarInt()));
        int height = Math.min(MAX_TILE_DIM, Math.max(0, buf.readVarInt()));
        byte[] tiles = ByteBufCodecs.BYTE_ARRAY.decode(buf);
        int sc = boundCount(buf.readVarInt(), MAX_MARKERS);
        List<SettlementMarker> settlements = new ArrayList<>(sc);
        for (int i = 0; i < sc; i++) {
            settlements.add(new SettlementMarker(
                    buf.readUtf(256), buf.readUtf(256), buf.readUtf(256), buf.readUtf(64),
                    buf.readVarInt(), buf.readVarInt(), buf.readBoolean(), buf.readUtf(16)));
        }
        int rc = boundCount(buf.readVarInt(), MAX_MARKERS);
        List<RoadSegment> roads = new ArrayList<>(rc);
        for (int i = 0; i < rc; i++) {
            roads.add(new RoadSegment(buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readUtf(64)));
        }
        int kc = boundCount(buf.readVarInt(), MAX_MARKERS);
        List<KingdomOverlay> kingdoms = new ArrayList<>(kc);
        for (int i = 0; i < kc; i++) {
            String id = buf.readUtf(256);
            String name = buf.readUtf(256);
            int color = buf.readInt();
            int cx = buf.readVarInt();
            int cz = buf.readVarInt();
            int bc = boundCount(buf.readVarInt(), MAX_BORDER_POINTS);
            List<Integer> border = new ArrayList<>(bc);
            for (int j = 0; j < bc; j++) {
                border.add(buf.readVarInt());
            }
            kingdoms.add(new KingdomOverlay(id, name, color, cx, cz, border, buf.readUtf(16)));
        }
        int ac = boundCount(buf.readVarInt(), MAX_MARKERS);
        List<ArmyMarker> armies = new ArrayList<>(ac);
        for (int i = 0; i < ac; i++) {
            armies.add(new ArmyMarker(buf.readUtf(256), buf.readUtf(256), buf.readVarInt(), buf.readVarInt(), buf.readVarInt()));
        }
        int ec = boundCount(buf.readVarInt(), MAX_MARKERS);
        List<EpidemicMarker> epidemics = new ArrayList<>(ec);
        for (int i = 0; i < ec; i++) {
            epidemics.add(new EpidemicMarker(buf.readUtf(128), buf.readVarInt(), buf.readVarInt(), buf.readDouble()));
        }
        int mc = boundCount(buf.readVarInt(), MAX_MARKERS);
        List<MigrationMarker> migrations = new ArrayList<>(mc);
        for (int i = 0; i < mc; i++) {
            migrations.add(new MigrationMarker(buf.readUtf(128), buf.readVarInt(), buf.readVarInt(), buf.readVarInt()));
        }
        int playerX = buf.readVarInt();
        int playerZ = buf.readVarInt();
        long revision = buf.readLong();
        return new MapDataPayload(originX, originZ, tileSize, width, height, tiles,
                settlements, roads, kingdoms, armies, epidemics, migrations, playerX, playerZ, revision);
    }

    private static int boundCount(int n, int max) {
        if (n < 0 || n > max) {
            throw new IllegalArgumentException("map payload collection size out of bounds: " + n);
        }
        return n;
    }
}
