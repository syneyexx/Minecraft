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
import net.minecraft.core.Direction;
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

/**
 * Chunk-sliced placement of imported/authored MLS structure assets.
 * Server thread only; SafeChunkWriter; no neighbor chunk force-load.
 */
public final class StructureAssetMaterializer {
    private final StructureCatalog catalog;

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
        // Prefer planned foundation; fall back to surface.
        int baseY = building.foundationY() > 0
                ? building.foundationY()
                : level.getHeight(Heightmap.Types.WORLD_SURFACE_WG, fp.center().x(), fp.center().z());

        gradeFoundation(level, writer, fp, baseY, asset.foundationMode());

        int chunkX = writer.chunk().getPos().x;
        int chunkZ = writer.chunk().getPos().z;
        Map<Long, List<MlsStructureFormat.BlockPlacement>> index = content.chunkIndex();
        // Transform structure-local chunk keys after rotation is expensive; instead filter by world chunk.
        Map<String, BlockState> paletteCache = new HashMap<>();

        int placed = 0;
        for (MlsStructureFormat.BlockPlacement b : content.blocks()) {
            MlsStructureFormat.BlockPlacement rb = MlsStructureFormat.rotate(
                    b, content.width(), content.depth(), rotations);
            if (rb.x() >= dims[0] || rb.z() >= dims[1]) {
                continue;
            }
            int wx = originX + rb.x();
            int wz = originZ + rb.z();
            if ((wx >> 4) != chunkX || (wz >> 4) != chunkZ) {
                continue;
            }
            if (!writer.inChunk(wx, wz)) {
                continue;
            }
            int wy = baseY + rb.y();
            String paletteEntry = content.palette().get(rb.paletteIndex());
            String rotatedState = MlsStructureFormat.rotateBlockState(paletteEntry, rotations);
            BlockState stateBlock = resolveState(rotatedState, paletteCache);
            if (stateBlock == null || stateBlock.isAir()) {
                continue;
            }
            // Never place command/structure/jigsaw from assets.
            if (isForbidden(stateBlock)) {
                continue;
            }
            if (writer.trySet(new BlockPos(wx, wy, wz), stateBlock)) {
                placed++;
            }
        }

        // Minimal role overlay only when functional markers are missing — skip aggressive rewrite.
        ensureEntranceClear(writer, building, baseY);

        if (placed > 0 || intersectsChunk(fp, chunkX, chunkZ)) {
            state.recordStructure(building.id());
            LivingModsStructureIndex.get(level).putBuilding(building);
            return true;
        }
        return true; // asset path handled even if this chunk has no blocks
    }

    private void gradeFoundation(
            ServerLevel level,
            SafeChunkWriter writer,
            BoundingBox2 fp,
            int baseY,
            FoundationMode mode
    ) {
        for (int x = fp.minX(); x <= fp.maxX(); x++) {
            for (int z = fp.minZ(); z <= fp.maxZ(); z++) {
                if (!writer.inChunk(x, z)) continue;
                int surface = level.getHeight(Heightmap.Types.WORLD_SURFACE_WG, x, z);
                if (mode == FoundationMode.STILTS) {
                    // Only place posts at corners later; clear vegetation lightly.
                    for (int y = surface; y <= baseY + 1; y++) {
                        BlockPos p = new BlockPos(x, y, z);
                        BlockState s = writer.chunk().getBlockState(p);
                        if (SafeChunkWriter.isNaturalTerrain(s) || s.isAir()) {
                            if (!s.is(Blocks.WATER) && !s.is(Blocks.LAVA)) {
                                writer.trySet(p, Blocks.AIR.defaultBlockState());
                            }
                        }
                    }
                    continue;
                }
                int fillFrom = Math.min(surface, baseY) - 1;
                for (int y = fillFrom; y < baseY; y++) {
                    writer.trySet(new BlockPos(x, y, z), Blocks.DIRT.defaultBlockState());
                }
                writer.trySet(new BlockPos(x, baseY, z), Blocks.STONE_BRICKS.defaultBlockState());
                int clearTo = baseY + (mode == FoundationMode.UNDERGROUND ? 4 : 16);
                for (int y = baseY + 1; y <= clearTo; y++) {
                    BlockPos p = new BlockPos(x, y, z);
                    BlockState s = writer.chunk().getBlockState(p);
                    if (SafeChunkWriter.isNaturalTerrain(s) || s.isAir()) {
                        writer.trySet(p, Blocks.AIR.defaultBlockState());
                    }
                }
            }
        }
    }

    private void ensureEntranceClear(SafeChunkWriter writer, PlannedBuilding building, int baseY) {
        BoundingBox2 fp = building.footprint();
        Direction dir = switch (building.entranceFacing() == null ? "south" : building.entranceFacing()) {
            case "north" -> Direction.NORTH;
            case "east" -> Direction.EAST;
            case "west" -> Direction.WEST;
            default -> Direction.SOUTH;
        };
        int x = fp.center().x();
        int z = fp.center().z();
        if (dir == Direction.NORTH) z = fp.minZ();
        if (dir == Direction.SOUTH) z = fp.maxZ();
        if (dir == Direction.WEST) x = fp.minX();
        if (dir == Direction.EAST) x = fp.maxX();
        for (int dy = 1; dy <= 2; dy++) {
            writer.trySet(new BlockPos(x, baseY + dy, z), Blocks.AIR.defaultBlockState());
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
        // Reject obvious mod namespaces silently as air (already sanitized at import).
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
