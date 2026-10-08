package com.livingmods.worldgen.structure;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MineLife Structure (MLS1) — deterministic local block-palette format.
 * No Minecraft dependency; NeoForge maps palette strings to BlockState.
 * Air is omitted. Block entities / entities are never stored.
 */
public final class MlsStructureFormat {
    public static final int MAGIC = 0x4D4C5331; // MLS1
    public static final int MAX_DIM = 256;
    public static final int MAX_BLOCKS = 250_000;
    public static final int MAX_PALETTE = 4096;

    public record BlockPlacement(int x, int y, int z, int paletteIndex) {}

    public record StructureContent(
            int width,
            int height,
            int depth,
            int entranceX,
            int entranceY,
            int entranceZ,
            int entranceFacing, // 0N 1E 2S 3W
            FoundationMode foundationMode,
            List<String> palette,
            List<BlockPlacement> blocks
    ) {
        public StructureContent {
            palette = List.copyOf(palette);
            blocks = List.copyOf(blocks);
        }

        /** Pre-index blocks by chunk-local key for slice materialization. */
        public Map<Long, List<BlockPlacement>> chunkIndex() {
            Map<Long, List<BlockPlacement>> map = new LinkedHashMap<>();
            for (BlockPlacement b : blocks) {
                int cx = b.x() >> 4;
                int cz = b.z() >> 4;
                long key = ((long) cx << 32) ^ (cz & 0xffffffffL);
                map.computeIfAbsent(key, k -> new ArrayList<>()).add(b);
            }
            return map;
        }
    }

    private MlsStructureFormat() {}

    public static byte[] write(StructureContent content) throws IOException {
        if (content.width() < 1 || content.height() < 1 || content.depth() < 1
                || content.width() > MAX_DIM || content.height() > MAX_DIM || content.depth() > MAX_DIM) {
            throw new IOException("invalid dimensions");
        }
        if (content.palette().size() > MAX_PALETTE) {
            throw new IOException("palette too large");
        }
        if (content.blocks().size() > MAX_BLOCKS) {
            throw new IOException("too many blocks");
        }
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bos);
        out.writeInt(MAGIC);
        out.writeShort(content.width());
        out.writeShort(content.height());
        out.writeShort(content.depth());
        out.writeShort(content.entranceX());
        out.writeShort(content.entranceY());
        out.writeShort(content.entranceZ());
        out.writeByte(content.entranceFacing() & 0xff);
        out.writeByte(content.foundationMode().ordinal() & 0xff);
        out.writeShort(content.palette().size());
        for (String p : content.palette()) {
            writeUtf(out, p == null ? "minecraft:air" : p);
        }
        out.writeInt(content.blocks().size());
        for (BlockPlacement b : content.blocks()) {
            out.writeShort(b.x());
            out.writeShort(b.y());
            out.writeShort(b.z());
            out.writeShort(b.paletteIndex());
        }
        out.flush();
        return bos.toByteArray();
    }

    public static StructureContent read(byte[] bytes) throws IOException {
        if (bytes == null || bytes.length < 20) {
            throw new IOException("truncated MLS");
        }
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes));
        int magic = in.readInt();
        if (magic != MAGIC) {
            throw new IOException("bad MLS magic: " + Integer.toHexString(magic));
        }
        int width = in.readUnsignedShort();
        int height = in.readUnsignedShort();
        int depth = in.readUnsignedShort();
        if (width < 1 || height < 1 || depth < 1 || width > MAX_DIM || height > MAX_DIM || depth > MAX_DIM) {
            throw new IOException("invalid MLS dimensions");
        }
        int entranceX = in.readUnsignedShort();
        int entranceY = in.readUnsignedShort();
        int entranceZ = in.readUnsignedShort();
        int facing = in.readUnsignedByte();
        int foundationOrd = in.readUnsignedByte();
        FoundationMode foundation = foundationOrd >= 0 && foundationOrd < FoundationMode.values().length
                ? FoundationMode.values()[foundationOrd]
                : FoundationMode.CUT_AND_FILL;
        int paletteSize = in.readUnsignedShort();
        if (paletteSize > MAX_PALETTE) {
            throw new IOException("palette too large");
        }
        List<String> palette = new ArrayList<>(paletteSize);
        for (int i = 0; i < paletteSize; i++) {
            palette.add(readUtf(in));
        }
        int blockCount = in.readInt();
        if (blockCount < 0 || blockCount > MAX_BLOCKS) {
            throw new IOException("invalid block count: " + blockCount);
        }
        List<BlockPlacement> blocks = new ArrayList<>(blockCount);
        for (int i = 0; i < blockCount; i++) {
            int x = in.readUnsignedShort();
            int y = in.readUnsignedShort();
            int z = in.readUnsignedShort();
            int pi = in.readUnsignedShort();
            if (x >= width || y >= height || z >= depth || pi >= paletteSize) {
                throw new IOException("block out of bounds at index " + i);
            }
            blocks.add(new BlockPlacement(x, y, z, pi));
        }
        return new StructureContent(width, height, depth, entranceX, entranceY, entranceZ,
                facing, foundation, palette, blocks);
    }

    public static String contentHash(byte[] bytes) {
        // FNV-1a 64 as hex
        long h = 0xcbf29ce484222325L;
        for (byte b : bytes) {
            h ^= (b & 0xff);
            h *= 0x100000001b3L;
        }
        return Long.toHexString(h);
    }

    public static String facingName(int facing) {
        return switch (facing & 3) {
            case 0 -> "north";
            case 1 -> "east";
            case 2 -> "south";
            default -> "west";
        };
    }

    public static int facingIndex(String name) {
        if (name == null) return 2;
        return switch (name.toLowerCase()) {
            case "north" -> 0;
            case "east" -> 1;
            case "south" -> 2;
            case "west" -> 3;
            default -> 2;
        };
    }

    /** Rotate placement around Y within the structure bounding box. rotations = 0..3 (90° steps). */
    public static BlockPlacement rotate(BlockPlacement b, int width, int depth, int rotations) {
        int r = ((rotations % 4) + 4) % 4;
        int x = b.x();
        int z = b.z();
        int nx = x;
        int nz = z;
        int nw = width;
        int nd = depth;
        for (int i = 0; i < r; i++) {
            int ox = nx;
            int oz = nz;
            nx = nd - 1 - oz;
            nz = ox;
            int tmp = nw;
            nw = nd;
            nd = tmp;
        }
        return new BlockPlacement(nx, b.y(), nz, b.paletteIndex());
    }

    public static int[] rotatedDimensions(int width, int depth, int rotations) {
        return (rotations & 1) == 1 ? new int[]{depth, width} : new int[]{width, depth};
    }

    public static String rotateFacing(String facing, int rotations) {
        int idx = facingIndex(facing);
        return facingName((idx + rotations) & 3);
    }

    public static String rotateBlockState(String state, int rotations) {
        if (state == null || rotations == 0) return state;
        int r = ((rotations % 4) + 4) % 4;
        if (r == 0) return state;
        String out = state;
        for (int i = 0; i < r; i++) {
            out = rotateBlockStateOnce(out);
        }
        return out;
    }

    private static String rotateBlockStateOnce(String state) {
        String s = state;
        s = s.replace("facing=north", "facing=__TMP_E__");
        s = s.replace("facing=east", "facing=__TMP_S__");
        s = s.replace("facing=south", "facing=__TMP_W__");
        s = s.replace("facing=west", "facing=__TMP_N__");
        s = s.replace("facing=__TMP_E__", "facing=east");
        s = s.replace("facing=__TMP_S__", "facing=south");
        s = s.replace("facing=__TMP_W__", "facing=west");
        s = s.replace("facing=__TMP_N__", "facing=north");
        s = s.replace("axis=x", "axis=__TMP_Z__");
        s = s.replace("axis=z", "axis=__TMP_X__");
        s = s.replace("axis=__TMP_Z__", "axis=z");
        s = s.replace("axis=__TMP_X__", "axis=x");
        return s;
    }

    private static void writeUtf(DataOutputStream out, String s) throws IOException {
        byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > 1024) {
            bytes = Arrays.copyOf(bytes, 1024);
        }
        out.writeShort(bytes.length);
        out.write(bytes);
    }

    private static String readUtf(DataInputStream in) throws IOException {
        int len = in.readUnsignedShort();
        if (len > 1024) throw new IOException("utf too long");
        byte[] bytes = in.readNBytes(len);
        if (bytes.length != len) throw new IOException("truncated utf");
        return new String(bytes, StandardCharsets.UTF_8);
    }
}
