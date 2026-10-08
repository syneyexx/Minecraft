package com.livingmods.neoforge.worldgen;

import com.livingmods.common.geo.BoundingBox2;
import com.livingmods.worldgen.plan.PlannedBuilding;
import com.livingmods.worldgen.plan.PlannedSettlement;
import com.livingmods.worldgen.structure.FoundationMode;
import com.livingmods.worldgen.structure.MlsStructureFormat;
import com.livingmods.worldgen.structure.StructureAsset;
import com.livingmods.worldgen.structure.StructureCatalog;
import com.livingmods.worldgen.structure.StructureCatalogHolder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Chunk-sliced placement of imported/authored MLS structure assets.
 * Server thread only; SafeChunkWriter; no neighbor chunk force-load.
 * Uses rotated chunk indexes so large castles are not fully scanned per chunk.
 */
public final class StructureAssetMaterializer {
    private final StructureCatalog catalog;
    /** Bounded cache: assetId|rot → rotated chunk index. */
    private final ConcurrentHashMap<String, Map<Long, List<MlsStructureFormat.BlockPlacement>>> rotIndexCache =
            new ConcurrentHashMap<>();
    private static final int MAX_ROT_CACHE = 64;

    public StructureAssetMaterializer() {
        this(StructureCatalogHolder.ensureLoaded());
    }

    public StructureAssetMaterializer(StructureCatalog catalog) {
        this.catalog = catalog;
    }

    public boolean tryMaterialize(
            ServerLevel level,
            SafeChunkWriter writer,
            PlannedBuilding building,
            PlannedSettlement settlement,
            ChunkMaterializationState state
    ) {
        if (!building.usesImportedAsset()) {
            return false;
        }
        Optional<StructureAsset> assetOpt = catalog.get(building.assetId());
        if (assetOpt.isEmpty()) {
            return false;
        }
        Optional<MlsStructureFormat.StructureContent> contentOpt = catalog.loadContent(building.assetId());
        if (contentOpt.isEmpty()) {
            return false;
        }
        StructureAsset asset = assetOpt.get();
        MlsStructureFormat.StructureContent content = contentOpt.get();
        int rotations = ((building.rotationY() / 90) % 4 + 4) % 4;
        int[] dims = MlsStructureFormat.rotatedDimensions(content.width(), content.depth(), rotations);
        BoundingBox2 fp = building.footprint();
        int originX = fp.minX();
        int originZ = fp.minZ();
        int baseY = building.foundationY() > 0
                ? building.foundationY()
                : level.getHeight(Heightmap.Types.WORLD_SURFACE_WG, fp.center().x(), fp.center().z());

        gradeFoundation(level, writer, fp, baseY, asset.foundationMode(), asset.terrainTolerance());

        int chunkX = writer.chunk().getPos().x;
        int chunkZ = writer.chunk().getPos().z;
        Map<String, BlockState> paletteCache = new HashMap<>();

        // World-chunk → structure-local chunk keys after rotation.
        // Structure local (0..dims) maps to world originX/originZ.
        int localMinX = Math.max(0, (chunkX << 4) - originX);
        int localMaxX = Math.min(dims[0] - 1, ((chunkX + 1) << 4) - 1 - originX);
        int localMinZ = Math.max(0, (chunkZ << 4) - originZ);
        int localMaxZ = Math.min(dims[1] - 1, ((chunkZ + 1) << 4) - 1 - originZ);
        if (localMinX > localMaxX || localMinZ > localMaxZ) {
            if (intersectsChunk(fp, chunkX, chunkZ)) {
                state.recordStructure(building.id());
                LivingModsStructureIndex.get(level).putBuilding(building);
            }
            return true;
        }

        Map<Long, List<MlsStructureFormat.BlockPlacement>> index = rotatedIndex(building.assetId(), content, rotations);
        int cx0 = localMinX >> 4;
        int cx1 = localMaxX >> 4;
        int cz0 = localMinZ >> 4;
        int cz1 = localMaxZ >> 4;

        int placed = 0;
        for (int lcx = cx0; lcx <= cx1; lcx++) {
            for (int lcz = cz0; lcz <= cz1; lcz++) {
                long key = ((long) lcx << 32) ^ (lcz & 0xffffffffL);
                List<MlsStructureFormat.BlockPlacement> slice = index.get(key);
                if (slice == null) continue;
                for (MlsStructureFormat.BlockPlacement rb : slice) {
                    int wx = originX + rb.x();
                    int wz = originZ + rb.z();
                    if ((wx >> 4) != chunkX || (wz >> 4) != chunkZ) continue;
                    if (!writer.inChunk(wx, wz)) continue;
                    int wy = baseY + rb.y();
                    String paletteEntry = content.palette().get(rb.paletteIndex());
                    String rotatedState = MlsStructureFormat.rotateBlockState(paletteEntry, rotations);
                    BlockState stateBlock = resolveState(rotatedState, paletteCache);
                    if (stateBlock == null || stateBlock.isAir()) continue;
                    if (isForbidden(stateBlock)) continue;
                    if (writer.trySet(new BlockPos(wx, wy, wz), stateBlock)) {
                        placed++;
                    }
                }
            }
        }

        clearEntrance(writer, building, asset, content, rotations, originX, originZ, baseY, dims);

        if (placed > 0 || intersectsChunk(fp, chunkX, chunkZ)) {
            state.recordStructure(building.id());
            LivingModsStructureIndex.get(level).putBuilding(building);
            return true;
        }
        return true;
    }

    private Map<Long, List<MlsStructureFormat.BlockPlacement>> rotatedIndex(
            String assetId, MlsStructureFormat.StructureContent content, int rotations
    ) {
        String key = assetId + "|" + rotations;
        Map<Long, List<MlsStructureFormat.BlockPlacement>> cached = rotIndexCache.get(key);
        if (cached != null) return cached;
        Map<Long, List<MlsStructureFormat.BlockPlacement>> built =
                MlsStructureFormat.rotatedChunkIndex(content, rotations);
        if (rotIndexCache.size() >= MAX_ROT_CACHE) {
            String first = rotIndexCache.keySet().stream().findFirst().orElse(null);
            if (first != null) rotIndexCache.remove(first);
        }
        rotIndexCache.put(key, built);
        return built;
    }

    private void gradeFoundation(
            ServerLevel level,
            SafeChunkWriter writer,
            BoundingBox2 fp,
            int baseY,
            FoundationMode mode,
            int terrainTolerance
    ) {
        int tol = Math.max(1, terrainTolerance);
        for (int x = fp.minX(); x <= fp.maxX(); x++) {
            for (int z = fp.minZ(); z <= fp.maxZ(); z++) {
                if (!writer.inChunk(x, z)) continue;
                int surface = level.getHeight(Heightmap.Types.WORLD_SURFACE_WG, x, z);
                switch (mode) {
                    case STILTS -> {
                        // Clear vegetation; place support posts at corners/edges.
                        for (int y = surface; y <= baseY + 1; y++) {
                            clearNatural(writer, x, y, z);
                        }
                        boolean post = (x == fp.minX() || x == fp.maxX()) && (z == fp.minZ() || z == fp.maxZ());
                        if (post) {
                            for (int y = Math.min(surface, baseY) - 1; y <= baseY; y++) {
                                writer.trySet(new BlockPos(x, y, z), Blocks.OAK_LOG.defaultBlockState());
                            }
                        }
                    }
                    case UNDERGROUND -> {
                        for (int y = baseY - 2; y <= baseY; y++) {
                            writer.trySet(new BlockPos(x, y, z),
                                    y == baseY ? Blocks.STONE_BRICKS.defaultBlockState() : Blocks.STONE.defaultBlockState());
                        }
                        for (int y = baseY + 1; y <= baseY + 4; y++) {
                            clearNatural(writer, x, y, z);
                        }
                    }
                    case TERRACED -> {
                        int local = surface;
                        int step = Math.max(baseY - tol, Math.min(baseY + tol, local));
                        // Step toward baseY in 2-block terraces
                        int terraceY = baseY + ((local - baseY) / 2) * 2;
                        terraceY = Math.max(baseY - tol, Math.min(baseY + tol, terraceY));
                        fillTo(writer, x, z, surface, terraceY);
                        clearAbove(writer, x, z, terraceY, terraceY + 6);
                    }
                    case HILLSIDE -> {
                        int target = Math.max(baseY - tol, Math.min(baseY + tol / 2, surface));
                        // Retaining fill on downhill side
                        if (surface < target) {
                            for (int y = surface; y < target; y++) {
                                writer.trySet(new BlockPos(x, y, z), Blocks.COBBLESTONE.defaultBlockState());
                            }
                        } else if (surface > target + 1) {
                            for (int y = target + 1; y <= Math.min(surface, target + tol); y++) {
                                clearNatural(writer, x, y, z);
                            }
                        }
                        writer.trySet(new BlockPos(x, target, z), Blocks.STONE_BRICKS.defaultBlockState());
                        clearAbove(writer, x, z, target, target + 8);
                    }
                    case FLAT -> {
                        // Minimal alteration — only clear vegetation in footprint, light pad.
                        if (Math.abs(surface - baseY) <= 1) {
                            writer.trySet(new BlockPos(x, baseY, z), Blocks.DIRT_PATH.defaultBlockState());
                            clearAbove(writer, x, z, baseY, baseY + 4);
                        } else if (Math.abs(surface - baseY) <= tol) {
                            fillTo(writer, x, z, surface, baseY);
                            clearAbove(writer, x, z, baseY, baseY + 6);
                        }
                    }
                    case WATERFRONT -> {
                        if (surface <= baseY) {
                            for (int y = Math.min(surface, baseY) - 1; y < baseY; y++) {
                                writer.trySet(new BlockPos(x, y, z), Blocks.OAK_PLANKS.defaultBlockState());
                            }
                        }
                        writer.trySet(new BlockPos(x, baseY, z), Blocks.OAK_PLANKS.defaultBlockState());
                        clearAbove(writer, x, z, baseY, baseY + 8);
                    }
                    case CUT_AND_FILL -> {
                        if (Math.abs(surface - baseY) > tol + 2) {
                            // Refuse to bulldoze mountains — light touch only
                            clearAbove(writer, x, z, Math.max(surface, baseY), Math.max(surface, baseY) + 2);
                        } else {
                            fillTo(writer, x, z, surface, baseY);
                            writer.trySet(new BlockPos(x, baseY, z), Blocks.STONE_BRICKS.defaultBlockState());
                            clearAbove(writer, x, z, baseY, baseY + 12);
                        }
                    }
                    default -> {
                        fillTo(writer, x, z, surface, baseY);
                        writer.trySet(new BlockPos(x, baseY, z), Blocks.STONE_BRICKS.defaultBlockState());
                        clearAbove(writer, x, z, baseY, baseY + 12);
                    }
                }
            }
        }
    }

    private static void fillTo(SafeChunkWriter writer, int x, int z, int surface, int baseY) {
        int fillFrom = Math.min(surface, baseY) - 1;
        for (int y = fillFrom; y < baseY; y++) {
            writer.trySet(new BlockPos(x, y, z), Blocks.DIRT.defaultBlockState());
        }
    }

    private static void clearAbove(SafeChunkWriter writer, int x, int z, int baseY, int clearTo) {
        for (int y = baseY + 1; y <= clearTo; y++) {
            clearNatural(writer, x, y, z);
        }
    }

    private static void clearNatural(SafeChunkWriter writer, int x, int y, int z) {
        BlockPos p = new BlockPos(x, y, z);
        BlockState s = writer.chunk().getBlockState(p);
        if (SafeChunkWriter.isNaturalTerrain(s) || s.isAir()) {
            if (!s.is(Blocks.WATER) && !s.is(Blocks.LAVA)) {
                writer.trySet(p, Blocks.AIR.defaultBlockState());
            }
        }
    }

    private void clearEntrance(
            SafeChunkWriter writer,
            PlannedBuilding building,
            StructureAsset asset,
            MlsStructureFormat.StructureContent content,
            int rotations,
            int originX,
            int originZ,
            int baseY,
            int[] dims
    ) {
        // Transform authored entrance into world space.
        MlsStructureFormat.BlockPlacement entranceLocal = MlsStructureFormat.rotate(
                new MlsStructureFormat.BlockPlacement(
                        asset.entranceX(), asset.entranceY(), asset.entranceZ(), 0),
                content.width(), content.depth(), rotations
        );
        int ex = originX + Math.max(0, Math.min(dims[0] - 1, entranceLocal.x()));
        int ez = originZ + Math.max(0, Math.min(dims[1] - 1, entranceLocal.z()));
        int ey = baseY + Math.max(1, asset.entranceY());
        for (int dy = 0; dy <= 2; dy++) {
            if (writer.inChunk(ex, ez)) {
                writer.trySet(new BlockPos(ex, ey + dy, ez), Blocks.AIR.defaultBlockState());
            }
        }
        // One block outside toward facing
        String facing = MlsStructureFormat.rotateFacing(asset.entranceFacing(), rotations);
        int ox = ex;
        int oz = ez;
        switch (facing) {
            case "north" -> oz--;
            case "south" -> oz++;
            case "west" -> ox--;
            default -> ox++;
        }
        if (writer.inChunk(ox, oz)) {
            writer.trySet(new BlockPos(ox, ey, oz), Blocks.AIR.defaultBlockState());
            writer.trySet(new BlockPos(ox, ey + 1, oz), Blocks.AIR.defaultBlockState());
        }
    }

    private static boolean intersectsChunk(BoundingBox2 fp, int chunkX, int chunkZ) {
        int minCx = fp.minX() >> 4;
        int maxCx = fp.maxX() >> 4;
        int minCz = fp.minZ() >> 4;
        int maxCz = fp.maxZ() >> 4;
        return chunkX >= minCx && chunkX <= maxCx && chunkZ >= minCz && chunkZ <= maxCz;
    }

    private static boolean isForbidden(BlockState state) {
        Block b = state.getBlock();
        return b == Blocks.COMMAND_BLOCK
                || b == Blocks.CHAIN_COMMAND_BLOCK
                || b == Blocks.REPEATING_COMMAND_BLOCK
                || b == Blocks.STRUCTURE_BLOCK
                || b == Blocks.JIGSAW
                || b == Blocks.BARRIER
                || b == Blocks.STRUCTURE_VOID;
    }

    private static BlockState resolveState(String raw, Map<String, BlockState> cache) {
        if (raw == null || raw.isBlank()) return Blocks.AIR.defaultBlockState();
        BlockState cached = cache.get(raw);
        if (cached != null) return cached;
        String id = raw;
        String props = null;
        int bracket = raw.indexOf('[');
        if (bracket >= 0) {
            id = raw.substring(0, bracket);
            int end = raw.indexOf(']', bracket);
            props = end > bracket ? raw.substring(bracket + 1, end) : null;
        }
        if (id.startsWith("minecraft:")) {
            id = id.substring("minecraft:".length());
        }
        if (id.contains(":") && !id.startsWith("minecraft:")) {
            cache.put(raw, Blocks.AIR.defaultBlockState());
            return Blocks.AIR.defaultBlockState();
        }
        ResourceLocation rl = ResourceLocation.tryParse(id.contains(":") ? id : "minecraft:" + id);
        if (rl == null) {
            cache.put(raw, Blocks.AIR.defaultBlockState());
            return Blocks.AIR.defaultBlockState();
        }
        Optional<Block> block = BuiltInRegistries.BLOCK.getOptional(rl);
        if (block.isEmpty()) {
            cache.put(raw, Blocks.AIR.defaultBlockState());
            return Blocks.AIR.defaultBlockState();
        }
        BlockState state = block.get().defaultBlockState();
        if (props != null && !props.isBlank()) {
            state = applyProperties(state, props);
        }
        cache.put(raw, state);
        return state;
    }

    private static BlockState applyProperties(BlockState state, String props) {
        BlockState out = state;
        for (String part : props.split(",")) {
            String[] kv = part.split("=", 2);
            if (kv.length != 2) continue;
            String key = kv[0].trim().toLowerCase(Locale.ROOT);
            String value = kv[1].trim().toLowerCase(Locale.ROOT);
            for (var property : out.getProperties()) {
                if (!property.getName().equals(key)) continue;
                Optional<?> parsed = property.getValue(value);
                if (parsed.isPresent()) {
                    //noinspection unchecked,rawtypes
                    out = out.setValue((net.minecraft.world.level.block.state.properties.Property) property, (Comparable) parsed.get());
                }
            }
        }
        return out;
    }
}
