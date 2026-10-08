package com.livingmods.worldgen.architecture;

import com.livingmods.common.culture.CultureDefinition;
import com.livingmods.common.geo.BoundingBox2;
import com.livingmods.common.id.DistrictId;
import com.livingmods.common.id.LotId;
import com.livingmods.common.id.SettlementId;
import com.livingmods.common.id.StructureId;
import com.livingmods.common.model.BuildingRole;
import com.livingmods.common.model.SettlementTier;
import com.livingmods.common.model.WealthClass;
import com.livingmods.common.util.DeterministicRandom;
import com.livingmods.common.util.Hashing;
import com.livingmods.worldgen.plan.PlannedBuilding;
import com.livingmods.worldgen.structure.MlsStructureFormat;
import com.livingmods.worldgen.structure.StructureAsset;
import com.livingmods.worldgen.structure.StructureCatalog;
import com.livingmods.worldgen.structure.StructureCatalogHolder;
import com.livingmods.worldgen.structure.StructureSizeClass;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Building grammar: prefers data-driven {@link StructureAsset} selection with real dimensions,
 * falling back to procedural {@link StructureRegistry} templates.
 * Selected {@code assetId} is persisted on {@link PlannedBuilding} — never re-rolled at materialization.
 *
 * <p>M6.1: asset-first planning — select asset before committing lot bounds when possible.
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

    /** Selected asset + rotation + clearance-padded footprint size for lot reservation. */
    public record AssetPlacement(
            StructureAsset asset,
            int rotationSteps,
            int width,
            int depth,
            int clearance,
            String entranceFacing
    ) {
        public int lotWidth() {
            return width + clearance * 2;
        }

        public int lotDepth() {
            return depth + clearance * 2;
        }
    }

    private final StructureRegistry registry;
    private final StructureCatalog catalog;

    public ArchitectureGrammar() {
        this(StructureRegistry.defaultRegistry(), StructureCatalogHolder.ensureLoaded());
    }

    public ArchitectureGrammar(StructureRegistry registry) {
        this(registry, StructureCatalogHolder.ensureLoaded());
    }

    public ArchitectureGrammar(StructureRegistry registry, StructureCatalog catalog) {
        this.registry = registry;
        this.catalog = catalog == null ? StructureCatalog.empty() : catalog;
    }

    public StructureCatalog catalog() {
        return catalog;
    }

    /**
     * Asset-first selection: choose a production asset for the role before lot commitment.
     */
    public Optional<AssetPlacement> selectAssetPlacement(
            long worldSeed,
            long ordinal,
            CultureDefinition culture,
            BuildingRole role,
            WealthClass wealth,
            SettlementTier tier,
            int preferredEntranceDirection,
            List<String> recentAssetIds,
            boolean coastalContext,
            boolean undergroundContext
    ) {
        StructureSizeClass sizeHint = sizeHintForRole(role, wealth, tier);
        List<StructureAsset> candidates = catalog.selectCandidates(
                culture.key(), role, tier, wealth, sizeHint, coastalContext, undergroundContext);
        if (candidates.isEmpty()) {
            return Optional.empty();
        }
        Optional<StructureAsset> picked = catalog.select(
                culture.key(), role, tier, wealth, sizeHint, worldSeed, ordinal, recentAssetIds);
        if (picked.isEmpty()) {
            picked = Optional.of(candidates.get(Math.floorMod(
                    Hashing.mix(worldSeed, ordinal), candidates.size())));
        }
        StructureAsset asset = picked.get();
        int rot = chooseRotationFit(asset, preferredEntranceDirection % 4);
        int[] dims = MlsStructureFormat.rotatedDimensions(asset.width(), asset.depth(), rot);
        String facing = MlsStructureFormat.rotateFacing(asset.entranceFacing(), rot);
        return Optional.of(new AssetPlacement(
                asset, rot, dims[0], dims[1], Math.max(0, asset.requiredClearance()), facing));
    }

    public Blueprint generate(
            long worldSeed,
            long ordinal,
            CultureDefinition culture,
            BuildingRole role,
            WealthClass wealth,
            LotId lotId,
            SettlementId settlementId,
            DistrictId districtId,
            BoundingBox2 lotBounds,
            int entranceDirection,
            int foundationY
    ) {
        return generate(worldSeed, ordinal, culture, role, wealth, SettlementTier.TOWN,
                lotId, settlementId, districtId, lotBounds, entranceDirection, foundationY, List.of());
    }

    public Blueprint generate(
            long worldSeed,
            long ordinal,
            CultureDefinition culture,
            BuildingRole role,
            WealthClass wealth,
            SettlementTier tier,
            LotId lotId,
            SettlementId settlementId,
            DistrictId districtId,
            BoundingBox2 lotBounds,
            int entranceDirection,
            int foundationY,
            List<String> recentAssetIds
    ) {
        long fork = Hashing.mix(worldSeed, Hashing.mix(0x4152434849L, ordinal));
        DeterministicRandom random = new DeterministicRandom(fork);

        StructureSizeClass sizeHint = sizeHintFor(role, wealth, tier, lotBounds);
        Optional<StructureAsset> assetOpt = catalog.select(
                culture.key(), role, tier, wealth, sizeHint, worldSeed, ordinal, recentAssetIds);

        int rot = entranceDirection % 4;
        int w;
        int d;
        int height;
        String assetId = "";
        String archetype = "";
        int floors;
        int capacity;
        int workSlots;
        int residentialSlots;
        String entrance = facingName(rot);

        if (assetOpt.isPresent() && fitsLot(assetOpt.get(), lotBounds)) {
            StructureAsset asset = assetOpt.get();
            rot = chooseRotationFit(asset, entranceDirection % 4, lotBounds);
            int[] dims = MlsStructureFormat.rotatedDimensions(asset.width(), asset.depth(), rot);
            w = dims[0];
            d = dims[1];
            if (w <= lotBounds.width() && d <= lotBounds.depth()) {
                assetId = asset.assetId();
                archetype = asset.archetype();
                height = asset.height();
                entrance = MlsStructureFormat.rotateFacing(asset.entranceFacing(), rot);
                floors = Math.max(1, Math.min(4, (height + 2) / 4));
                capacity = asset.occupationCapacityHint() > 0
                        ? asset.occupationCapacityHint()
                        : capacityFor(role, wealth, floors, 4)[0];
                workSlots = asset.workSlotsHint() > 0 ? asset.workSlotsHint() : capacityFor(role, wealth, floors, 4)[1];
                residentialSlots = asset.residentialSlotsHint() > 0
                        ? asset.residentialSlotsHint()
                        : capacityFor(role, wealth, floors, 4)[2];
            } else {
                assetId = "";
                archetype = "";
                height = 0;
                capacity = 0;
                workSlots = 0;
                residentialSlots = 0;
                floors = 0;
                w = 0;
                d = 0;
            }
        } else {
            assetId = "";
            archetype = "";
            height = 0;
            capacity = 0;
            workSlots = 0;
            residentialSlots = 0;
            floors = 0;
            w = 0;
            d = 0;
        }

        if (assetId.isEmpty()) {
            StructureRegistry.Template template = registry
                    .pickWeighted(culture.key(), role, tier, wealth, worldSeed, ordinal)
                    .orElse(registry.all().get(0));
            w = Math.min(template.width(), lotBounds.width());
            d = Math.min(template.depth(), lotBounds.depth());
            if (random.chance(0.3) && lotBounds.width() >= template.depth() && lotBounds.depth() >= template.width()) {
                int tmp = w;
                w = d;
                d = tmp;
                rot = (rot + 1) % 4;
            }
            w = Math.max(5, w - 2);
            d = Math.max(5, d - 2);
            floors = 1 + (wealth.ordinal() >= WealthClass.COMFORTABLE.ordinal() ? 1 : 0);
            if (role == BuildingRole.PALACE || role == BuildingRole.CASTLE_KEEP) {
                floors = Math.max(floors, 2);
            }
            if (role == BuildingRole.TOWER) {
                floors = Math.max(floors, 3);
            }
            int[] slots = capacityFor(role, wealth, floors, 4);
            capacity = slots[0];
            workSlots = slots[1];
            residentialSlots = slots[2];
            height = floors * 4 + 2;
            entrance = facingName(rot);
        }

        // Align footprint so entrance faces street: offset toward entrance edge of lot.
        BoundingBox2 footprint = alignFootprint(lotBounds, w, d, rot);

        boolean basement = assetId.isEmpty() && random.chance(0.15) && foundationY > 58;
        boolean attic = assetId.isEmpty()
                && culture.architecture().roofStyle() != CultureDefinition.RoofStyle.FLAT
                && random.chance(0.4);

        List<String> rooms = layoutFloorPlan(random, role, w, d, floors);
        List<String> walls = traceWalls(w, d, floors, culture);
        List<String> windows = placeWindows(random, w, d, floors, culture.architecture().windowDensity());

        int buildingSeed = (int) (fork & 0x7fffffff);
        StructureId structureId = StructureId.deterministic(worldSeed, ordinal);
        String palette = culture.architecture().primaryBlock();

        PlannedBuilding building = new PlannedBuilding(
                structureId,
                lotId,
                settlementId,
                districtId,
                role,
                wealth,
                footprint,
                rot * 90,
                foundationY,
                culture.key(),
                palette,
                buildingSeed,
                floors,
                basement,
                attic,
                rooms,
                walls,
                windows,
                entrance,
                capacity,
                workSlots,
                residentialSlots,
                assetId,
                archetype
        );

        InteriorMetadata interior = new InteriorMetadata(floors, rooms.size(), basement, attic, rooms);
        return new Blueprint(building, interior, walls, windows, entrance);
    }

    /** Build from a pre-selected asset placement into an already-reserved lot. */
    public Blueprint generateFromPlacement(
            long worldSeed,
            long ordinal,
            CultureDefinition culture,
            BuildingRole role,
            WealthClass wealth,
            SettlementTier tier,
            LotId lotId,
            SettlementId settlementId,
            DistrictId districtId,
            BoundingBox2 lotBounds,
            int foundationY,
            AssetPlacement placement
    ) {
        StructureAsset asset = placement.asset();
        int rot = placement.rotationSteps();
        int w = placement.width();
        int d = placement.depth();
        BoundingBox2 footprint = alignFootprint(lotBounds, w, d, rot);
        int floors = Math.max(1, Math.min(4, (asset.height() + 2) / 4));
        int[] slots = capacityFor(role, wealth, floors, 4);
        int capacity = asset.occupationCapacityHint() > 0 ? asset.occupationCapacityHint() : slots[0];
        int workSlots = asset.workSlotsHint() > 0 ? asset.workSlotsHint() : slots[1];
        int residentialSlots = asset.residentialSlotsHint() > 0 ? asset.residentialSlotsHint() : slots[2];
        long fork = Hashing.mix(worldSeed, Hashing.mix(0x4152434849L, ordinal));
        DeterministicRandom random = new DeterministicRandom(fork);
        List<String> rooms = layoutFloorPlan(random, role, w, d, floors);
        List<String> walls = traceWalls(w, d, floors, culture);
        List<String> windows = placeWindows(random, w, d, floors, culture.architecture().windowDensity());
        PlannedBuilding building = new PlannedBuilding(
                StructureId.deterministic(worldSeed, ordinal),
                lotId, settlementId, districtId, role, wealth, footprint,
                rot * 90, foundationY, culture.key(), culture.architecture().primaryBlock(),
                (int) (fork & 0x7fffffff), floors, false, false, rooms, walls, windows,
                placement.entranceFacing(), capacity, workSlots, residentialSlots,
                asset.assetId(), asset.archetype()
        );
        return new Blueprint(building, new InteriorMetadata(floors, rooms.size(), false, false, rooms),
                walls, windows, placement.entranceFacing());
    }

    private static BoundingBox2 alignFootprint(BoundingBox2 lot, int w, int d, int rot) {
        int minX;
        int minZ;
        // Push building toward the entrance edge so door meets street.
        switch (rot & 3) {
            case 0 -> { // north-facing entrance → place near minZ
                minX = lot.minX() + Math.max(0, (lot.width() - w) / 2);
                minZ = lot.minZ();
            }
            case 1 -> { // east
                minX = lot.maxX() - w + 1;
                minZ = lot.minZ() + Math.max(0, (lot.depth() - d) / 2);
            }
            case 2 -> { // south
                minX = lot.minX() + Math.max(0, (lot.width() - w) / 2);
                minZ = lot.maxZ() - d + 1;
            }
            default -> { // west
                minX = lot.minX();
                minZ = lot.minZ() + Math.max(0, (lot.depth() - d) / 2);
            }
        }
        minX = Math.max(lot.minX(), Math.min(lot.maxX() - w + 1, minX));
        minZ = Math.max(lot.minZ(), Math.min(lot.maxZ() - d + 1, minZ));
        return BoundingBox2.of(minX, minZ, minX + w - 1, minZ + d - 1);
    }

    private static boolean fitsLot(StructureAsset asset, BoundingBox2 lot) {
        return (asset.width() <= lot.width() && asset.depth() <= lot.depth())
                || (asset.depth() <= lot.width() && asset.width() <= lot.depth());
    }

    /**
     * Choose an allowed rotation that aligns entrance toward preferred street direction
     * and whose rotated dims fit the lot when provided.
     */
    public static int chooseRotationFit(StructureAsset asset, int preferredEntranceDir) {
        return chooseRotationFit(asset, preferredEntranceDir, null);
    }

    public static int chooseRotationFit(StructureAsset asset, int preferredEntranceDir, BoundingBox2 lot) {
        int assetFacing = facingIndex(asset.entranceFacing());
        int desired = ((preferredEntranceDir % 4) + 4) % 4;
        int ideal = (desired - assetFacing + 4) % 4;
        List<Integer> allowedSteps = new ArrayList<>();
        for (int deg : asset.allowedRotations()) {
            allowedSteps.add(((deg / 90) % 4 + 4) % 4);
        }
        if (allowedSteps.isEmpty()) {
            allowedSteps.addAll(List.of(0, 1, 2, 3));
        }
        // Prefer ideal, then other allowed that fit.
        List<Integer> order = new ArrayList<>();
        order.add(ideal);
        for (int s : allowedSteps) {
            if (!order.contains(s)) order.add(s);
        }
        for (int s : order) {
            if (!allowedSteps.contains(s)) continue;
            int[] dims = MlsStructureFormat.rotatedDimensions(asset.width(), asset.depth(), s);
            if (lot == null || (dims[0] <= lot.width() && dims[1] <= lot.depth())) {
                return s;
            }
        }
        // Last resort: any allowed rotation (caller may fall back).
        return allowedSteps.get(0);
    }

    private static StructureSizeClass sizeHintForRole(BuildingRole role, WealthClass wealth, SettlementTier tier) {
        return switch (role) {
            case WELL, WAYSTONE, MARKET_STALL -> StructureSizeClass.TINY;
            case HOUSE, FARMHOUSE, SHOP, GUARDHOUSE, CLINIC ->
                    wealth == WealthClass.POOR || tier == SettlementTier.HAMLET
                            ? StructureSizeClass.SMALL : StructureSizeClass.SMALL;
            case TOWNHOUSE, TAVERN, SMITHY, WORKSHOP, SCHOOL, TOWER, GATEHOUSE, MILL, BARN, SAWMILL ->
                    StructureSizeClass.MEDIUM;
            case MANOR, TEMPLE, MARKET_HALL, WAREHOUSE, BARRACKS, PRISON, DOCK, BRIDGE ->
                    StructureSizeClass.MEDIUM;
            case PALACE, CASTLE_KEEP, MONUMENT -> StructureSizeClass.LARGE;
            default -> StructureSizeClass.SMALL;
        };
    }

    private static StructureSizeClass sizeHintFor(
            BuildingRole role, WealthClass wealth, SettlementTier tier, BoundingBox2 lot
    ) {
        // Prefer role-based hint; only shrink when lot is tiny.
        StructureSizeClass roleHint = sizeHintForRole(role, wealth, tier);
        int maxLot = Math.max(lot.width(), lot.depth());
        if (maxLot <= 8) return StructureSizeClass.TINY;
        if (maxLot <= 12 && roleHint.ordinal() > StructureSizeClass.SMALL.ordinal()) {
            return StructureSizeClass.SMALL;
        }
        return roleHint;
    }

    /** Returns [capacity, workSlots, residentialSlots]. */
    public static int[] capacityFor(BuildingRole role, WealthClass wealth, int floors, int rooms) {
        int wealthBoost = Math.max(0, wealth.ordinal());
        return switch (role) {
            case HOUSE, TOWNHOUSE, FARMHOUSE -> new int[]{2 + wealthBoost, 0, 2 + wealthBoost};
            case MANOR -> new int[]{6 + wealthBoost, 1, 6 + wealthBoost};
            case PALACE, CASTLE_KEEP -> new int[]{20 + floors * 4, 8, 12};
            case SMITHY, WORKSHOP, SAWMILL, MILL -> new int[]{4, 3 + wealthBoost, 1};
            case TAVERN -> new int[]{12 + floors * 2, 4, 2};
            case GUARDHOUSE, BARRACKS -> new int[]{6 + floors * 2, 2, 4 + floors};
            case SCHOOL -> new int[]{10 + rooms, 4, 0};
            case CLINIC -> new int[]{6, 3, 2};
            case SHOP, MARKET_STALL, MARKET_HALL -> new int[]{4, 3, 0};
            case TEMPLE -> new int[]{16, 2, 1};
            case WAREHOUSE, BARN -> new int[]{2, 2, 0};
            case DOCK -> new int[]{4, 3, 0};
            default -> new int[]{Math.max(2, rooms), Math.max(1, rooms / 2), floors};
        };
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
                rooms.add("living");
            }
            case SHOP, MARKET_STALL, MARKET_HALL -> {
                rooms.add("sales_floor");
                rooms.add("storage");
            }
            case TAVERN -> {
                rooms.add("bar");
                rooms.add("tables");
                rooms.add("kitchen");
                if (floors > 1) rooms.add("rooms_upper");
            }
            case TEMPLE -> {
                rooms.add("nave");
                rooms.add("sanctuary");
            }
            case BARRACKS, GUARDHOUSE -> {
                rooms.add("barracks_room");
                rooms.add("armory");
                rooms.add("facilities");
                rooms.add("beds");
            }
            case WORKSHOP, SMITHY, SAWMILL, MILL -> {
                rooms.add("work_floor");
                rooms.add("forge");
                rooms.add("materials");
                rooms.add("storage");
            }
            case SCHOOL -> {
                rooms.add("teaching");
                rooms.add("lecterns");
                rooms.add("storage");
            }
            case CLINIC -> {
                rooms.add("treatment");
                rooms.add("storage");
                rooms.add("recovery");
            }
            case PALACE, CASTLE_KEEP -> {
                rooms.add("throne_room");
                rooms.add("court");
                rooms.add("council");
                rooms.add("private");
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
            walls.add("corners@" + f + ":" + culture.architecture().accentBlock());
        }
        return walls;
    }

    private List<String> placeWindows(DeterministicRandom random, int w, int d, int floors, double density) {
        List<String> windows = new ArrayList<>();
        int slots = (w + d) * 2 * floors;
        int count = (int) Math.max(1, Math.round(slots * density * 0.15));
        for (int i = 0; i < count; i++) {
            int side = random.nextInt(4);
            int along = random.nextInt(Math.max(1, side % 2 == 0 ? w : d));
            int floor = random.nextInt(Math.max(1, floors));
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

    private static int facingIndex(String name) {
        if (name == null) return 2;
        return switch (name.toLowerCase()) {
            case "north" -> 0;
            case "east" -> 1;
            case "south" -> 2;
            default -> 3;
        };
    }
}
