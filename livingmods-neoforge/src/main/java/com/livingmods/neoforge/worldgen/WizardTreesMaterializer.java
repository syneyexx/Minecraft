package com.livingmods.neoforge.worldgen;

import com.livingmods.common.culture.CultureDefinition;
import com.livingmods.common.culture.CultureRegistry;
import com.livingmods.common.geo.BlockPos2;
import com.livingmods.common.geo.BoundingBox2;
import com.livingmods.common.util.DeterministicRandom;
import com.livingmods.common.util.Hashing;
import com.livingmods.worldgen.plan.PlannedBuilding;
import com.livingmods.worldgen.plan.PlannedSettlement;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.List;

/**
 * Wizard Trees underground civilization: caverns, tunnel graph, chambers, temple center,
 * scholars, mining, redstone-lit farms, magical architecture, vertical connectivity, hidden access.
 * Not surface boxes placed underground.
 */
public final class WizardTreesMaterializer {
    /** Nominal cavern floor Y for Wizard Trees settlements. */
    public static final int CAVERN_FLOOR_Y = -28;

    private final CultureRegistry cultures = new CultureRegistry();

    public void materialize(ServerLevel level, SafeChunkWriter writer, PlannedSettlement settlement) {
        if (!settlement.underground()) {
            return;
        }
        CultureDefinition culture = cultures.get(settlement.cultureKey()).orElse(cultures.wizardTrees());
        BlockState primary = CulturalBlocks.primary(culture);
        BlockState accent = CulturalBlocks.accent(culture);
        BlockState road = CulturalBlocks.road(culture);
        BlockState glass = CulturalBlocks.roof(culture);

        BoundingBox2 chunkBox = chunkBox(writer);
        BoundingBox2 bounds = settlement.bounds();
        if (!bounds.intersects(chunkBox.expand(2))) {
            return;
        }

        DeterministicRandom random = new DeterministicRandom(Hashing.mix(
                settlement.id().hashCode(), chunkBox.minX() * 31L + chunkBox.minZ()));

        // Cavern shell within this chunk ∩ settlement
        carveCavernSlice(writer, bounds, chunkBox, primary, random);

        // Underground street toward center
        placeUndergroundStreet(writer, settlement.center(), chunkBox, road);

        // Chambers / homes from buildings or synthetic if empty
        if (settlement.buildings().isEmpty()) {
            placeSyntheticChambers(writer, settlement, chunkBox, primary, accent, glass, random);
        } else {
            for (PlannedBuilding b : settlement.buildings()) {
                if (!b.footprint().intersects(chunkBox)) continue;
                placeChamber(writer, b.footprint(), primary, accent, glass, b.role().name(), random);
            }
        }

        // Theocratic temple near capital center
        if (settlement.capital() && chunkBox.expand(8).contains(settlement.center())) {
            placeTemple(writer, settlement.center(), primary, accent, glass);
        }

        // Redstone-lit underground agriculture pockets
        if (random.chance(0.55)) {
            placeFungalFarm(writer, settlement.center(), chunkBox, random);
        }

        // Scholar / mining annexes
        if (random.chance(0.4)) {
            placeScholarNiche(writer, settlement.center(), chunkBox, accent);
        }
        if (random.chance(0.35)) {
            placeMiningAnnex(writer, settlement.center(), chunkBox, primary);
        }

        // Vertical shaft + hidden surface entrance (local slice)
        placeVerticalAccess(level, writer, settlement.center(), chunkBox, primary, accent);
    }

    private void carveCavernSlice(
            SafeChunkWriter writer,
            BoundingBox2 settlementBounds,
            BoundingBox2 chunkBox,
            BlockState shell,
            DeterministicRandom random
    ) {
        int minX = Math.max(settlementBounds.minX(), chunkBox.minX());
        int maxX = Math.min(settlementBounds.maxX(), chunkBox.maxX());
        int minZ = Math.max(settlementBounds.minZ(), chunkBox.minZ());
        int maxZ = Math.min(settlementBounds.maxZ(), chunkBox.maxZ());
        int floor = CAVERN_FLOOR_Y;
        int ceiling = CAVERN_FLOOR_Y + 10;

        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                // Organic radius falloff from settlement center-ish noise
                double n = random.nextDouble();
                int localCeil = ceiling - (n < 0.2 ? 2 : 0);
                for (int y = floor; y <= localCeil; y++) {
                    boolean shellLayer = y == floor || y == localCeil;
                    if (shellLayer) {
                        writer.trySet(new BlockPos(x, y, z), shell);
                    } else {
                        writer.trySet(new BlockPos(x, y, z), Blocks.AIR.defaultBlockState());
                    }
                }
                // Magical floor inlay
                if ((x + z) % 7 == 0) {
                    writer.trySet(new BlockPos(x, floor, z), Blocks.AMETHYST_BLOCK.defaultBlockState());
                }
            }
        }
    }

    private void placeUndergroundStreet(
            SafeChunkWriter writer, BlockPos2 center, BoundingBox2 chunkBox, BlockState road
    ) {
        for (int x = chunkBox.minX(); x <= chunkBox.maxX(); x++) {
            if (Math.abs(x - center.x()) > 40) continue;
            int z = center.z();
            if (!chunkBox.contains(x, z)) continue;
            writer.trySet(new BlockPos(x, CAVERN_FLOOR_Y, z), road);
            writer.trySet(new BlockPos(x, CAVERN_FLOOR_Y, z + 1), road);
            writer.trySet(new BlockPos(x, CAVERN_FLOOR_Y + 1, z), Blocks.AIR.defaultBlockState());
            writer.trySet(new BlockPos(x, CAVERN_FLOOR_Y + 2, z), Blocks.AIR.defaultBlockState());
            if (x % 5 == 0) {
                writer.trySet(new BlockPos(x, CAVERN_FLOOR_Y + 3, z), Blocks.REDSTONE_LAMP.defaultBlockState());
                writer.trySet(new BlockPos(x, CAVERN_FLOOR_Y + 4, z), Blocks.REDSTONE_BLOCK.defaultBlockState());
            }
        }
        for (int z = chunkBox.minZ(); z <= chunkBox.maxZ(); z++) {
            if (Math.abs(z - center.z()) > 40) continue;
            int x = center.x();
            if (!chunkBox.contains(x, z)) continue;
            writer.trySet(new BlockPos(x, CAVERN_FLOOR_Y, z), road);
            writer.trySet(new BlockPos(x + 1, CAVERN_FLOOR_Y, z), road);
        }
    }

    private void placeSyntheticChambers(
            SafeChunkWriter writer,
            PlannedSettlement settlement,
            BoundingBox2 chunkBox,
            BlockState primary,
            BlockState accent,
            BlockState glass,
            DeterministicRandom random
    ) {
        BlockPos2 c = settlement.center();
        for (int i = 0; i < 3; i++) {
            int ox = (i - 1) * 12 + random.nextInt(-2, 3);
            int oz = random.nextInt(-8, 9);
            BoundingBox2 room = BoundingBox2.of(
                    c.x() + ox - 3, c.z() + oz - 3,
                    c.x() + ox + 3, c.z() + oz + 3
            );
            if (!room.intersects(chunkBox)) continue;
            String role = switch (i) {
                case 0 -> "home";
                case 1 -> "scholar";
                default -> "mining";
            };
            placeChamber(writer, room, primary, accent, glass, role, random);
        }
    }

    private void placeChamber(
            SafeChunkWriter writer,
            BoundingBox2 room,
            BlockState primary,
            BlockState accent,
            BlockState glass,
            String role,
            DeterministicRandom random
    ) {
        int floor = CAVERN_FLOOR_Y;
        for (int x = room.minX(); x <= room.maxX(); x++) {
            for (int z = room.minZ(); z <= room.maxZ(); z++) {
                if (!writer.inChunk(x, z)) continue;
                boolean edge = x == room.minX() || x == room.maxX() || z == room.minZ() || z == room.maxZ();
                writer.trySet(new BlockPos(x, floor, z), primary);
                for (int dy = 1; dy <= 4; dy++) {
                    if (edge) {
                        BlockState wall = dy == 2 && random.chance(0.25) ? glass : primary;
                        writer.trySet(new BlockPos(x, floor + dy, z), wall);
                    } else {
                        writer.trySet(new BlockPos(x, floor + dy, z), Blocks.AIR.defaultBlockState());
                    }
                }
                writer.trySet(new BlockPos(x, floor + 5, z), accent);
            }
        }
        // Door gap toward -Z
        int dx = room.center().x();
        int dz = room.minZ();
        writer.trySet(new BlockPos(dx, floor + 1, dz), Blocks.AIR.defaultBlockState());
        writer.trySet(new BlockPos(dx, floor + 2, dz), Blocks.AIR.defaultBlockState());

        int ix = room.center().x();
        int iz = room.center().z();
        if (role.contains("home") || role.contains("HOUSE")) {
            writer.trySet(new BlockPos(ix, floor + 1, iz), Blocks.PURPLE_BED.defaultBlockState());
            writer.trySet(new BlockPos(ix + 1, floor + 1, iz), Blocks.CHEST.defaultBlockState());
        } else if (role.contains("scholar") || role.contains("SCHOOL") || role.contains("TEMPLE")) {
            writer.trySet(new BlockPos(ix, floor + 1, iz), Blocks.LECTERN.defaultBlockState());
            writer.trySet(new BlockPos(ix + 1, floor + 1, iz), Blocks.BOOKSHELF.defaultBlockState());
            writer.trySet(new BlockPos(ix - 1, floor + 1, iz), Blocks.ENCHANTING_TABLE.defaultBlockState());
        } else if (role.contains("mining") || role.contains("MINE")) {
            writer.trySet(new BlockPos(ix, floor + 1, iz), Blocks.CRAFTING_TABLE.defaultBlockState());
            writer.trySet(new BlockPos(ix + 1, floor + 1, iz), Blocks.BLAST_FURNACE.defaultBlockState());
        }
        writer.trySet(new BlockPos(ix, floor + 3, iz), Blocks.LANTERN.defaultBlockState());
    }

    private void placeTemple(
            SafeChunkWriter writer, BlockPos2 center, BlockState primary, BlockState accent, BlockState glass
    ) {
        int floor = CAVERN_FLOOR_Y;
        int r = 6;
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                if (dx * dx + dz * dz > r * r) continue;
                int x = center.x() + dx;
                int z = center.z() + dz;
                if (!writer.inChunk(x, z)) continue;
                writer.trySet(new BlockPos(x, floor, z), accent);
                if (dx * dx + dz * dz > (r - 1) * (r - 1)) {
                    for (int dy = 1; dy <= 6; dy++) {
                        writer.trySet(new BlockPos(x, floor + dy, z), dy == 3 ? glass : primary);
                    }
                } else {
                    for (int dy = 1; dy <= 5; dy++) {
                        writer.trySet(new BlockPos(x, floor + dy, z), Blocks.AIR.defaultBlockState());
                    }
                }
            }
        }
        writer.trySet(new BlockPos(center.x(), floor + 1, center.z()), Blocks.AMETHYST_CLUSTER.defaultBlockState());
        writer.trySet(new BlockPos(center.x(), floor + 2, center.z()), Blocks.END_ROD.defaultBlockState());
        writer.trySet(new BlockPos(center.x() + 2, floor + 1, center.z()), Blocks.ENCHANTING_TABLE.defaultBlockState());
        writer.trySet(new BlockPos(center.x() - 2, floor + 1, center.z()), Blocks.LECTERN.defaultBlockState());
    }

    private void placeFungalFarm(SafeChunkWriter writer, BlockPos2 center, BoundingBox2 chunkBox, DeterministicRandom random) {
        int ox = center.x() + 10;
        int oz = center.z() - 8;
        int floor = CAVERN_FLOOR_Y;
        for (int dx = 0; dx < 6; dx++) {
            for (int dz = 0; dz < 6; dz++) {
                int x = ox + dx;
                int z = oz + dz;
                if (!chunkBox.contains(x, z)) continue;
                writer.trySet(new BlockPos(x, floor, z), Blocks.MYCELIUM.defaultBlockState());
                writer.trySet(new BlockPos(x, floor + 1, z),
                        random.chance(0.5) ? Blocks.RED_MUSHROOM.defaultBlockState() : Blocks.BROWN_MUSHROOM.defaultBlockState());
                if ((dx + dz) % 3 == 0) {
                    writer.trySet(new BlockPos(x, floor + 3, z), Blocks.REDSTONE_LAMP.defaultBlockState());
                    writer.trySet(new BlockPos(x, floor + 4, z), Blocks.REDSTONE_BLOCK.defaultBlockState());
                }
            }
        }
    }

    private void placeScholarNiche(SafeChunkWriter writer, BlockPos2 center, BoundingBox2 chunkBox, BlockState accent) {
        int x = center.x() - 14;
        int z = center.z() + 6;
        if (!chunkBox.expand(3).contains(BlockPos2.of(x, z))) return;
        for (int dx = 0; dx < 4; dx++) {
            for (int dz = 0; dz < 4; dz++) {
                if (!writer.inChunk(x + dx, z + dz)) continue;
                writer.trySet(new BlockPos(x + dx, CAVERN_FLOOR_Y, z + dz), accent);
                writer.trySet(new BlockPos(x + dx, CAVERN_FLOOR_Y + 1, z + dz), Blocks.AIR.defaultBlockState());
            }
        }
        writer.trySet(new BlockPos(x + 1, CAVERN_FLOOR_Y + 1, z + 1), Blocks.BOOKSHELF.defaultBlockState());
        writer.trySet(new BlockPos(x + 2, CAVERN_FLOOR_Y + 1, z + 1), Blocks.LECTERN.defaultBlockState());
    }

    private void placeMiningAnnex(SafeChunkWriter writer, BlockPos2 center, BoundingBox2 chunkBox, BlockState primary) {
        int x = center.x() + 16;
        int z = center.z() + 4;
        for (int d = 0; d < 8; d++) {
            int tx = x + d;
            if (!chunkBox.contains(tx, z)) continue;
            writer.trySet(new BlockPos(tx, CAVERN_FLOOR_Y - 1, z), primary);
            writer.trySet(new BlockPos(tx, CAVERN_FLOOR_Y, z), Blocks.AIR.defaultBlockState());
            writer.trySet(new BlockPos(tx, CAVERN_FLOOR_Y + 1, z), Blocks.AIR.defaultBlockState());
            writer.trySet(new BlockPos(tx, CAVERN_FLOOR_Y + 1, z - 1), Blocks.OAK_FENCE.defaultBlockState());
        }
        writer.trySet(new BlockPos(x + 3, CAVERN_FLOOR_Y, z), Blocks.COAL_ORE.defaultBlockState());
    }

    private void placeVerticalAccess(
            ServerLevel level,
            SafeChunkWriter writer,
            BlockPos2 center,
            BoundingBox2 chunkBox,
            BlockState primary,
            BlockState accent
    ) {
        // Controlled trade / hidden shaft near center if this chunk owns it
        if (!chunkBox.contains(center)) {
            // Still allow shaft if center's X or Z lane intersects
            if (!chunkBox.contains(center.x(), chunkBox.minZ()) && !chunkBox.contains(chunkBox.minX(), center.z())) {
                return;
            }
        }
        int x = center.x();
        int z = center.z();
        if (!writer.inChunk(x, z)) return;

        int surface = level.getHeight(Heightmap.Types.WORLD_SURFACE_WG, x, z);
        for (int y = CAVERN_FLOOR_Y + 1; y <= surface; y++) {
            writer.trySet(new BlockPos(x, y, z), Blocks.AIR.defaultBlockState());
            writer.trySet(new BlockPos(x + 1, y, z), Blocks.AIR.defaultBlockState());
            if (y % 5 == 0) {
                writer.trySet(new BlockPos(x - 1, y, z), primary);
                writer.trySet(new BlockPos(x, y, z - 1), Blocks.LADDER.defaultBlockState());
            }
        }
        // Hidden surface hatch
        writer.trySet(new BlockPos(x, surface, z), Blocks.MOSS_BLOCK.defaultBlockState());
        writer.trySet(new BlockPos(x + 1, surface, z), Blocks.OAK_TRAPDOOR.defaultBlockState());
        writer.trySet(new BlockPos(x - 1, surface + 1, z), accent);
        // Controlled trade entrance marker
        writer.trySet(new BlockPos(x + 2, surface, z), CulturalBlocks.resolve("polished_deepslate", Blocks.DEEPSLATE.defaultBlockState()));
    }

    private static BoundingBox2 chunkBox(SafeChunkWriter writer) {
        int cx = writer.chunk().getPos().x << 4;
        int cz = writer.chunk().getPos().z << 4;
        return BoundingBox2.of(cx, cz, cx + 15, cz + 15);
    }
}
