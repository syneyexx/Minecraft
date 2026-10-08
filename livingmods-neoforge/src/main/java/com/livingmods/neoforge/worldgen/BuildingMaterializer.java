package com.livingmods.neoforge.worldgen;

import com.livingmods.common.culture.CultureDefinition;
import com.livingmods.common.culture.CultureRegistry;
import com.livingmods.common.geo.BlockPos2;
import com.livingmods.common.geo.BoundingBox2;
import com.livingmods.common.model.BuildingRole;
import com.livingmods.worldgen.plan.PlannedBuilding;
import com.livingmods.worldgen.plan.PlannedSettlement;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LadderBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.List;

/**
 * Consumes full architecture-grammar metadata on {@link PlannedBuilding} — hollow shells with
 * floors, walls, doors, windows, stairs, roof, interiors by role. Not solid cuboids.
 */
public final class BuildingMaterializer {
    private static final int FLOOR_HEIGHT = 4;

    private final CultureRegistry cultures = new CultureRegistry();
    private final StructureAssetMaterializer importedAssets = new StructureAssetMaterializer();

    public void materialize(
            ServerLevel level,
            SafeChunkWriter writer,
            PlannedBuilding building,
            PlannedSettlement settlement,
            ChunkMaterializationState state
    ) {
        // Imported/authored asset path — procedural fallback when missing/invalid.
        if (building.usesImportedAsset()
                && importedAssets.tryMaterialize(level, writer, building, settlement, state)) {
            return;
        }

        CultureDefinition culture = cultures.get(building.cultureKey()).orElse(cultures.all().get(0));
        BoundingBox2 fp = building.footprint();
        int baseY = building.foundationY() > 0
                ? building.foundationY()
                : level.getHeight(Heightmap.Types.WORLD_SURFACE_WG, fp.center().x(), fp.center().z());

        // Terrain connection under footprint
        gradeFoundation(level, writer, fp, baseY);

        BlockState primary = CulturalBlocks.primary(culture);
        BlockState secondary = CulturalBlocks.secondary(culture);
        BlockState accent = CulturalBlocks.accent(culture);
        BlockState roof = CulturalBlocks.roof(culture);
        BlockState wallBlock = CulturalBlocks.wall(culture);

        int floors = Math.max(1, building.floorCount());
        Direction entrance = facing(building.entranceFacing());
        BlockPos doorPos = doorPosition(fp, entrance, baseY + 1);

        if (building.hasBasement()) {
            carveBasement(writer, fp, baseY, secondary);
        }

        // Foundation slab
        for (int x = fp.minX(); x <= fp.maxX(); x++) {
            for (int z = fp.minZ(); z <= fp.maxZ(); z++) {
                writer.trySet(new BlockPos(x, baseY, z), Blocks.STONE_BRICKS.defaultBlockState());
            }
        }

        for (int floor = 0; floor < floors; floor++) {
            int y0 = baseY + 1 + floor * FLOOR_HEIGHT;
            placeFloorDeck(writer, fp, y0 - 1, floor == 0 ? Blocks.STONE_BRICKS.defaultBlockState() : secondary);
            placeExteriorWalls(writer, fp, y0, FLOOR_HEIGHT - 1, primary, accent, entrance, doorPos, floor, building);
            placeWindows(writer, fp, y0, building.windowPositions(), floor);
            placeInteriorPartition(writer, fp, y0, secondary, floor, building);
            placeLighting(writer, fp, y0 + 2, floor);
            if (floor < floors - 1) {
                placeStairs(writer, fp, y0, entrance);
            }
        }

        int topY = baseY + floors * FLOOR_HEIGHT;
        placeRoof(writer, fp, topY, roof, culture.architecture().roofStyle(), accent);
        placeChimney(writer, fp, topY, entrance, primary);

        // Clear doorway volume and connect to street
        clearDoorway(writer, doorPos, entrance, FLOOR_HEIGHT - 1);
        placeDoor(writer, doorPos, entrance);
        connectToRoad(level, writer, doorPos, entrance, settlement, culture);

        placeRoleInterior(writer, fp, baseY + 1, building.role(), building.roomTags(), entrance);

        state.recordStructure(building.id());
        LivingModsStructureIndex.get(level).putBuilding(building);
    }

    private void gradeFoundation(ServerLevel level, SafeChunkWriter writer, BoundingBox2 fp, int baseY) {
        for (int x = fp.minX(); x <= fp.maxX(); x++) {
            for (int z = fp.minZ(); z <= fp.maxZ(); z++) {
                if (!writer.inChunk(x, z)) continue;
                int surface = level.getHeight(Heightmap.Types.WORLD_SURFACE_WG, x, z);
                for (int y = Math.min(surface, baseY) - 1; y < baseY; y++) {
                    writer.trySet(new BlockPos(x, y, z), Blocks.DIRT.defaultBlockState());
                }
                // Clear above foundation into building volume later
                for (int y = baseY + 1; y <= baseY + 12; y++) {
                    BlockPos p = new BlockPos(x, y, z);
                    BlockState s = writer.chunk().getBlockState(p);
                    if (SafeChunkWriter.isNaturalTerrain(s) || s.isAir()) {
                        writer.trySet(p, Blocks.AIR.defaultBlockState());
                    }
                }
            }
        }
    }

    private void carveBasement(SafeChunkWriter writer, BoundingBox2 fp, int baseY, BlockState wall) {
        for (int x = fp.minX() + 1; x <= fp.maxX() - 1; x++) {
            for (int z = fp.minZ() + 1; z <= fp.maxZ() - 1; z++) {
                for (int y = baseY - 3; y < baseY; y++) {
                    writer.trySet(new BlockPos(x, y, z), Blocks.AIR.defaultBlockState());
                }
            }
        }
        for (int x = fp.minX(); x <= fp.maxX(); x++) {
            for (int z = fp.minZ(); z <= fp.maxZ(); z++) {
                boolean edge = x == fp.minX() || x == fp.maxX() || z == fp.minZ() || z == fp.maxZ();
                if (edge) {
                    for (int y = baseY - 3; y < baseY; y++) {
                        writer.trySet(new BlockPos(x, y, z), wall);
                    }
                }
            }
        }
    }

    private void placeFloorDeck(SafeChunkWriter writer, BoundingBox2 fp, int y, BlockState deck) {
        for (int x = fp.minX() + 1; x <= fp.maxX() - 1; x++) {
            for (int z = fp.minZ() + 1; z <= fp.maxZ() - 1; z++) {
                writer.trySet(new BlockPos(x, y, z), deck);
            }
        }
    }

    private void placeExteriorWalls(
            SafeChunkWriter writer,
            BoundingBox2 fp,
            int y0,
            int height,
            BlockState primary,
            BlockState accent,
            Direction entrance,
            BlockPos doorPos,
            int floor,
            PlannedBuilding building
    ) {
        for (int dy = 0; dy < height; dy++) {
            int y = y0 + dy;
            for (int x = fp.minX(); x <= fp.maxX(); x++) {
                for (int z = fp.minZ(); z <= fp.maxZ(); z++) {
                    boolean edge = x == fp.minX() || x == fp.maxX() || z == fp.minZ() || z == fp.maxZ();
                    if (!edge) continue;
                    boolean corner = (x == fp.minX() || x == fp.maxX()) && (z == fp.minZ() || z == fp.maxZ());
                    // Doorway opening on ground floor
                    if (floor == 0 && dy < 2 && isDoorColumn(x, z, doorPos, entrance)) {
                        writer.trySet(new BlockPos(x, y, z), Blocks.AIR.defaultBlockState());
                        continue;
                    }
                    writer.trySet(new BlockPos(x, y, z), corner ? accent : primary);
                }
            }
        }
    }

    private boolean isDoorColumn(int x, int z, BlockPos door, Direction entrance) {
        if (entrance.getAxis() == Direction.Axis.X) {
            return x == door.getX() && Math.abs(z - door.getZ()) <= 0;
        }
        return z == door.getZ() && Math.abs(x - door.getX()) <= 0;
    }

    private void placeWindows(SafeChunkWriter writer, BoundingBox2 fp, int y0, List<String> windows, int floor) {
        for (String spec : windows) {
            // win:side:along@floor
            if (!spec.startsWith("win:")) continue;
            String[] parts = spec.substring(4).split("[@:]");
            if (parts.length < 3) continue;
            try {
                int side = Integer.parseInt(parts[0]);
                int along = Integer.parseInt(parts[1]);
                int f = Integer.parseInt(parts[2]);
                if (f != floor) continue;
                BlockPos wp = windowPos(fp, side, along, y0 + 1);
                if (wp != null) {
                    writer.trySet(wp, Blocks.GLASS_PANE.defaultBlockState());
                }
            } catch (NumberFormatException ignored) {
            }
        }
    }

    private BlockPos windowPos(BoundingBox2 fp, int side, int along, int y) {
        int w = fp.width();
        int d = fp.depth();
        return switch (side) {
            case 0 -> new BlockPos(fp.minX() + Math.floorMod(along, w), y, fp.minZ());
            case 1 -> new BlockPos(fp.maxX(), y, fp.minZ() + Math.floorMod(along, d));
            case 2 -> new BlockPos(fp.minX() + Math.floorMod(along, w), y, fp.maxZ());
            default -> new BlockPos(fp.minX(), y, fp.minZ() + Math.floorMod(along, d));
        };
    }

    private void placeInteriorPartition(
            SafeChunkWriter writer, BoundingBox2 fp, int y0, BlockState mat, int floor, PlannedBuilding building
    ) {
        if (fp.width() < 7 || fp.depth() < 7) return;
        int midX = fp.center().x();
        int midZ = fp.center().z();
        // Simple cross partition leaving doorway gaps
        for (int z = fp.minZ() + 1; z <= fp.maxZ() - 1; z++) {
            if (z == midZ) continue;
            for (int dy = 0; dy < FLOOR_HEIGHT - 1; dy++) {
                writer.trySet(new BlockPos(midX, y0 + dy, z), mat);
            }
        }
        if (building.roomTags().size() > 4) {
            for (int x = fp.minX() + 1; x <= fp.maxX() - 1; x++) {
                if (x == midX) continue;
                for (int dy = 0; dy < FLOOR_HEIGHT - 1; dy++) {
                    writer.trySet(new BlockPos(x, y0 + dy, midZ), mat);
                }
            }
        }
    }

    private void placeStairs(SafeChunkWriter writer, BoundingBox2 fp, int y0, Direction entrance) {
        int sx = fp.maxX() - 1;
        int sz = fp.maxZ() - 1;
        for (int i = 0; i < FLOOR_HEIGHT; i++) {
            BlockPos p = new BlockPos(sx, y0 + i, sz);
            writer.trySet(p, Blocks.AIR.defaultBlockState());
            writer.trySet(p, Blocks.LADDER.defaultBlockState().setValue(LadderBlock.FACING, entrance.getOpposite()));
        }
    }

    private void placeRoof(
            SafeChunkWriter writer,
            BoundingBox2 fp,
            int topY,
            BlockState roof,
            CultureDefinition.RoofStyle style,
            BlockState accent
    ) {
        switch (style) {
            case FLAT -> {
                for (int x = fp.minX(); x <= fp.maxX(); x++) {
                    for (int z = fp.minZ(); z <= fp.maxZ(); z++) {
                        writer.trySet(new BlockPos(x, topY, z), accent);
                    }
                }
            }
            case DOMED, MAGICAL -> {
                int cx = fp.center().x();
                int cz = fp.center().z();
                int r = Math.max(fp.width(), fp.depth()) / 2;
                for (int x = fp.minX(); x <= fp.maxX(); x++) {
                    for (int z = fp.minZ(); z <= fp.maxZ(); z++) {
                        int dx = x - cx;
                        int dz = z - cz;
                        if (dx * dx + dz * dz <= r * r) {
                            int rise = Math.max(0, r - (int) Math.sqrt(dx * dx + dz * dz));
                            writer.trySet(new BlockPos(x, topY + rise / 2, z), roof.is(Blocks.SPRUCE_STAIRS) ? accent : roof);
                        }
                    }
                }
            }
            default -> {
                // Gable / hip / thatch: peaked rows
                int depth = fp.depth();
                for (int z = fp.minZ(); z <= fp.maxZ(); z++) {
                    int fromEdge = Math.min(z - fp.minZ(), fp.maxZ() - z);
                    int rise = Math.min(fromEdge, depth / 2);
                    for (int x = fp.minX(); x <= fp.maxX(); x++) {
                        BlockState state = roof;
                        if (roof.getBlock() instanceof StairBlock) {
                            Direction face = z <= fp.center().z() ? Direction.SOUTH : Direction.NORTH;
                            state = roof.setValue(StairBlock.FACING, face).setValue(StairBlock.HALF, Half.BOTTOM);
                        }
                        writer.trySet(new BlockPos(x, topY + rise, z), state);
                    }
                }
            }
        }
    }

    private void placeChimney(SafeChunkWriter writer, BoundingBox2 fp, int topY, Direction entrance, BlockState primary) {
        int x = fp.minX() + 1;
        int z = entrance.getAxis() == Direction.Axis.Z ? fp.minZ() + 1 : fp.maxZ() - 1;
        for (int y = topY; y <= topY + 3; y++) {
            writer.trySet(new BlockPos(x, y, z), primary);
        }
        writer.trySet(new BlockPos(x, topY + 4, z), Blocks.CAMPFIRE.defaultBlockState());
    }

    private void placeLighting(SafeChunkWriter writer, BoundingBox2 fp, int y, int floor) {
        writer.trySet(new BlockPos(fp.center().x(), y, fp.center().z()), Blocks.LANTERN.defaultBlockState());
        if (fp.width() > 8) {
            writer.trySet(new BlockPos(fp.minX() + 2, y, fp.minZ() + 2), Blocks.WALL_TORCH.defaultBlockState());
        }
    }

    private void clearDoorway(SafeChunkWriter writer, BlockPos door, Direction facing, int height) {
        for (int dy = 0; dy < height; dy++) {
            writer.trySet(door.above(dy), Blocks.AIR.defaultBlockState());
            writer.trySet(door.relative(facing.getClockWise()).above(dy), Blocks.AIR.defaultBlockState());
        }
    }

    private void placeDoor(SafeChunkWriter writer, BlockPos door, Direction facing) {
        writer.trySet(door, Blocks.OAK_DOOR.defaultBlockState());
        writer.trySet(door.above(), Blocks.OAK_DOOR.defaultBlockState());
    }

    private void connectToRoad(
            ServerLevel level,
            SafeChunkWriter writer,
            BlockPos door,
            Direction facing,
            PlannedSettlement settlement,
            CultureDefinition culture
    ) {
        BlockState path = CulturalBlocks.road(culture);
        BlockPos2 best = null;
        double bestDist = Double.MAX_VALUE;
        BlockPos2 door2 = BlockPos2.of(door.getX(), door.getZ());
        if (settlement != null && settlement.streetNetwork() != null) {
            for (BlockPos2 street : settlement.streetNetwork()) {
                double d = door2.distanceTo(street);
                if (d < bestDist) {
                    bestDist = d;
                    best = street;
                }
            }
        }
        // Walk out from door toward street / facing direction
        int steps = best == null ? 6 : Math.min(24, (int) Math.ceil(bestDist) + 2);
        int x = door.getX();
        int z = door.getZ();
        int tx = best != null ? best.x() : x + facing.getStepX() * steps;
        int tz = best != null ? best.z() : z + facing.getStepZ() * steps;
        for (int i = 0; i < steps; i++) {
            if (x != tx) x += Integer.signum(tx - x);
            else if (z != tz) z += Integer.signum(tz - z);
            int y = level.getHeight(Heightmap.Types.WORLD_SURFACE_WG, x, z) - 1;
            // Avoid placing path into cliffs: step height limited
            int prevY = door.getY() - 1;
            if (Math.abs(y - prevY) > 2) {
                y = prevY + Integer.signum(y - prevY);
            }
            writer.trySet(new BlockPos(x, y, z), path);
            // Clear above path
            writer.trySet(new BlockPos(x, y + 1, z), Blocks.AIR.defaultBlockState());
            writer.trySet(new BlockPos(x, y + 2, z), Blocks.AIR.defaultBlockState());
        }
    }

    private void placeRoleInterior(
            SafeChunkWriter writer,
            BoundingBox2 fp,
            int y,
            BuildingRole role,
            List<String> rooms,
            Direction entrance
    ) {
        int ix = fp.minX() + 2;
        int iz = fp.minZ() + 2;
        int ax = fp.maxX() - 2;
        int az = fp.maxZ() - 2;
        switch (role) {
            case HOUSE, TOWNHOUSE, MANOR, FARMHOUSE -> {
                writer.trySet(new BlockPos(ix, y, iz), Blocks.WHITE_BED.defaultBlockState());
                writer.trySet(new BlockPos(ax, y, iz), Blocks.CHEST.defaultBlockState());
                writer.trySet(new BlockPos(ix, y, az), Blocks.CRAFTING_TABLE.defaultBlockState());
                writer.trySet(new BlockPos(fp.center().x(), y, fp.center().z()), Blocks.OAK_STAIRS.defaultBlockState());
            }
            case SMITHY -> {
                writer.trySet(new BlockPos(ix, y, iz), Blocks.BLAST_FURNACE.defaultBlockState());
                writer.trySet(new BlockPos(ix + 1, y, iz), Blocks.ANVIL.defaultBlockState());
                writer.trySet(new BlockPos(ax, y, iz), Blocks.CHEST.defaultBlockState());
                writer.trySet(new BlockPos(ax, y, az), Blocks.CRAFTING_TABLE.defaultBlockState());
            }
            case TAVERN -> {
                writer.trySet(new BlockPos(fp.center().x(), y, iz), Blocks.BARREL.defaultBlockState());
                writer.trySet(new BlockPos(ix, y, az), Blocks.SMOKER.defaultBlockState());
                writer.trySet(new BlockPos(ax, y, az), Blocks.CHEST.defaultBlockState());
                placeTable(writer, fp.center().x(), y, fp.center().z());
                placeTable(writer, ix, y, fp.center().z());
            }
            case GUARDHOUSE, BARRACKS -> {
                writer.trySet(new BlockPos(ix, y, iz), Blocks.WHITE_BED.defaultBlockState());
                writer.trySet(new BlockPos(ax, y, iz), Blocks.WHITE_BED.defaultBlockState());
                writer.trySet(new BlockPos(ix, y, az), Blocks.CHEST.defaultBlockState());
                writer.trySet(new BlockPos(ax, y, az), Blocks.SMITHING_TABLE.defaultBlockState());
                writer.trySet(new BlockPos(fp.center().x(), y, az), Blocks.IRON_BARS.defaultBlockState());
            }
            case SCHOOL -> {
                writer.trySet(new BlockPos(fp.center().x(), y, iz), Blocks.LECTERN.defaultBlockState());
                writer.trySet(new BlockPos(ix, y, az), Blocks.LECTERN.defaultBlockState());
                writer.trySet(new BlockPos(ax, y, az), Blocks.BOOKSHELF.defaultBlockState());
                writer.trySet(new BlockPos(ax, y, iz), Blocks.BOOKSHELF.defaultBlockState());
            }
            case CLINIC -> {
                writer.trySet(new BlockPos(ix, y, iz), Blocks.WHITE_BED.defaultBlockState());
                writer.trySet(new BlockPos(ax, y, iz), Blocks.WHITE_BED.defaultBlockState());
                writer.trySet(new BlockPos(fp.center().x(), y, az), Blocks.BREWING_STAND.defaultBlockState());
                writer.trySet(new BlockPos(ix, y, az), Blocks.CHEST.defaultBlockState());
            }
            case PALACE, CASTLE_KEEP -> {
                writer.trySet(new BlockPos(fp.center().x(), y, iz), Blocks.GILDED_BLACKSTONE.defaultBlockState()); // throne base
                writer.trySet(new BlockPos(fp.center().x(), y + 1, iz), Blocks.RED_BANNER.defaultBlockState());
                writer.trySet(new BlockPos(ix, y, az), Blocks.CHEST.defaultBlockState());
                writer.trySet(new BlockPos(ax, y, az), Blocks.BOOKSHELF.defaultBlockState());
                writer.trySet(new BlockPos(ix, y, iz + 1), Blocks.IRON_BARS.defaultBlockState());
                writer.trySet(new BlockPos(ax, y, iz + 1), Blocks.IRON_BARS.defaultBlockState());
            }
            case WORKSHOP -> {
                writer.trySet(new BlockPos(ix, y, iz), Blocks.CRAFTING_TABLE.defaultBlockState());
                writer.trySet(new BlockPos(ax, y, iz), Blocks.CHEST.defaultBlockState());
                writer.trySet(new BlockPos(ix, y, az), Blocks.LOOM.defaultBlockState());
            }
            case TEMPLE -> {
                writer.trySet(new BlockPos(fp.center().x(), y, iz), Blocks.ENCHANTING_TABLE.defaultBlockState());
                writer.trySet(new BlockPos(ix, y, az), Blocks.BOOKSHELF.defaultBlockState());
                writer.trySet(new BlockPos(ax, y, az), Blocks.BOOKSHELF.defaultBlockState());
            }
            case SHOP, MARKET_HALL, MARKET_STALL -> {
                writer.trySet(new BlockPos(fp.center().x(), y, iz), Blocks.BARREL.defaultBlockState());
                writer.trySet(new BlockPos(ix, y, az), Blocks.CHEST.defaultBlockState());
                writer.trySet(new BlockPos(ax, y, az), Blocks.CHEST.defaultBlockState());
            }
            default -> writer.trySet(new BlockPos(fp.center().x(), y, fp.center().z()), Blocks.CHEST.defaultBlockState());
        }
        // Cultural decoration accents
        writer.trySet(new BlockPos(fp.minX() + 1, y + 2, fp.minZ() + 1), Blocks.FLOWER_POT.defaultBlockState());
    }

    private void placeTable(SafeChunkWriter writer, int x, int y, int z) {
        writer.trySet(new BlockPos(x, y, z), Blocks.OAK_FENCE.defaultBlockState());
        writer.trySet(new BlockPos(x, y + 1, z), Blocks.OAK_PRESSURE_PLATE.defaultBlockState());
        writer.trySet(new BlockPos(x + 1, y, z), Blocks.OAK_STAIRS.defaultBlockState());
        writer.trySet(new BlockPos(x - 1, y, z), Blocks.OAK_STAIRS.defaultBlockState());
    }

    private static Direction facing(String name) {
        return switch (name == null ? "south" : name.toLowerCase()) {
            case "north" -> Direction.NORTH;
            case "east" -> Direction.EAST;
            case "west" -> Direction.WEST;
            default -> Direction.SOUTH;
        };
    }

    private static BlockPos doorPosition(BoundingBox2 fp, Direction entrance, int y) {
        return switch (entrance) {
            case NORTH -> new BlockPos(fp.center().x(), y, fp.minZ());
            case SOUTH -> new BlockPos(fp.center().x(), y, fp.maxZ());
            case WEST -> new BlockPos(fp.minX(), y, fp.center().z());
            default -> new BlockPos(fp.maxX(), y, fp.center().z());
        };
    }
}
