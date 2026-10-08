package com.livingmods.worldgen.planner;

import com.livingmods.common.config.LivingModsConfig;
import com.livingmods.common.culture.CultureDefinition;
import com.livingmods.common.culture.CultureRegistry;
import com.livingmods.common.geo.BlockPos2;
import com.livingmods.common.geo.BoundingBox2;
import com.livingmods.common.id.DistrictId;
import com.livingmods.common.id.LotId;
import com.livingmods.common.model.BuildingRole;
import com.livingmods.common.model.DistrictType;
import com.livingmods.common.model.SettlementRole;
import com.livingmods.common.model.SettlementTier;
import com.livingmods.common.model.WealthClass;
import com.livingmods.common.util.DeterministicRandom;
import com.livingmods.common.util.Hashing;
import com.livingmods.worldgen.architecture.ArchitectureGrammar;
import com.livingmods.worldgen.plan.PlannedBuilding;
import com.livingmods.worldgen.plan.PlannedDistrict;
import com.livingmods.worldgen.plan.PlannedLot;
import com.livingmods.worldgen.plan.PlannedRoad;
import com.livingmods.worldgen.plan.PlannedSettlement;
import com.livingmods.worldgen.terrain.TerrainProvider;
import com.livingmods.worldgen.terrain.TerrainSample;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;

/**
 * Urban hierarchy: boundary → main streets → districts → landmarks → asset-sized lots → buildings.
 * District geometry follows street corridors, terrain, waterfront, gates, and castle adjacency —
 * not only rectangular pie slices.
 *
 * <p>M6.1 planning order: role → asset → rotation → real dimensions → reserve lot → entrance align.
 */
public final class UrbanPlanner {
    private final LivingModsConfig config;
    private final CultureRegistry cultures;
    private final TerrainProvider terrain;
    private final ArchitectureGrammar grammar;

    public UrbanPlanner(CultureRegistry cultures, TerrainProvider terrain) {
        this(LivingModsConfig.defaults(), cultures, terrain);
    }

    public UrbanPlanner(LivingModsConfig config, CultureRegistry cultures, TerrainProvider terrain) {
        this.config = config;
        this.cultures = cultures;
        this.terrain = terrain;
        this.grammar = new ArchitectureGrammar();
    }

    public List<PlannedSettlement> planUrbanLayouts(long seed, List<PlannedSettlement> settlements) {
        return planUrbanLayouts(seed, settlements, List.of());
    }

    public List<PlannedSettlement> planUrbanLayouts(
            long seed, List<PlannedSettlement> settlements, List<PlannedRoad> roads
    ) {
        List<PlannedSettlement> out = new ArrayList<>();
        long base = Hashing.mix(seed, 0x555242414EL);

        for (int i = 0; i < settlements.size(); i++) {
            PlannedSettlement s = settlements.get(i);
            CultureDefinition culture = cultures.get(s.cultureKey())
                    .orElseThrow(() -> new IllegalStateException("culture " + s.cultureKey()));
            DeterministicRandom random = new DeterministicRandom(Hashing.mix(base, i));
            // All tiers use the catalog — hamlets/villages get a compact rural layout.
            out.add(layoutSettlement(seed, i, s, culture, roads, random));
        }
        return out;
    }

    private PlannedSettlement layoutSettlement(
            long seed, int index, PlannedSettlement s, CultureDefinition culture,
            List<PlannedRoad> roads, DeterministicRandom random
    ) {
        int radius = SettlementFootprint.footprintRadius(
                s.tier(), s.plannedPopulation(), culture, config.civilizationDensityScale()
        );
        BoundingBox2 bounds = BoundingBox2.around(s.center(), radius);

        List<BlockPos2> roadApproaches = incomingRoadEndpoints(s, roads, radius);
        List<BlockPos2> wallPath = List.of();
        List<BlockPos2> gates = List.of();
        if (s.walls()) {
            wallPath = wallRing(s.center(), radius, terrain);
            gates = gatePositions(wallPath, roadApproaches, random);
        } else if (!roadApproaches.isEmpty()) {
            gates = new ArrayList<>(roadApproaches.subList(0, Math.min(4, roadApproaches.size())));
        }

        // Main streets: center spokes + gate→center corridors (road-street integration).
        List<BlockPos2> streets = majorStreets(s.center(), radius, culture, gates, random);
        List<PlannedDistrict> districts = planDistricts(seed, index, s, bounds, radius, culture, gates, random);

        List<PlannedLot> lots = new ArrayList<>();
        List<PlannedBuilding> buildings = new ArrayList<>();
        java.util.Set<Long> occupiedLotCells = new java.util.HashSet<>();
        List<String> recentAssetIds = new ArrayList<>();
        int lotOrdinal = 0;
        int buildingOrdinal = 0;
        int housingTarget = SettlementFootprint.housingBuildingTarget(s.plannedPopulation(), culture);
        int housingBuilt = 0;
        boolean coastal = waterfront(s.center(), radius) || s.role() == SettlementRole.PORT;
        boolean underground = s.underground() || culture.underground();

        // 1) Landmark-first: reserve major anchors using real asset dimensions.
        for (PlannedDistrict district : districts) {
            List<BuildingRole> landmarks = landmarkRolesFor(district.type(), s);
            for (BuildingRole landmarkRole : landmarks) {
                if (buildingOrdinal > 80) break;
                WealthClass wealth = wealthFor(district.type(), s.tier());
                int entranceDir = nearestStreetDirection(district.bounds().center(), streets);
                OptionalPlacement placed = placeAssetFirst(
                        seed, index, buildingOrdinal, s, culture, district, landmarkRole, wealth,
                        entranceDir, coastal, underground, recentAssetIds, occupiedLotCells, streets, random
                );
                if (placed != null) {
                    lots.add(placed.lot());
                    buildings.add(placed.building());
                    recentAssetIds.add(placed.building().assetId());
                    lotOrdinal++;
                    buildingOrdinal++;
                }
            }
        }

        // 2) Fill remaining district area with asset-sized lots (role → asset → lot).
        for (PlannedDistrict district : districts) {
            int fillBudget = fillBudgetFor(district.type(), s.tier());
            for (int n = 0; n < fillBudget; n++) {
                BuildingRole role = pickFillRole(district.type(), s, random);
                if (isHousing(role) && housingBuilt >= housingTarget && random.chance(0.55)) {
                    continue;
                }
                // Skip roles already satisfied as unique landmarks in this district.
                if (isLandmarkRole(role) && districtHasRole(buildings, district, role)) {
                    continue;
                }
                WealthClass wealth = wealthFor(district.type(), s.tier());
                int entranceDir = random.nextInt(4);
                OptionalPlacement placed = placeAssetFirst(
                        seed, index, buildingOrdinal, s, culture, district, role, wealth,
                        entranceDir, coastal, underground, recentAssetIds, occupiedLotCells, streets, random
                );
                if (placed == null) continue;
                lots.add(placed.lot());
                buildings.add(placed.building());
                if (placed.building().usesImportedAsset()) {
                    recentAssetIds.add(placed.building().assetId());
                }
                lotOrdinal++;
                buildingOrdinal++;
                if (isHousing(role)) housingBuilt++;
            }
        }

        // Special placement routes (agriculture, mining, walls/gates, ruins, bridges).
        buildingOrdinal = placeSpecialRoutes(
                seed, index, s, culture, districts, lots, buildings, recentAssetIds,
                occupiedLotCells, streets, gates, wallPath, coastal, underground, random, buildingOrdinal
        );

        // Ensure housing capacity target when lots under-produced.
        housingBuilt = ensureHousingCapacity(
                seed, index, s, culture, districts, lots, buildings, housingTarget, housingBuilt,
                occupiedLotCells, random
        );

        List<BlockPos2> secondary = secondaryStreets(streets, bounds, culture, random);
        streets = new ArrayList<>(streets);
        streets.addAll(secondary);

        return new PlannedSettlement(
                s.id(), s.name(), s.tier(), s.role(), s.center(), bounds,
                s.ownerKingdom(), s.cultureId(), s.cultureKey(), s.capital(), s.walls(),
                s.underground(), s.plannedPopulation(),
                districts, lots, buildings, streets, wallPath, gates
        );
    }

    private int ensureHousingCapacity(
            long seed, int index, PlannedSettlement s, CultureDefinition culture,
            List<PlannedDistrict> districts, List<PlannedLot> lots, List<PlannedBuilding> buildings,
            int housingTarget, int housingBuilt, java.util.Set<Long> occupied,
            DeterministicRandom random
    ) {
        if (housingBuilt >= housingTarget || districts.isEmpty()) return housingBuilt;
        PlannedDistrict residential = districts.stream()
                .filter(d -> d.type() == DistrictType.COMMON_RESIDENTIAL
                        || d.type() == DistrictType.WEALTHY_RESIDENTIAL)
                .findFirst()
                .orElse(districts.get(districts.size() - 1));
        List<String> recent = new ArrayList<>();
        for (PlannedBuilding existing : buildings) {
            if (existing.usesImportedAsset()) recent.add(existing.assetId());
        }
        int guard = 0;
        while (housingBuilt < housingTarget && guard++ < housingTarget * 2) {
            OptionalPlacement p = placeAssetFirst(
                    seed, index, buildings.size() + 50_000, s, culture, residential,
                    BuildingRole.HOUSE, wealthFor(residential.type(), s.tier()), 0,
                    false, s.underground(), recent, occupied, List.of(), random
            );
            if (p == null) break;
            lots.add(p.lot());
            buildings.add(p.building());
            if (p.building().usesImportedAsset()) recent.add(p.building().assetId());
            housingBuilt++;
        }
        return housingBuilt;
    }

    private static boolean isHousing(BuildingRole role) {
        return role == BuildingRole.HOUSE || role == BuildingRole.TOWNHOUSE
                || role == BuildingRole.MANOR || role == BuildingRole.FARMHOUSE;
    }

    private List<BlockPos2> incomingRoadEndpoints(PlannedSettlement s, List<PlannedRoad> roads, int radius) {
        List<BlockPos2> endpoints = new ArrayList<>();
        for (PlannedRoad road : roads) {
            boolean connects = road.fromSettlement().filter(s.id()::equals).isPresent()
                    || road.toSettlement().filter(s.id()::equals).isPresent();
            if (!connects || road.path().isEmpty()) continue;
            BlockPos2 start = road.path().get(0);
            BlockPos2 end = road.path().get(road.path().size() - 1);
            BlockPos2 nearer = start.distanceTo(s.center()) <= end.distanceTo(s.center()) ? start : end;
            // Project onto settlement boundary ring.
            double dx = nearer.x() - s.center().x();
            double dz = nearer.z() - s.center().z();
            double len = Math.hypot(dx, dz);
            if (len < 1) continue;
            int gx = s.center().x() + (int) (dx / len * radius);
            int gz = s.center().z() + (int) (dz / len * radius);
            endpoints.add(BlockPos2.of(gx, gz));
        }
        return endpoints;
    }

    private List<PlannedDistrict> planDistricts(
            long seed, int index, PlannedSettlement s, BoundingBox2 bounds, int r,
            CultureDefinition culture, List<BlockPos2> gates, DeterministicRandom random
    ) {
        List<PlannedDistrict> districts = new ArrayList<>();
        int cx = s.center().x();
        int cz = s.center().z();
        int d = 0;

        // Castle / historic core near center, slightly offset away from heaviest gate traffic.
        BlockPos2 castleCenter = s.center();
        if (!gates.isEmpty()) {
            BlockPos2 gate = gates.get(0);
            castleCenter = BlockPos2.of(
                    cx - (gate.x() - cx) / 5,
                    cz - (gate.z() - cz) / 5
            );
        }

        if (s.capital() || s.tier() == SettlementTier.CAPITAL) {
            districts.add(district(seed, index, d++, s, DistrictType.CASTLE,
                    boxAround(castleCenter, r / 4)));
            districts.add(district(seed, index, d++, s, DistrictType.GOVERNMENT,
                    boxAround(BlockPos2.of(cx - r / 8, cz), r / 5)));
            districts.add(district(seed, index, d++, s, DistrictType.MARKET,
                    boxAround(s.center(), r / 6)));
            districts.add(district(seed, index, d++, s, DistrictType.WEALTHY_RESIDENTIAL,
                    annularSector(s.center(), r / 5, r / 2, 0.1, 1.2, random)));
            districts.add(district(seed, index, d++, s, DistrictType.COMMON_RESIDENTIAL,
                    annularSector(s.center(), r / 4, r * 9 / 10, 1.4, 4.5, random)));
            districts.add(district(seed, index, d++, s, DistrictType.MILITARY,
                    gateAdjacentBox(s.center(), gates, r, random)));
            districts.add(district(seed, index, d++, s, DistrictType.RELIGIOUS,
                    annularSector(s.center(), r / 6, r / 2, 4.0, 5.2, random)));
        } else if (s.tier() == SettlementTier.CITY) {
            districts.add(district(seed, index, d++, s, DistrictType.HISTORIC_CENTER,
                    boxAround(s.center(), r / 3)));
            districts.add(district(seed, index, d++, s, DistrictType.COMMERCIAL,
                    corridorAlongStreets(s.center(), gates, r / 4)));
            districts.add(district(seed, index, d++, s, DistrictType.COMMON_RESIDENTIAL,
                    annularSector(s.center(), r / 4, r * 9 / 10, 0.5, 5.5, random)));
            if (s.role() == SettlementRole.PORT || waterfront(s.center(), r)) {
                districts.add(district(seed, index, d++, s, DistrictType.DOCKS,
                        waterfrontBox(s.center(), r, random)));
            }
        } else if (s.tier() == SettlementTier.TOWN) {
            districts.add(district(seed, index, d++, s, DistrictType.PLAZA,
                    boxAround(s.center(), r / 5)));
            districts.add(district(seed, index, d++, s, DistrictType.CRAFTSMEN,
                    annularSector(s.center(), r / 5, r * 4 / 5, 0, Math.PI * 2, random)));
            districts.add(district(seed, index, d++, s, DistrictType.COMMON_RESIDENTIAL,
                    annularSector(s.center(), r / 4, r * 9 / 10, 0.5, 5.5, random)));
            if (s.role() == SettlementRole.MILITARY) {
                districts.add(district(seed, index, d++, s, DistrictType.MILITARY,
                        gateAdjacentBox(s.center(), gates, r, random)));
            }
            if (s.role() == SettlementRole.PORT || waterfront(s.center(), r)) {
                districts.add(district(seed, index, d++, s, DistrictType.DOCKS,
                        waterfrontBox(s.center(), r, random)));
            }
        } else {
            // Hamlet / village — compact rural layout with agriculture.
            districts.add(district(seed, index, d++, s, DistrictType.PLAZA,
                    boxAround(s.center(), Math.max(8, r / 4))));
            districts.add(district(seed, index, d++, s, DistrictType.COMMON_RESIDENTIAL,
                    annularSector(s.center(), r / 6, r * 4 / 5, 0, Math.PI * 2, random)));
            districts.add(district(seed, index, d++, s, DistrictType.CRAFTSMEN,
                    annularSector(s.center(), r / 5, r * 3 / 4, 2.0, 4.0, random)));
            if (s.role() == SettlementRole.MINING || "ironvale".equals(s.cultureKey())) {
                districts.add(district(seed, index, d++, s, DistrictType.CRAFTSMEN,
                        boxAround(BlockPos2.of(s.center().x() + r / 3, s.center().z()), r / 5)));
            }
        }

        List<PlannedDistrict> clamped = new ArrayList<>();
        for (PlannedDistrict district : districts) {
            BoundingBox2 box = clamp(district.bounds(), bounds);
            if (box.width() >= 4 && box.depth() >= 4) {
                clamped.add(new PlannedDistrict(district.id(), district.settlementId(), district.type(), box));
            }
        }
        return clamped;
    }

    private boolean waterfront(BlockPos2 center, int radius) {
        for (int i = 0; i < 8; i++) {
            double a = i * Math.PI / 4;
            int x = center.x() + (int) (Math.cos(a) * radius * 0.8);
            int z = center.z() + (int) (Math.sin(a) * radius * 0.8);
            TerrainSample s = terrain.sample(x, z);
            if (s.water() || s.coastal() || s.river()) return true;
        }
        return false;
    }

    private BoundingBox2 waterfrontBox(BlockPos2 center, int r, DeterministicRandom random) {
        for (int i = 0; i < 8; i++) {
            double a = i * Math.PI / 4 + random.nextDouble() * 0.2;
            int x = center.x() + (int) (Math.cos(a) * r * 0.7);
            int z = center.z() + (int) (Math.sin(a) * r * 0.7);
            if (terrain.coast(x, z) || terrain.waterPresence(x, z) || terrain.river(x, z)) {
                return boxAround(BlockPos2.of(x, z), r / 4);
            }
        }
        return boxAround(BlockPos2.of(center.x() + r / 2, center.z()), r / 4);
    }

    private BoundingBox2 gateAdjacentBox(BlockPos2 center, List<BlockPos2> gates, int r, DeterministicRandom random) {
        if (gates.isEmpty()) {
            return annularSector(center, r / 3, r * 4 / 5, 5.0, 6.0, random);
        }
        BlockPos2 g = gates.get(0);
        return boxAround(BlockPos2.of((center.x() + g.x()) / 2, (center.z() + g.z()) / 2), r / 5);
    }

    private BoundingBox2 corridorAlongStreets(BlockPos2 center, List<BlockPos2> gates, int halfWidth) {
        if (gates.isEmpty()) return boxAround(center, halfWidth);
        BlockPos2 g = gates.get(0);
        int minX = Math.min(center.x(), g.x()) - halfWidth / 2;
        int maxX = Math.max(center.x(), g.x()) + halfWidth / 2;
        int minZ = Math.min(center.z(), g.z()) - halfWidth / 2;
        int maxZ = Math.max(center.z(), g.z()) + halfWidth / 2;
        return BoundingBox2.of(minX, minZ, maxX, maxZ);
    }

    private BoundingBox2 annularSector(
            BlockPos2 center, int inner, int outer, double angleStart, double angleEnd,
            DeterministicRandom random
    ) {
        double mid = (angleStart + angleEnd) * 0.5 + random.nextDouble() * 0.05;
        int rad = (inner + outer) / 2;
        int x = center.x() + (int) (Math.cos(mid) * rad);
        int z = center.z() + (int) (Math.sin(mid) * rad);
        int half = Math.max(8, (outer - inner) / 2);
        return boxAround(BlockPos2.of(x, z), half);
    }

    private static BoundingBox2 boxAround(BlockPos2 c, int half) {
        return BoundingBox2.of(c.x() - half, c.z() - half, c.x() + half, c.z() + half);
    }

    private PlannedDistrict district(long seed, int settlementIndex, int ordinal,
                                     PlannedSettlement s, DistrictType type, BoundingBox2 box) {
        DistrictId id = DistrictId.deterministic(seed, settlementIndex * 100L + ordinal);
        return new PlannedDistrict(id, s.id(), type, box);
    }

    private BoundingBox2 clamp(BoundingBox2 inner, BoundingBox2 outer) {
        return BoundingBox2.of(
                Math.max(inner.minX(), outer.minX()),
                Math.max(inner.minZ(), outer.minZ()),
                Math.min(inner.maxX(), outer.maxX()),
                Math.min(inner.maxZ(), outer.maxZ())
        );
    }

    private List<PlannedLot> subdivideLots(
            long seed, int settlementIndex, PlannedSettlement s, PlannedDistrict district,
            CultureDefinition culture, List<BlockPos2> streets, DeterministicRandom random,
            int lotBase, java.util.Set<Long> occupiedLotCells
    ) {
        List<PlannedLot> lots = new ArrayList<>();
        BoundingBox2 b = district.bounds();
        int spacing = SettlementFootprint.primaryStreetSpacing(culture);
        int lotW = culture.architecture().layoutStyle() == CultureDefinition.LayoutStyle.GRID ? 12 : 14;
        int lotD = 10;
        int row = 0;
        for (int z = b.minZ(); z + lotD <= b.maxZ(); z += lotD + 2) {
            for (int x = b.minX(); x + lotW <= b.maxX(); x += lotW + 2) {
                // Leave corridors near primary street samples.
                if (nearStreet(x + lotW / 2, z + lotD / 2, streets, spacing / 2)) {
                    continue;
                }
                BoundingBox2 lotBox = BoundingBox2.of(x, z, x + lotW - 1, z + lotD - 1);
                if (lotOverlapsExisting(lotBox, occupiedLotCells)) {
                    continue;
                }
                if (terrain.waterPresence(lotBox.center().x(), lotBox.center().z())) {
                    continue;
                }
                markLotCells(lotBox, occupiedLotCells);
                LotId lotId = LotId.deterministic(seed, settlementIndex * 1000L + lotBase + lots.size());
                double slope = terrain.averageSlope(lotBox, 4);
                EnumSet<BuildingRole> roles = rolesFor(district.type());
                boolean occupied = random.chance(0.82);
                int entrance = row % 2 == 0 ? 0 : 2;
                lots.add(new PlannedLot(
                        lotId, s.id(), district.id(), district.type(), lotBox,
                        entrance, slope, roles, occupied
                ));
            }
            row++;
        }
        return lots;
    }

    private boolean nearStreet(int x, int z, List<BlockPos2> streets, int threshold) {
        for (BlockPos2 p : streets) {
            if (Math.abs(p.x() - x) + Math.abs(p.z() - z) <= threshold) return true;
        }
        return false;
    }

    private boolean lotOverlapsExisting(BoundingBox2 lotBox, java.util.Set<Long> occupied) {
        for (int x = lotBox.minX(); x <= lotBox.maxX(); x++) {
            for (int z = lotBox.minZ(); z <= lotBox.maxZ(); z++) {
                if (occupied.contains(pack(x, z))) {
                    return true;
                }
            }
        }
        return false;
    }

    private void markLotCells(BoundingBox2 lotBox, java.util.Set<Long> occupied) {
        for (int x = lotBox.minX(); x <= lotBox.maxX(); x++) {
            for (int z = lotBox.minZ(); z <= lotBox.maxZ(); z++) {
                occupied.add(pack(x, z));
            }
        }
    }

    private static long pack(int x, int z) {
        return ((long) x << 32) ^ (z & 0xffffffffL);
    }

    private List<BlockPos2> majorStreets(
            BlockPos2 center, int radius, CultureDefinition culture,
            List<BlockPos2> gates, DeterministicRandom random
    ) {
        List<BlockPos2> streets = new ArrayList<>();
        CultureDefinition.LayoutStyle layout = culture.architecture().layoutStyle();
        int cx = center.x();
        int cz = center.z();
        int spacing = SettlementFootprint.primaryStreetSpacing(culture);

        // Gate → center primary corridors (external road endpoint → gate → primary street).
        for (BlockPos2 gate : gates) {
            int steps = Math.max(1, (int) (gate.distanceTo(center) / spacing));
            for (int i = 0; i <= steps; i++) {
                double t = i / (double) steps;
                int x = (int) Math.round(gate.x() + (cx - gate.x()) * t);
                int z = (int) Math.round(gate.z() + (cz - gate.z()) * t);
                streets.add(BlockPos2.of(x, z));
            }
        }

        if (layout == CultureDefinition.LayoutStyle.GRID || layout == CultureDefinition.LayoutStyle.TERRACED) {
            for (int x = cx - radius; x <= cx + radius; x += spacing) {
                streets.add(BlockPos2.of(x, cz));
            }
            for (int z = cz - radius; z <= cz + radius; z += spacing) {
                streets.add(BlockPos2.of(cx, z));
            }
        } else {
            int rays = 8;
            for (int i = 0; i < rays; i++) {
                double angle = (Math.PI * 2 * i) / rays + random.nextDouble() * 0.1;
                for (int dist = spacing; dist <= radius; dist += spacing) {
                    int x = cx + (int) (Math.cos(angle) * dist);
                    int z = cz + (int) (Math.sin(angle) * dist);
                    streets.add(BlockPos2.of(x, z));
                }
            }
        }
        return streets;
    }

    private List<BlockPos2> secondaryStreets(
            List<BlockPos2> major, BoundingBox2 bounds, CultureDefinition culture, DeterministicRandom random
    ) {
        if (!culture.architecture().preferStraightStreets()) {
            return List.of();
        }
        List<BlockPos2> secondary = new ArrayList<>();
        int step = SettlementFootprint.primaryStreetSpacing(culture) * 2;
        for (int z = bounds.minZ() + step; z < bounds.maxZ(); z += step) {
            for (int x = bounds.minX() + step; x < bounds.maxX(); x += step) {
                if (random.chance(0.35)) {
                    secondary.add(BlockPos2.of(x, z));
                }
            }
        }
        return secondary;
    }

    private List<BlockPos2> wallRing(BlockPos2 center, int radius, TerrainProvider terrain) {
        List<BlockPos2> ring = new ArrayList<>();
        int cx = center.x();
        int cz = center.z();
        int steps = 48;
        for (int i = 0; i < steps; i++) {
            double angle = (Math.PI * 2 * i) / steps;
            int rad = radius;
            // Pull wall inland slightly where terrain is steep or wet.
            int sx = cx + (int) (Math.cos(angle) * radius);
            int sz = cz + (int) (Math.sin(angle) * radius);
            TerrainSample sample = terrain.sample(sx, sz);
            if (sample.water() || sample.slope() > 0.4) {
                rad = (int) (radius * 0.85);
            }
            int x = cx + (int) (Math.cos(angle) * rad);
            int z = cz + (int) (Math.sin(angle) * rad);
            ring.add(BlockPos2.of(x, z));
        }
        return ring;
    }

    private List<BlockPos2> gatePositions(
            List<BlockPos2> wallPath, List<BlockPos2> roadApproaches, DeterministicRandom random
    ) {
        if (wallPath.size() < 4) return List.of();
        List<BlockPos2> gates = new ArrayList<>();
        // Prefer wall points nearest to incoming roads.
        for (BlockPos2 approach : roadApproaches) {
            BlockPos2 nearest = null;
            double best = Double.MAX_VALUE;
            for (BlockPos2 w : wallPath) {
                double d = w.distanceTo(approach);
                if (d < best) {
                    best = d;
                    nearest = w;
                }
            }
            if (nearest != null && !gates.contains(nearest)) {
                gates.add(nearest);
            }
        }
        int desired = Math.max(2, Math.min(4, 2 + random.nextInt(2)));
        int step = wallPath.size() / desired;
        for (int i = 0; gates.size() < desired && i < desired; i++) {
            BlockPos2 g = wallPath.get(i * step);
            if (!gates.contains(g)) gates.add(g);
        }
        return gates;
    }

    private EnumSet<BuildingRole> rolesFor(DistrictType type) {
        return switch (type) {
            case CASTLE -> EnumSet.of(BuildingRole.CASTLE_KEEP, BuildingRole.GATEHOUSE, BuildingRole.TOWER, BuildingRole.BARRACKS);
            case GOVERNMENT -> EnumSet.of(BuildingRole.PALACE, BuildingRole.MONUMENT, BuildingRole.PRISON);
            case MARKET, COMMERCIAL -> EnumSet.of(BuildingRole.MARKET_HALL, BuildingRole.SHOP, BuildingRole.MARKET_STALL, BuildingRole.TAVERN, BuildingRole.WAREHOUSE);
            case WEALTHY_RESIDENTIAL -> EnumSet.of(BuildingRole.MANOR, BuildingRole.TOWNHOUSE);
            case COMMON_RESIDENTIAL -> EnumSet.of(BuildingRole.HOUSE, BuildingRole.TOWNHOUSE, BuildingRole.WELL);
            case MILITARY -> EnumSet.of(BuildingRole.BARRACKS, BuildingRole.GUARDHOUSE, BuildingRole.TOWER, BuildingRole.GATEHOUSE);
            case RELIGIOUS -> EnumSet.of(BuildingRole.TEMPLE, BuildingRole.MONUMENT);
            case CRAFTSMEN -> EnumSet.of(BuildingRole.WORKSHOP, BuildingRole.SMITHY, BuildingRole.TAVERN, BuildingRole.SAWMILL);
            case DOCKS -> EnumSet.of(BuildingRole.DOCK, BuildingRole.WAREHOUSE, BuildingRole.TOWER);
            case HISTORIC_CENTER, PLAZA, EDUCATION -> EnumSet.of(
                    BuildingRole.HOUSE, BuildingRole.SHOP, BuildingRole.SCHOOL, BuildingRole.CLINIC,
                    BuildingRole.TAVERN, BuildingRole.WELL, BuildingRole.PRISON);
            default -> EnumSet.of(BuildingRole.HOUSE, BuildingRole.SHOP, BuildingRole.FARMHOUSE);
        };
    }

    private List<BuildingRole> landmarkRolesFor(DistrictType type, PlannedSettlement s) {
        List<BuildingRole> out = new ArrayList<>();
        switch (type) {
            case CASTLE -> {
                out.add(BuildingRole.CASTLE_KEEP);
                out.add(BuildingRole.GATEHOUSE);
            }
            case GOVERNMENT -> {
                if (s.capital() || s.tier() == SettlementTier.CAPITAL) out.add(BuildingRole.PALACE);
                out.add(BuildingRole.MONUMENT);
            }
            case RELIGIOUS -> out.add(BuildingRole.TEMPLE);
            case MARKET, COMMERCIAL -> out.add(BuildingRole.MARKET_HALL);
            case MILITARY -> {
                out.add(BuildingRole.BARRACKS);
                out.add(BuildingRole.GUARDHOUSE);
            }
            case DOCKS -> {
                out.add(BuildingRole.DOCK);
                out.add(BuildingRole.WAREHOUSE);
            }
            case PLAZA, HISTORIC_CENTER -> {
                out.add(BuildingRole.WELL);
                if (s.tier().ordinal() >= SettlementTier.TOWN.ordinal()) out.add(BuildingRole.SCHOOL);
            }
            default -> {
            }
        }
        return out;
    }

    private BuildingRole pickFillRole(DistrictType type, PlannedSettlement s, DeterministicRandom random) {
        List<BuildingRole> roles = new ArrayList<>(rolesFor(type));
        // Agricultural outskirts for hamlets/villages
        if (!s.tier().isUrban() && s.tier() != SettlementTier.CAPITAL) {
            roles.add(BuildingRole.FARMHOUSE);
            roles.add(BuildingRole.BARN);
            roles.add(BuildingRole.MILL);
            roles.add(BuildingRole.WELL);
        }
        if (s.role() == SettlementRole.MINING || "ironvale".equals(s.cultureKey())) {
            roles.add(BuildingRole.MINE_ENTRANCE);
            roles.add(BuildingRole.SMITHY);
        }
        if (roles.isEmpty()) return BuildingRole.HOUSE;
        return random.pick(roles);
    }

    private int fillBudgetFor(DistrictType type, SettlementTier tier) {
        int base = switch (type) {
            case COMMON_RESIDENTIAL -> 18;
            case WEALTHY_RESIDENTIAL -> 8;
            case MARKET, COMMERCIAL, CRAFTSMEN -> 10;
            case DOCKS, MILITARY -> 6;
            case CASTLE, GOVERNMENT, RELIGIOUS -> 4;
            default -> 8;
        };
        if (tier == SettlementTier.HAMLET) return Math.max(4, base / 3);
        if (tier == SettlementTier.VILLAGE) return Math.max(6, base / 2);
        if (tier == SettlementTier.TOWN) return (base * 3) / 4;
        return base;
    }

    private boolean isLandmarkRole(BuildingRole role) {
        return role == BuildingRole.PALACE || role == BuildingRole.CASTLE_KEEP
                || role == BuildingRole.TEMPLE || role == BuildingRole.MARKET_HALL
                || role == BuildingRole.MONUMENT;
    }

    private boolean districtHasRole(List<PlannedBuilding> buildings, PlannedDistrict district, BuildingRole role) {
        for (PlannedBuilding b : buildings) {
            if (b.districtId().equals(district.id()) && b.role() == role) return true;
        }
        return false;
    }

    private int nearestStreetDirection(BlockPos2 from, List<BlockPos2> streets) {
        if (streets.isEmpty()) return 2;
        BlockPos2 nearest = streets.get(0);
        double best = Double.MAX_VALUE;
        for (BlockPos2 p : streets) {
            double d = p.distanceTo(from);
            if (d < best) {
                best = d;
                nearest = p;
            }
        }
        int dx = nearest.x() - from.x();
        int dz = nearest.z() - from.z();
        if (Math.abs(dx) > Math.abs(dz)) return dx > 0 ? 1 : 3;
        return dz > 0 ? 2 : 0;
    }

    private record OptionalPlacement(PlannedLot lot, PlannedBuilding building) {}

    private OptionalPlacement placeAssetFirst(
            long seed, int settlementIndex, int buildingOrdinal,
            PlannedSettlement s, CultureDefinition culture, PlannedDistrict district,
            BuildingRole role, WealthClass wealth, int entranceDir,
            boolean coastal, boolean underground,
            List<String> recentAssetIds, java.util.Set<Long> occupied,
            List<BlockPos2> streets, DeterministicRandom random
    ) {
        long ordinal = settlementIndex * 10_000L + buildingOrdinal;
        Optional<ArchitectureGrammar.AssetPlacement> apOpt = grammar.selectAssetPlacement(
                seed, ordinal, culture, role, wealth, s.tier(), entranceDir,
                recentAssetIds, coastal && (role == BuildingRole.DOCK || role == BuildingRole.TOWER),
                underground
        );
        BoundingBox2 districtBounds = district.bounds();
        int lotW;
        int lotD;
        ArchitectureGrammar.AssetPlacement ap = null;
        if (apOpt.isPresent()) {
            ap = apOpt.get();
            lotW = ap.lotWidth();
            lotD = ap.lotDepth();
        } else {
            // Emergency procedural lot size by role class
            lotW = switch (role) {
                case PALACE, CASTLE_KEEP -> 28;
                case TEMPLE, MARKET_HALL, BARRACKS, MANOR -> 18;
                case WELL, MARKET_STALL, WAYSTONE -> 7;
                default -> culture.architecture().layoutStyle() == CultureDefinition.LayoutStyle.GRID ? 12 : 14;
            };
            lotD = role == BuildingRole.PALACE || role == BuildingRole.CASTLE_KEEP ? 24 : Math.max(8, lotW - 2);
        }
        // Culture spacing: steppe/dispersed cultures get more padding.
        int gap = switch (culture.architecture().layoutStyle()) {
            case ORGANIC_RADIAL -> 3;
            case TERRACED, CAVERN -> 2;
            default -> 2;
        };
        if ("steppeborn".equals(culture.key())) gap = 5;

        for (int attempt = 0; attempt < 24; attempt++) {
            int x = districtBounds.minX() + random.nextInt(Math.max(1, districtBounds.width() - lotW));
            int z = districtBounds.minZ() + random.nextInt(Math.max(1, districtBounds.depth() - lotD));
            BoundingBox2 lotBox = BoundingBox2.of(x, z, x + lotW - 1, z + lotD - 1);
            if (lotBox.maxX() > districtBounds.maxX() || lotBox.maxZ() > districtBounds.maxZ()) continue;
            if (lotOverlapsExisting(lotBox, occupied)) continue;
            if (role != BuildingRole.DOCK && role != BuildingRole.BRIDGE
                    && terrain.waterPresence(lotBox.center().x(), lotBox.center().z())) {
                continue;
            }
            if (role == BuildingRole.DOCK && !terrain.coast(lotBox.center().x(), lotBox.center().z())
                    && !terrain.waterPresence(lotBox.center().x(), lotBox.center().z())
                    && !coastal) {
                continue;
            }
            double slope = terrain.averageSlope(lotBox, 4);
            int tolerance = ap != null ? ap.asset().terrainTolerance() : 4;
            if (slope > 0.15 * tolerance && role != BuildingRole.MINE_ENTRANCE) {
                continue;
            }
            markLotCells(expand(lotBox, gap / 2), occupied);
            int dir = nearestStreetDirection(lotBox.center(), streets);
            LotId lotId = LotId.deterministic(seed, settlementIndex * 1000L + buildingOrdinal + 700);
            PlannedLot lot = new PlannedLot(
                    lotId, s.id(), district.id(), district.type(), lotBox,
                    dir, slope, EnumSet.of(role), true
            );
            int foundationY = (int) terrain.surfaceHeight(lotBox.center().x(), lotBox.center().z());
            ArchitectureGrammar.Blueprint bp;
            if (ap != null) {
                // Recompute rotation toward actual street
                ArchitectureGrammar.AssetPlacement aligned = new ArchitectureGrammar.AssetPlacement(
                        ap.asset(),
                        ArchitectureGrammar.chooseRotationFit(ap.asset(), dir, lotBox),
                        ap.width(), ap.depth(), ap.clearance(),
                        ap.entranceFacing()
                );
                int[] dims = com.livingmods.worldgen.structure.MlsStructureFormat.rotatedDimensions(
                        ap.asset().width(), ap.asset().depth(), aligned.rotationSteps());
                aligned = new ArchitectureGrammar.AssetPlacement(
                        ap.asset(), aligned.rotationSteps(), dims[0], dims[1], ap.clearance(),
                        com.livingmods.worldgen.structure.MlsStructureFormat.rotateFacing(
                                ap.asset().entranceFacing(), aligned.rotationSteps())
                );
                bp = grammar.generateFromPlacement(
                        seed, ordinal, culture, role, wealth, s.tier(),
                        lotId, s.id(), district.id(), lotBox, foundationY, aligned
                );
            } else {
                bp = grammar.generate(
                        seed, ordinal, culture, role, wealth, s.tier(),
                        lotId, s.id(), district.id(), lotBox, dir, foundationY, recentAssetIds
                );
            }
            return new OptionalPlacement(lot, bp.building());
        }
        return null;
    }

    private static BoundingBox2 expand(BoundingBox2 box, int pad) {
        if (pad <= 0) return box;
        return BoundingBox2.of(box.minX() - pad, box.minZ() - pad, box.maxX() + pad, box.maxZ() + pad);
    }

    private int placeSpecialRoutes(
            long seed, int index, PlannedSettlement s, CultureDefinition culture,
            List<PlannedDistrict> districts, List<PlannedLot> lots, List<PlannedBuilding> buildings,
            List<String> recentAssetIds, java.util.Set<Long> occupied, List<BlockPos2> streets,
            List<BlockPos2> gates, List<BlockPos2> wallPath,
            boolean coastal, boolean underground, DeterministicRandom random, int buildingOrdinal
    ) {
        PlannedDistrict host = districts.isEmpty() ? null : districts.get(districts.size() - 1);
        if (host == null) return buildingOrdinal;

        // Agriculture for hamlet/village
        if (s.tier() == SettlementTier.HAMLET || s.tier() == SettlementTier.VILLAGE
                || s.role() == SettlementRole.FARMING) {
            for (BuildingRole role : List.of(
                    BuildingRole.FARMHOUSE, BuildingRole.BARN, BuildingRole.MILL, BuildingRole.WAREHOUSE
            )) {
                OptionalPlacement p = placeAssetFirst(
                        seed, index, buildingOrdinal, s, culture, host, role, WealthClass.COMMON, 2,
                        coastal, underground, recentAssetIds, occupied, streets, random
                );
                if (p != null) {
                    lots.add(p.lot());
                    buildings.add(p.building());
                    if (p.building().usesImportedAsset()) recentAssetIds.add(p.building().assetId());
                    buildingOrdinal++;
                }
            }
        }

        // Mining
        if (s.role() == SettlementRole.MINING || "ironvale".equals(s.cultureKey())) {
            OptionalPlacement p = placeAssetFirst(
                    seed, index, buildingOrdinal, s, culture, host, BuildingRole.MINE_ENTRANCE,
                    WealthClass.POOR, 0, coastal, true, recentAssetIds, occupied, streets, random
            );
            if (p != null) {
                lots.add(p.lot());
                buildings.add(p.building());
                if (p.building().usesImportedAsset()) recentAssetIds.add(p.building().assetId());
                buildingOrdinal++;
            }
        }

        // Gatehouses at gates
        if (!gates.isEmpty() && s.walls()) {
            PlannedDistrict military = districts.stream()
                    .filter(d -> d.type() == DistrictType.MILITARY || d.type() == DistrictType.CASTLE)
                    .findFirst().orElse(host);
            for (int gi = 0; gi < Math.min(2, gates.size()); gi++) {
                OptionalPlacement p = placeAssetFirst(
                        seed, index, buildingOrdinal, s, culture, military, BuildingRole.GATEHOUSE,
                        WealthClass.COMMON, nearestStreetDirection(gates.get(gi), streets),
                        coastal, underground, recentAssetIds, occupied, streets, random
                );
                if (p != null) {
                    lots.add(p.lot());
                    buildings.add(p.building());
                    if (p.building().usesImportedAsset()) recentAssetIds.add(p.building().assetId());
                    buildingOrdinal++;
                }
            }
        }

        // Wall modules along wall path (sparse)
        if (!wallPath.isEmpty() && s.walls()) {
            PlannedDistrict military = districts.stream()
                    .filter(d -> d.type() == DistrictType.MILITARY || d.type() == DistrictType.CASTLE)
                    .findFirst().orElse(host);
            for (int i = 0; i < wallPath.size(); i += Math.max(8, wallPath.size() / 4)) {
                OptionalPlacement p = placeAssetFirst(
                        seed, index, buildingOrdinal, s, culture, military, BuildingRole.WALL_SEGMENT,
                        WealthClass.COMMON, 0, coastal, underground, recentAssetIds, occupied, streets, random
                );
                if (p != null) {
                    lots.add(p.lot());
                    buildings.add(p.building());
                    if (p.building().usesImportedAsset()) recentAssetIds.add(p.building().assetId());
                    buildingOrdinal++;
                }
            }
        }

        // Waystations / bridges near roads for towns+
        if (s.tier().ordinal() >= SettlementTier.VILLAGE.ordinal()) {
            OptionalPlacement way = placeAssetFirst(
                    seed, index, buildingOrdinal, s, culture, host, BuildingRole.WAYSTONE,
                    WealthClass.POOR, 2, coastal, underground, recentAssetIds, occupied, streets, random
            );
            if (way != null) {
                lots.add(way.lot());
                buildings.add(way.building());
                if (way.building().usesImportedAsset()) recentAssetIds.add(way.building().assetId());
                buildingOrdinal++;
            }
        }

        // Occasional ruin on outskirts
        if (random.chance(0.35)) {
            OptionalPlacement ruin = placeAssetFirst(
                    seed, index, buildingOrdinal, s, culture, host, BuildingRole.RUIN,
                    WealthClass.POOR, random.nextInt(4), coastal, underground, recentAssetIds, occupied, streets, random
            );
            if (ruin != null) {
                lots.add(ruin.lot());
                buildings.add(ruin.building());
                if (ruin.building().usesImportedAsset()) recentAssetIds.add(ruin.building().assetId());
                buildingOrdinal++;
            }
        }

        // Lighthouse for coastal atlantea / ports
        if (coastal && ("atlantea".equals(s.cultureKey()) || s.role() == SettlementRole.PORT)) {
            OptionalPlacement light = placeAssetFirst(
                    seed, index, buildingOrdinal, s, culture, host, BuildingRole.TOWER,
                    WealthClass.COMMON, 2, true, underground, recentAssetIds, occupied, streets, random
            );
            if (light != null) {
                lots.add(light.lot());
                buildings.add(light.building());
                if (light.building().usesImportedAsset()) recentAssetIds.add(light.building().assetId());
                buildingOrdinal++;
            }
        }
        return buildingOrdinal;
    }

    private WealthClass wealthFor(DistrictType type, SettlementTier tier) {
        return switch (type) {
            case CASTLE, GOVERNMENT -> WealthClass.ROYAL;
            case WEALTHY_RESIDENTIAL -> WealthClass.WEALTHY;
            case MARKET, COMMERCIAL -> WealthClass.COMFORTABLE;
            case MILITARY -> WealthClass.COMMON;
            default -> tier.isCapitalClass() ? WealthClass.COMFORTABLE : WealthClass.COMMON;
        };
    }
}
