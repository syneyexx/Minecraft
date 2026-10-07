package com.livingmods.worldgen.architecture;

import com.livingmods.common.culture.CultureDefinition;
import com.livingmods.common.geo.BoundingBox2;
import com.livingmods.common.id.LotId;
import com.livingmods.common.id.StructureId;
import com.livingmods.common.model.BuildingRole;
import com.livingmods.common.model.WealthClass;
import com.livingmods.common.util.DeterministicRandom;
import com.livingmods.common.util.Hashing;
import com.livingmods.worldgen.plan.PlannedBuilding;

import java.util.ArrayList;
import java.util.List;

/**
 * Procedural building grammar: foundation through roof and interior metadata.
 */
public final class ArchitectureGrammar {
    public record InteriorMetadata(
            int floorCount,
            int roomCount,
            boolean hasBasement,
            boolean hasAttic,
            List<String> roomTags
    ) {}

    public record Blueprint(
            PlannedBuilding building,
            InteriorMetadata interior,
            List<String> wallSegments,
            List<String> windowPositions,
            String entranceFacing
    ) {}

    private final StructureRegistry registry;

    public ArchitectureGrammar() {
        this(StructureRegistry.defaultRegistry());
    }

    public ArchitectureGrammar(StructureRegistry registry) {
        this.registry = registry;
    }

    public Blueprint generate(
            long worldSeed,
            long ordinal,
            CultureDefinition culture,
            BuildingRole role,
            WealthClass wealth,
            LotId lotId,
            BoundingBox2 lotBounds,
            int entranceDirection,
            int foundationY
    ) {
        long fork = Hashing.mix(worldSeed, Hashing.mix(0x4152434849L, ordinal));
        DeterministicRandom random = new DeterministicRandom(fork);
        StructureRegistry.Template template = registry
                .pickWeighted(culture.key(), role, com.livingmods.common.model.SettlementTier.TOWN, wealth, worldSeed, ordinal)
                .orElse(registry.all().get(0));

        int rot = entranceDirection % 4;
        int w = Math.min(template.width(), lotBounds.width());
        int d = Math.min(template.depth(), lotBounds.depth());
        if (random.chance(0.3) && lotBounds.width() >= template.depth() && lotBounds.depth() >= template.width()) {
            int tmp = w;
            w = d;
            d = tmp;
            rot = (rot + 1) % 4;
        }
        w = Math.max(3, w - 2);
        d = Math.max(3, d - 2);

        int cx = lotBounds.center().x();
        int cz = lotBounds.center().z();
        int halfW = w / 2;
        int halfD = d / 2;
        int minX = Math.max(lotBounds.minX(), cx - halfW);
        int minZ = Math.max(lotBounds.minZ(), cz - halfD);
        int maxX = Math.min(lotBounds.maxX(), minX + w - 1);
        int maxZ = Math.min(lotBounds.maxZ(), minZ + d - 1);
        BoundingBox2 footprint = BoundingBox2.of(minX, minZ, maxX, maxZ);

        int floors = 1 + (wealth.ordinal() >= WealthClass.COMFORTABLE.ordinal() ? 1 : 0);
        if (role == BuildingRole.PALACE || role == BuildingRole.CASTLE_KEEP) {
            floors = Math.max(floors, 2);
        }

        boolean basement = random.chance(0.15) && foundationY > 58;
        boolean attic = culture.architecture().roofStyle() != CultureDefinition.RoofStyle.FLAT && random.chance(0.4);

        List<String> rooms = layoutFloorPlan(random, role, w, d, floors);
        List<String> walls = traceWalls(w, d, floors, culture);
        List<String> windows = placeWindows(random, w, d, floors, culture.architecture().windowDensity());
        String entrance = facingName(rot);

        int buildingSeed = (int) (fork & 0x7fffffff);
        StructureId structureId = StructureId.deterministic(worldSeed, ordinal);
        String palette = culture.architecture().primaryBlock();

        PlannedBuilding building = new PlannedBuilding(
                structureId,
                lotId,
                role,
                wealth,
                footprint,
                rot * 90,
                foundationY,
                culture.key(),
                palette,
                buildingSeed
        );

        InteriorMetadata interior = new InteriorMetadata(floors, rooms.size(), basement, attic, rooms);
        return new Blueprint(building, interior, walls, windows, entrance);
    }

    private List<String> layoutFloorPlan(DeterministicRandom random, BuildingRole role, int w, int d, int floors) {
        List<String> rooms = new ArrayList<>();
        rooms.add("entry");
        switch (role) {
            case HOUSE, TOWNHOUSE, MANOR, FARMHOUSE -> {
                rooms.add("hall");
                rooms.add(random.chance(0.5) ? "kitchen" : "hearth");
                rooms.add("bedroom");
                if (w >= 9) rooms.add("storage");
            }
            case SHOP, MARKET_STALL, MARKET_HALL -> {
                rooms.add("sales_floor");
                rooms.add("storage");
            }
            case TEMPLE -> {
                rooms.add("nave");
                rooms.add("sanctuary");
            }
            case BARRACKS, GUARDHOUSE -> {
                rooms.add("barracks_room");
                rooms.add("armory");
            }
            case WORKSHOP, SMITHY, SAWMILL, MILL -> {
                rooms.add("work_floor");
                rooms.add("materials");
            }
            case PALACE, CASTLE_KEEP -> {
                rooms.add("throne_room");
                rooms.add("council");
                rooms.add("guard_post");
                rooms.add("vault");
            }
            default -> rooms.add("main");
        }
        for (int f = 1; f < floors; f++) {
            rooms.add("upper_" + f);
        }
        return rooms;
    }

    private List<String> traceWalls(int w, int d, int floors, CultureDefinition culture) {
        List<String> walls = new ArrayList<>();
        String mat = culture.architecture().secondaryBlock();
        for (int f = 0; f < floors; f++) {
            walls.add("perimeter:" + w + "x" + d + "@" + f + ":" + mat);
        }
        return walls;
    }

    private List<String> placeWindows(DeterministicRandom random, int w, int d, int floors, double density) {
        List<String> windows = new ArrayList<>();
        int slots = (w + d) * 2 * floors;
        int count = (int) Math.max(1, Math.round(slots * density * 0.15));
        for (int i = 0; i < count; i++) {
            int side = random.nextInt(4);
            int along = random.nextInt(side % 2 == 0 ? w : d);
            int floor = random.nextInt(floors);
            windows.add("win:" + side + ":" + along + "@" + floor);
        }
        return windows;
    }

    private static String facingName(int entranceDirection) {
        return switch (entranceDirection) {
            case 0 -> "north";
            case 1 -> "east";
            case 2 -> "south";
            default -> "west";
        };
    }
}
