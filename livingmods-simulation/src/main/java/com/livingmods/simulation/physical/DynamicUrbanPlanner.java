package com.livingmods.simulation.physical;

import com.livingmods.common.culture.CultureDefinition;
import com.livingmods.common.culture.CultureRegistry;
import com.livingmods.common.culture.CultureKeys;
import com.livingmods.common.geo.BlockPos2;
import com.livingmods.common.geo.BoundingBox2;
import com.livingmods.common.id.DistrictId;
import com.livingmods.common.id.LotId;
import com.livingmods.common.id.SettlementId;
import com.livingmods.common.id.StructureId;
import com.livingmods.common.model.BuildingRole;
import com.livingmods.common.model.WealthClass;
import com.livingmods.common.util.DeterministicRandom;
import com.livingmods.common.util.Hashing;
import com.livingmods.simulation.CanonicalWorldState;
import com.livingmods.simulation.state.SettlementState;
import com.livingmods.worldgen.architecture.ArchitectureGrammar;
import com.livingmods.worldgen.plan.PlannedBuilding;
import com.livingmods.worldgen.terrain.TerrainAnalyzer;
import com.livingmods.worldgen.terrain.TerrainProvider;
import com.livingmods.worldgen.terrain.TerrainSample;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Focused dynamic planning layer that extends existing settlements.
 * Produces valid geometry; block placement remains materializer responsibility.
 * Reuses UrbanPlanner / ArchitectureGrammar / terrain principles without duplicating worldgen.
 */
public final class DynamicUrbanPlanner {
    public static final int MAX_PLOT_CANDIDATES = 24;
    public static final int MAX_ROUTE_POINTS = 64;

    private final CultureRegistry cultures;
    private final TerrainProvider terrain;
    private final ArchitectureGrammar grammar;

    public DynamicUrbanPlanner(long worldSeed) {
        this(new CultureRegistry(), new TerrainAnalyzer(worldSeed));
    }

    public DynamicUrbanPlanner(CultureRegistry cultures, TerrainProvider terrain) {
        this.cultures = cultures;
        this.terrain = terrain;
        this.grammar = new ArchitectureGrammar();
    }

    public record PlotCandidate(
            BlockPos2 center,
            BoundingBox2 footprint,
            String entranceFacing,
            BlockPos2 accessRoadPoint,
            double score,
            String cultureKey,
            ArchitectureGrammar.Blueprint blueprint
    ) {}

    public record DistrictPlan(
            BoundingBox2 boundary,
            BlockPos2 primaryConnection,
            List<BlockPos2> secondaryStreets,
            List<BoundingBox2> lots,
            List<BlockPos2> openSpaces,
            List<BlockPos2> expansionAnchors,
            String cultureKey
    ) {}

    public record SettlementFoundingPlan(
            BlockPos2 center,
            BoundingBox2 boundary,
            BlockPos2 civicCore,
            List<BlockPos2> roadGraph,
            List<PlotCandidate> starterBuildings,
            List<BlockPos2> expansionAnchors,
            String cultureKey
    ) {}

    public record RoadRoute(
            List<BlockPos2> path,
            List<BoundingBox2> bridgeSpans,
            boolean connectedToExisting
    ) {}

    public record WallPlan(
            List<BlockPos2> perimeter,
            List<BlockPos2> gatePositions,
            BoundingBox2 boundary
    ) {}

    public record CampSite(
            BlockPos2 center,
            BoundingBox2 footprint,
            String variant,
            double score
    ) {}

    public Optional<PlotCandidate> chooseBuildingPlot(
            CanonicalWorldState state,
            SettlementState settlement,
            BuildingRole role,
            String cultureKey,
            long ordinal
    ) {
        String culture = CultureKeys.sanitize(cultureKey);
        CultureDefinition def = cultures.get(culture).orElse(cultures.get(CultureKeys.DEFAULT).orElseThrow());
        DynamicSettlementGeometry geo = state.dynamicPhysical().settlementGeometry().get(settlement.id());
        BoundingBox2 search = geo != null ? geo.boundary() : BoundingBox2.around(settlement.center(), 48);
        int half = footprintHalf(role);
        DeterministicRandom random = new DeterministicRandom(Hashing.mix(state.seed(), ordinal ^ role.ordinal()));

        List<PlotCandidate> candidates = new ArrayList<>();
        for (int i = 0; i < MAX_PLOT_CANDIDATES; i++) {
            int x = search.minX() + 4 + random.nextInt(Math.max(1, search.width() - 8));
            int z = search.minZ() + 4 + random.nextInt(Math.max(1, search.depth() - 8));
            BlockPos2 center = BlockPos2.of(x, z);
            BoundingBox2 footprint = BoundingBox2.around(center, half);
            TerrainMetrics metrics = sampleTerrain(footprint);
            if (!metrics.suitable(half)) {
                continue;
            }
            if (collides(state, settlement.id(), footprint)) {
                continue;
            }
            BlockPos2 road = nearestRoadAccess(state, settlement, center);
            if (road == null) {
                continue;
            }
            String facing = entranceToward(center, road);
            List<String> recent = recentAssetIds(state, settlement.id());
            ArchitectureGrammar.Blueprint blueprint = grammar.generate(
                    state.seed(),
                    ordinal + i,
                    def,
                    role,
                    WealthClass.COMMON,
                    settlement.tier(),
                    LotId.deterministic(state.seed(), ordinal + i),
                    settlement.id(),
                    DistrictId.deterministic(state.seed(), ordinal + i),
                    footprint,
                    facingToDir(facing),
                    (int) metrics.medianY,
                    recent
            );
            double score = scorePlot(metrics, center, road, settlement.center(), role);
            candidates.add(new PlotCandidate(
                    center, blueprint.building().footprint(), facing, road, score, culture, blueprint));
        }
        return candidates.stream().max(Comparator.comparingDouble(PlotCandidate::score));
    }

    private static List<String> recentAssetIds(CanonicalWorldState state, SettlementId settlementId) {
        List<String> recent = new ArrayList<>();
        for (DynamicStructureRecord rec : state.dynamicPhysical().structures().values()) {
            if (rec.settlementId().equals(settlementId) && rec.assetId() != null && !rec.assetId().isBlank()) {
                recent.add(rec.assetId());
            }
        }
        for (PhysicalIntent intent : state.dynamicPhysical().intents().values()) {
            if (intent.settlementId().isPresent() && intent.settlementId().get().equals(settlementId)) {
                String assetId = intent.provenance().get("assetId");
                if (assetId != null && !assetId.isBlank()) {
                    recent.add(assetId);
                }
            }
        }
        return recent;
    }

    public DistrictPlan planDistrict(
            CanonicalWorldState state,
            SettlementState settlement,
            BlockPos2 anchor,
            String cultureKey,
            int generation
    ) {
        String culture = CultureKeys.sanitize(cultureKey);
        BoundingBox2 boundary = BoundingBox2.around(anchor, 20);
        DynamicSettlementGeometry geo = state.dynamicPhysical().settlementGeometry().get(settlement.id());
        BlockPos2 connection = geo != null && !geo.roadAnchors().isEmpty()
                ? geo.roadAnchors().get(geo.roadAnchors().size() - 1)
                : settlement.center();

        List<BlockPos2> streets = new ArrayList<>();
        streets.add(connection);
        streets.add(anchor);
        streets.add(BlockPos2.of(anchor.x() + 12, anchor.z()));
        streets.add(BlockPos2.of(anchor.x() - 12, anchor.z()));
        streets.add(BlockPos2.of(anchor.x(), anchor.z() + 12));
        streets.add(BlockPos2.of(anchor.x(), anchor.z() - 12));

        List<BoundingBox2> lots = new ArrayList<>();
        int[][] offsets = {{8, 8}, {-8, 8}, {8, -8}, {-8, -8}, {14, 0}, {-14, 0}, {0, 14}, {0, -14}};
        for (int[] o : offsets) {
            BlockPos2 lotCenter = BlockPos2.of(anchor.x() + o[0], anchor.z() + o[1]);
            BoundingBox2 lot = BoundingBox2.around(lotCenter, 4);
            TerrainMetrics m = sampleTerrain(lot);
            if (m.suitable(4) && !collides(state, settlement.id(), lot)) {
                lots.add(lot);
            }
        }

        List<BlockPos2> open = List.of(
                BlockPos2.of(anchor.x() + 4, anchor.z() + 4),
                BlockPos2.of(anchor.x() - 4, anchor.z() - 4)
        );
        List<BlockPos2> expansion = List.of(
                BlockPos2.of(anchor.x() + 28, anchor.z()),
                BlockPos2.of(anchor.x(), anchor.z() + 28)
        );
        return new DistrictPlan(boundary, connection, streets, lots, open, expansion, culture);
    }

    public SettlementFoundingPlan planPlayerSettlement(
            CanonicalWorldState state,
            SettlementState capital,
            String cultureKey
    ) {
        String culture = CultureKeys.sanitize(cultureKey);
        BlockPos2 center = capital.center();
        BoundingBox2 boundary = BoundingBox2.around(center, 48);
        BlockPos2 civic = center;

        List<BlockPos2> roads = new ArrayList<>();
        roads.add(center);
        roads.add(BlockPos2.of(center.x() + 24, center.z()));
        roads.add(BlockPos2.of(center.x() - 24, center.z()));
        roads.add(BlockPos2.of(center.x(), center.z() + 24));
        roads.add(BlockPos2.of(center.x(), center.z() - 24));

        List<PlotCandidate> starters = new ArrayList<>();
        BuildingRole[] roles = {
                BuildingRole.MANOR, BuildingRole.HOUSE, BuildingRole.HOUSE,
                BuildingRole.WAREHOUSE, BuildingRole.FARMHOUSE, BuildingRole.GUARDHOUSE
        };
        BlockPos2[] plots = {
                center,
                BlockPos2.of(center.x() + 12, center.z()),
                BlockPos2.of(center.x() - 12, center.z() + 4),
                BlockPos2.of(center.x(), center.z() + 14),
                BlockPos2.of(center.x() + 18, center.z() + 18),
                BlockPos2.of(center.x() - 16, center.z() - 8)
        };
        for (int i = 0; i < roles.length; i++) {
            final int idx = i;
            Optional<PlotCandidate> chosen = chooseBuildingPlot(state, capital, roles[i], culture, 5000L + idx);
            starters.add(chosen.orElseGet(() -> fallbackPlot(plots[idx], roles[idx], culture)));
        }

        List<BlockPos2> anchors = List.of(
                BlockPos2.of(center.x() + 40, center.z()),
                BlockPos2.of(center.x(), center.z() + 40)
        );
        return new SettlementFoundingPlan(center, boundary, civic, roads, starters, anchors, culture);
    }

    public RoadRoute planRoad(
            CanonicalWorldState state,
            SettlementState settlement,
            BlockPos2 from,
            BlockPos2 to,
            String cultureKey
    ) {
        List<BlockPos2> path = new ArrayList<>();
        // Prefer connecting via existing road anchors / geometry.
        DynamicSettlementGeometry geo = state.dynamicPhysical().settlementGeometry().get(settlement.id());
        BlockPos2 junction = from;
        boolean connected = false;
        if (geo != null && !geo.roadAnchors().isEmpty()) {
            junction = geo.roadAnchors().stream()
                    .min(Comparator.comparingDouble(a -> a.distanceTo(to)))
                    .orElse(from);
            connected = true;
            path.add(junction);
        } else {
            path.add(from);
        }

        List<BoundingBox2> bridges = new ArrayList<>();
        int steps = Math.max(4, (int) (junction.distanceTo(to) / 8));
        BlockPos2 prev = junction;
        for (int i = 1; i <= steps; i++) {
            int x = junction.x() + (to.x() - junction.x()) * i / steps;
            int z = junction.z() + (to.z() - junction.z()) * i / steps;
            // Local corridor adjustment away from water / steep slope.
            TerrainSample s = terrain.sample(x, z);
            if (s.water() || s.slope() > 0.55) {
                int bestX = x;
                int bestZ = z;
                double best = Double.MAX_VALUE;
                for (int dx = -8; dx <= 8; dx += 4) {
                    for (int dz = -8; dz <= 8; dz += 4) {
                        TerrainSample alt = terrain.sample(x + dx, z + dz);
                        double cost = alt.slope() * 10 + (alt.water() ? 40 : 0) + Math.abs(dx) + Math.abs(dz);
                        if (cost < best) {
                            best = cost;
                            bestX = x + dx;
                            bestZ = z + dz;
                        }
                    }
                }
                if (terrain.sample(bestX, bestZ).water() && terrain.sample(prev).water()) {
                    // Still water — mark bridge span.
                    bridges.add(BoundingBox2.of(
                            Math.min(prev.x(), bestX) - 1, Math.min(prev.z(), bestZ) - 1,
                            Math.max(prev.x(), bestX) + 1, Math.max(prev.z(), bestZ) + 1));
                } else if (terrain.sample(bestX, bestZ).water() || terrain.sample(prev).water()) {
                    bridges.add(BoundingBox2.of(
                            Math.min(prev.x(), bestX) - 1, Math.min(prev.z(), bestZ) - 1,
                            Math.max(prev.x(), bestX) + 1, Math.max(prev.z(), bestZ) + 1));
                }
                x = bestX;
                z = bestZ;
            }
            BlockPos2 next = BlockPos2.of(x, z);
            path.add(next);
            prev = next;
            if (path.size() >= MAX_ROUTE_POINTS) break;
        }
        if (!path.isEmpty() && !path.get(path.size() - 1).equals(to)) {
            path.add(to);
        }
        if (geo != null) {
            geo.roadAnchors().add(to);
        }
        return new RoadRoute(path, bridges, connected);
    }

    public WallPlan planWall(SettlementState settlement, DynamicSettlementGeometry geo, String cultureKey) {
        BoundingBox2 boundary = geo != null ? geo.boundary() : BoundingBox2.around(settlement.center(), 48);
        List<BlockPos2> perimeter = new ArrayList<>();
        int step = 4;
        for (int x = boundary.minX(); x <= boundary.maxX(); x += step) {
            perimeter.add(BlockPos2.of(x, boundary.minZ()));
        }
        for (int z = boundary.minZ() + step; z <= boundary.maxZ(); z += step) {
            perimeter.add(BlockPos2.of(boundary.maxX(), z));
        }
        for (int x = boundary.maxX() - step; x >= boundary.minX(); x -= step) {
            perimeter.add(BlockPos2.of(x, boundary.maxZ()));
        }
        for (int z = boundary.maxZ() - step; z > boundary.minZ(); z -= step) {
            perimeter.add(BlockPos2.of(boundary.minX(), z));
        }

        List<BlockPos2> gates = new ArrayList<>();
        // Gates on each cardinal mid-edge (road crossings).
        gates.add(BlockPos2.of(boundary.center().x(), boundary.minZ()));
        gates.add(BlockPos2.of(boundary.center().x(), boundary.maxZ()));
        gates.add(BlockPos2.of(boundary.minX(), boundary.center().z()));
        gates.add(BlockPos2.of(boundary.maxX(), boundary.center().z()));
        if (geo != null) {
            for (BlockPos2 road : geo.roadAnchors()) {
                BlockPos2 onEdge = projectToBoundary(boundary, road);
                if (gates.stream().noneMatch(g -> g.distanceTo(onEdge) < 8)) {
                    gates.add(onEdge);
                }
            }
        }
        return new WallPlan(perimeter, gates, boundary);
    }

    public Optional<CampSite> chooseBanditSite(
            CanonicalWorldState state,
            SettlementState settlement,
            String preferredVariant,
            long ordinal
    ) {
        DeterministicRandom random = new DeterministicRandom(Hashing.mix(state.seed(), ordinal));
        List<CampSite> sites = new ArrayList<>();
        for (int i = 0; i < 16; i++) {
            int dist = 40 + random.nextInt(48);
            double angle = random.nextDouble() * Math.PI * 2;
            BlockPos2 center = BlockPos2.of(
                    settlement.center().x() + (int) Math.round(Math.cos(angle) * dist),
                    settlement.center().z() + (int) Math.round(Math.sin(angle) * dist)
            );
            String variant = preferredVariant == null || preferredVariant.isBlank()
                    ? pickVariant(terrain.sample(center), dist) : preferredVariant;
            int radius = variant.contains("STRONG") || variant.contains("FORT") ? 10 : 6;
            BoundingBox2 fp = BoundingBox2.around(center, radius);
            TerrainMetrics m = sampleTerrain(fp);
            double score = scoreCamp(state, settlement, center, variant, m);
            if (score > 0.15) {
                sites.add(new CampSite(center, fp, variant, score));
            }
        }
        return sites.stream().max(Comparator.comparingDouble(CampSite::score));
    }

    public TerrainMetrics sampleTerrain(BoundingBox2 footprint) {
        int samples = 0;
        double minY = Double.MAX_VALUE;
        double maxY = -Double.MAX_VALUE;
        double sumY = 0;
        double sumSlope = 0;
        int water = 0;
        List<Double> heights = new ArrayList<>();
        int step = Math.max(2, Math.min(footprint.width(), footprint.depth()) / 4);
        for (int x = footprint.minX(); x <= footprint.maxX(); x += step) {
            for (int z = footprint.minZ(); z <= footprint.maxZ(); z += step) {
                TerrainSample s = terrain.sample(x, z);
                minY = Math.min(minY, s.elevation());
                maxY = Math.max(maxY, s.elevation());
                sumY += s.elevation();
                sumSlope += s.slope();
                if (s.water()) water++;
                heights.add(s.elevation());
                samples++;
            }
        }
        if (samples == 0) {
            TerrainSample s = terrain.sample(footprint.center());
            return new TerrainMetrics(s.elevation(), s.elevation(), s.elevation(), s.slope(), 0, s.water() ? 1 : 0, 1);
        }
        heights.sort(Double::compareTo);
        double median = heights.get(heights.size() / 2);
        double variance = 0;
        double avg = sumY / samples;
        for (double h : heights) {
            variance += (h - avg) * (h - avg);
        }
        variance /= samples;
        return new TerrainMetrics(minY, maxY, median, sumSlope / samples, variance, water / (double) samples, samples);
    }

    public record TerrainMetrics(
            double minY, double maxY, double medianY, double slope, double variance, double waterFraction, int samples
    ) {
        public boolean suitable(int halfExtent) {
            if (waterFraction > 0.25) return false;
            if (slope > 0.5) return false;
            if (maxY - minY > Math.max(4, halfExtent)) return false;
            if (variance > 12) return false;
            if (medianY < 58 || medianY > 120) return false;
            return true;
        }
    }

    private boolean collides(CanonicalWorldState state, SettlementId sid, BoundingBox2 footprint) {
        DynamicPhysicalState physical = state.dynamicPhysical();
        for (DynamicStructureRecord rec : physical.structures().values()) {
            if (rec.footprint().intersects(footprint)) {
                return true;
            }
        }
        for (PhysicalIntent intent : physical.intents().values()) {
            if (!intent.status().isActive()) continue;
            if (intent.footprint() != null && intent.footprint().intersects(footprint)
                    && intent.type().isPersistentGeometry()) {
                return true;
            }
        }
        // Neighbouring settlements
        for (SettlementState other : state.settlements().values()) {
            if (other.id().equals(sid)) continue;
            if (other.center().distanceTo(footprint.center()) < 32
                    && BoundingBox2.around(other.center(), 20).intersects(footprint)) {
                return true;
            }
        }
        return false;
    }

    private BlockPos2 nearestRoadAccess(CanonicalWorldState state, SettlementState settlement, BlockPos2 center) {
        DynamicSettlementGeometry geo = state.dynamicPhysical().settlementGeometry().get(settlement.id());
        if (geo != null && !geo.roadAnchors().isEmpty()) {
            return geo.roadAnchors().stream()
                    .min(Comparator.comparingDouble(a -> a.distanceTo(center)))
                    .orElse(settlement.center());
        }
        // Fall back to settlement center as circulation hub.
        return settlement.center();
    }

    private static double scorePlot(
            TerrainMetrics metrics, BlockPos2 center, BlockPos2 road, BlockPos2 settlementCenter, BuildingRole role
    ) {
        double score = 2.0;
        score += (1.0 - metrics.slope) * 2.0;
        score += (1.0 - Math.min(1.0, metrics.variance / 10.0));
        score -= metrics.waterFraction * 3.0;
        score -= center.distanceTo(road) * 0.05;
        score -= Math.abs(center.distanceTo(settlementCenter) - 20) * 0.02;
        if (role == BuildingRole.FARMHOUSE) score += metrics.medianY < 75 ? 0.5 : 0;
        if (role == BuildingRole.WAREHOUSE) score += center.distanceTo(road) < 12 ? 0.8 : 0;
        return score;
    }

    private static double scoreCamp(
            CanonicalWorldState state,
            SettlementState settlement,
            BlockPos2 center,
            String variant,
            TerrainMetrics metrics
    ) {
        double score = 1.0 - metrics.slope;
        score -= metrics.waterFraction * 2.0;
        double dist = center.distanceTo(settlement.center());
        if (dist < 32) score -= 1.5;
        if (dist > 120) score -= 0.8;
        TerrainSample s = new TerrainAnalyzer(state.seed()).sample(center);
        if (variant.contains("FOREST") && s.forested()) score += 1.2;
        if (variant.contains("ROAD")) score += (1.0 - Math.abs(dist - 56) / 56.0);
        if (variant.contains("STRONG") || variant.contains("FORT")) {
            if (metrics.maxY - metrics.minY < 6 && metrics.suitable(10)) score += 0.8;
            else score -= 1.0;
        }
        // Avoid other camps.
        for (PhysicalIntent intent : state.dynamicPhysical().intents().values()) {
            if ((intent.type().name().contains("BANDIT"))
                    && intent.targetPosition().distanceTo(center) < 40) {
                score -= 2.0;
            }
        }
        return score;
    }

    private static String pickVariant(TerrainSample s, int dist) {
        if (s.forested()) return "FOREST_CAMP";
        if (dist < 56) return "ROAD_CAMP";
        if (s.defensive()) return "HIDEOUT";
        return "ROAD_CAMP";
    }

    private PlotCandidate fallbackPlot(BlockPos2 center, BuildingRole role, String culture) {
        int half = footprintHalf(role);
        BoundingBox2 fp = BoundingBox2.around(center, half);
        return new PlotCandidate(center, fp, "south", center, 0.1, culture, null);
    }

    private static int footprintHalf(BuildingRole role) {
        return switch (role) {
            case HOUSE, FARMHOUSE, MARKET_STALL, WELL -> 3;
            case TOWNHOUSE, SHOP, WORKSHOP, GUARDHOUSE, CLINIC -> 4;
            case WAREHOUSE, TEMPLE, SCHOOL, BARRACKS, SMITHY -> 5;
            case MANOR, MARKET_HALL, GATEHOUSE -> 6;
            case WALL_SEGMENT, TOWER -> 2;
            default -> 4;
        };
    }

    private static String entranceToward(BlockPos2 building, BlockPos2 road) {
        int dx = road.x() - building.x();
        int dz = road.z() - building.z();
        if (Math.abs(dx) > Math.abs(dz)) {
            return dx > 0 ? "east" : "west";
        }
        return dz > 0 ? "south" : "north";
    }

    private static int facingToDir(String facing) {
        return switch (facing) {
            case "east" -> 1;
            case "south" -> 2;
            case "west" -> 3;
            default -> 0;
        };
    }

    private static BlockPos2 projectToBoundary(BoundingBox2 b, BlockPos2 p) {
        int x = Math.max(b.minX(), Math.min(b.maxX(), p.x()));
        int z = Math.max(b.minZ(), Math.min(b.maxZ(), p.z()));
        int dxMin = Math.abs(x - b.minX());
        int dxMax = Math.abs(x - b.maxX());
        int dzMin = Math.abs(z - b.minZ());
        int dzMax = Math.abs(z - b.maxZ());
        int best = Math.min(Math.min(dxMin, dxMax), Math.min(dzMin, dzMax));
        if (best == dxMin) return BlockPos2.of(b.minX(), z);
        if (best == dxMax) return BlockPos2.of(b.maxX(), z);
        if (best == dzMin) return BlockPos2.of(x, b.minZ());
        return BlockPos2.of(x, b.maxZ());
    }
}
