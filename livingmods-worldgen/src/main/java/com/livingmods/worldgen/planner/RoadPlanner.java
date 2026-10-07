package com.livingmods.worldgen.planner;

import com.livingmods.common.geo.BlockPos2;
import com.livingmods.common.id.KingdomId;
import com.livingmods.common.id.RoadId;
import com.livingmods.common.id.SettlementId;
import com.livingmods.common.model.RoadClass;
import com.livingmods.common.model.SettlementTier;
import com.livingmods.common.util.DeterministicRandom;
import com.livingmods.common.util.Hashing;
import com.livingmods.worldgen.plan.PlannedBridge;
import com.livingmods.worldgen.plan.PlannedKingdom;
import com.livingmods.worldgen.plan.PlannedRoad;
import com.livingmods.worldgen.plan.PlannedSettlement;
import com.livingmods.worldgen.terrain.TerrainProvider;
import com.livingmods.worldgen.terrain.TerrainSample;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.PriorityQueue;
import java.util.Set;

public final class RoadPlanner {
    private static final int STEP = 8;
    private static final int[][] NEIGHBORS = {
            {STEP, 0}, {-STEP, 0}, {0, STEP}, {0, -STEP},
            {STEP, STEP}, {STEP, -STEP}, {-STEP, STEP}, {-STEP, -STEP}
    };

    private final TerrainProvider terrain;

    public RoadPlanner(TerrainProvider terrain) {
        this.terrain = terrain;
    }

    public List<PlannedRoad> plan(long seed, List<PlannedKingdom> kingdoms, List<PlannedSettlement> settlements) {
        long roadSeed = Hashing.mix(seed, 0x524F4144L);
        List<PlannedRoad> roads = new ArrayList<>();
        int roadOrdinal = 0;

        Map<KingdomId, List<PlannedSettlement>> byKingdom = groupByKingdom(settlements);

        for (PlannedKingdom kingdom : kingdoms) {
            if (kingdom.underground()) continue;
            List<PlannedSettlement> realm = byKingdom.getOrDefault(kingdom.id(), List.of());
            if (realm.size() < 2) continue;

            DeterministicRandom random = new DeterministicRandom(Hashing.mix(roadSeed, kingdom.id().hashCode()));

            PlannedSettlement capital = realm.stream().filter(PlannedSettlement::capital).findFirst().orElse(realm.get(0));
            List<PlannedSettlement> cities = byTier(realm, SettlementTier.CITY);
            List<PlannedSettlement> towns = byTier(realm, SettlementTier.TOWN);
            List<PlannedSettlement> villages = byTier(realm, SettlementTier.VILLAGE, SettlementTier.HAMLET);

            Set<SettlementId> connected = new HashSet<>();
            connected.add(capital.id());

            for (PlannedSettlement city : cities) {
                addRoad(seed, roadOrdinal++, roads, capital, city, RoadClass.ROYAL_HIGHWAY, kingdom.cultureKey());
                connected.add(city.id());
            }

            for (PlannedSettlement city : cities) {
                List<PlannedSettlement> nearTowns = nearestUnconnected(city, towns, connected, 2, random);
                for (PlannedSettlement town : nearTowns) {
                    addRoad(seed, roadOrdinal++, roads, city, town, RoadClass.MAJOR, kingdom.cultureKey());
                    connected.add(town.id());
                }
            }

            List<PlannedSettlement> townHubs = new ArrayList<>(towns);
            townHubs.addAll(cities);
            if (townHubs.isEmpty()) townHubs.add(capital);

            for (PlannedSettlement hub : townHubs) {
                List<PlannedSettlement> nearVillages = nearestUnconnected(hub, villages, connected, 3, random);
                for (PlannedSettlement village : nearVillages) {
                    RoadClass cls = hub.tier() == SettlementTier.CITY ? RoadClass.REGIONAL : RoadClass.LOCAL;
                    addRoad(seed, roadOrdinal++, roads, hub, village, cls, kingdom.cultureKey());
                    connected.add(village.id());
                }
            }

            for (PlannedSettlement s : realm) {
                if (connected.contains(s.id())) continue;
                PlannedSettlement anchor = nearestConnected(s, realm, connected);
                if (anchor != null) {
                    addRoad(seed, roadOrdinal++, roads, anchor, s, RoadClass.TRAIL, kingdom.cultureKey());
                    connected.add(s.id());
                }
            }
        }
        return roads;
    }

    /**
     * After urban layout: snap road endpoints to settlement gates / street entries
     * so external road → gate → primary urban street.
     */
    public List<PlannedRoad> connectRoadsToUrbanFabric(
            long seed, List<PlannedRoad> roads, List<PlannedSettlement> settlements
    ) {
        Map<SettlementId, PlannedSettlement> byId = new HashMap<>();
        for (PlannedSettlement s : settlements) {
            byId.put(s.id(), s);
        }
        List<PlannedRoad> out = new ArrayList<>(roads.size());
        int ordinal = 0;
        for (PlannedRoad road : roads) {
            List<BlockPos2> path = new ArrayList<>(road.path());
            if (path.size() >= 2) {
                road.fromSettlement().map(byId::get).ifPresent(s ->
                        snapEndpoint(path, true, s));
                road.toSettlement().map(byId::get).ifPresent(s ->
                        snapEndpoint(path, false, s));
            }
            out.add(new PlannedRoad(
                    road.id() != null ? road.id() : RoadId.deterministic(seed, ordinal),
                    road.roadClass(),
                    path,
                    road.fromSettlement(),
                    road.toSettlement(),
                    road.bridges(),
                    road.cultureKey()
            ));
            ordinal++;
        }
        return out;
    }

    private void snapEndpoint(List<BlockPos2> path, boolean fromStart, PlannedSettlement s) {
        BlockPos2 target = pickGateOrEdge(s, fromStart ? path.get(0) : path.get(path.size() - 1));
        if (fromStart) {
            path.set(0, target);
            if (path.size() > 1) {
                path.add(1, BlockPos2.of(
                        (target.x() + s.center().x()) / 2,
                        (target.z() + s.center().z()) / 2
                ));
            }
        } else {
            path.set(path.size() - 1, target);
            path.add(BlockPos2.of(
                    (target.x() + s.center().x()) / 2,
                    (target.z() + s.center().z()) / 2
            ));
        }
    }

    private BlockPos2 pickGateOrEdge(PlannedSettlement s, BlockPos2 approach) {
        if (!s.gatePositions().isEmpty()) {
            return s.gatePositions().stream()
                    .min(Comparator.comparingDouble(g -> g.distanceTo(approach)))
                    .orElse(approach);
        }
        // Project approach onto footprint boundary.
        double dx = approach.x() - s.center().x();
        double dz = approach.z() - s.center().z();
        double len = Math.hypot(dx, dz);
        int radius = Math.max(s.bounds().width(), s.bounds().depth()) / 2;
        if (len < 1) return approach;
        return BlockPos2.of(
                s.center().x() + (int) (dx / len * radius),
                s.center().z() + (int) (dz / len * radius)
        );
    }

    private void addRoad(long seed, int ordinal, List<PlannedRoad> roads,
                         PlannedSettlement from, PlannedSettlement to,
                         RoadClass roadClass, String cultureKey) {
        PathResult path = findPath(seed, ordinal, from.center(), to.center());
        if (path.path.size() < 2) {
            path = straightLine(from.center(), to.center());
        }
        RoadId id = RoadId.deterministic(seed, ordinal);
        roads.add(new PlannedRoad(
                id,
                roadClass,
                path.path,
                Optional.of(from.id()),
                Optional.of(to.id()),
                path.bridges,
                cultureKey
        ));
    }

    private PathResult findPath(long seed, int ordinal, BlockPos2 start, BlockPos2 goal) {
        long fork = Hashing.mix(seed, Hashing.mix(0x50415448L, ordinal));
        DeterministicRandom tieBreak = new DeterministicRandom(fork);

        PriorityQueue<Node> open = new PriorityQueue<>(Comparator.comparingDouble(n -> n.f));
        Map<Long, Node> best = new HashMap<>();
        Node startNode = new Node(start, 0, heuristic(start, goal), null);
        open.add(startNode);
        best.put(start.packed(), startNode);

        int maxIter = 12_000;
        int iter = 0;
        Node goalNode = null;

        while (!open.isEmpty() && iter++ < maxIter) {
            Node current = open.poll();
            if (current.pos.distanceTo(goal) < STEP * 1.5) {
                goalNode = current;
                break;
            }
            for (int[] d : NEIGHBORS) {
                BlockPos2 next = current.pos.add(d[0], d[1]);
                double move = movementCost(current.pos, next);
                if (move >= 900) continue;
                double g = current.g + move;
                double h = heuristic(next, goal);
                double f = g + h + tieBreak.nextDouble() * 0.01;
                Node existing = best.get(next.packed());
                if (existing != null && g >= existing.g) continue;
                Node node = new Node(next, g, f, current);
                best.put(next.packed(), node);
                open.add(node);
            }
        }

        if (goalNode == null) {
            return new PathResult(List.of(), List.of());
        }

        List<BlockPos2> path = new ArrayList<>();
        Node n = goalNode;
        while (n != null) {
            path.add(0, n.pos);
            n = n.parent;
        }
        path.add(goal);
        List<PlannedBridge> bridges = detectBridges(path, "road");
        return new PathResult(dedupe(path), bridges);
    }

    private double movementCost(BlockPos2 from, BlockPos2 to) {
        TerrainSample a = terrain.sample(from);
        TerrainSample b = terrain.sample(to);
        double dist = from.distanceTo(to);
        double slope = (a.slope() + b.slope()) * 0.5;
        double cost = dist * (1.0 + slope * 4.0);

        if (b.water()) {
            if (a.water()) {
                cost += dist * 2.5;
            } else {
                cost += dist * 6.0;
            }
        }
        double elevDiff = Math.abs(a.elevation() - b.elevation());
        if (elevDiff > 8) {
            cost += elevDiff * 3.0;
        }
        if (!a.buildable() && !a.water()) {
            cost += 40;
        }
        if (!b.buildable() && !b.water()) {
            cost += 40;
        }
        return cost;
    }

    private static double heuristic(BlockPos2 a, BlockPos2 b) {
        return a.distanceTo(b) * 0.9;
    }

    private List<PlannedBridge> detectBridges(List<BlockPos2> path, String cultureKey) {
        List<PlannedBridge> bridges = new ArrayList<>();
        boolean inWater = false;
        BlockPos2 waterStart = null;
        for (BlockPos2 p : path) {
            boolean water = terrain.sample(p).water();
            if (water && !inWater) {
                inWater = true;
                waterStart = p;
            } else if (!water && inWater && waterStart != null) {
                inWater = false;
                PlannedBridge.BridgeKind kind = bridgeKind(waterStart, p);
                bridges.add(new PlannedBridge(waterStart, p, kind, cultureKey));
                waterStart = null;
            }
        }
        return bridges;
    }

    private PlannedBridge.BridgeKind bridgeKind(BlockPos2 start, BlockPos2 end) {
        int span = (int) start.distanceTo(end);
        if (span < 16) return PlannedBridge.BridgeKind.FORD;
        if (span < 40) return PlannedBridge.BridgeKind.WOODEN;
        if (span < 80) return PlannedBridge.BridgeKind.STONE;
        return PlannedBridge.BridgeKind.MAJOR;
    }

    private PathResult straightLine(BlockPos2 start, BlockPos2 end) {
        List<BlockPos2> path = new ArrayList<>();
        int steps = Math.max(1, (int) (start.distanceTo(end) / STEP));
        for (int i = 0; i <= steps; i++) {
            double t = i / (double) steps;
            int x = (int) Math.round(start.x() + (end.x() - start.x()) * t);
            int z = (int) Math.round(start.z() + (end.z() - start.z()) * t);
            path.add(BlockPos2.of(x, z));
        }
        return new PathResult(path, detectBridges(path, "road"));
    }

    private static List<BlockPos2> dedupe(List<BlockPos2> path) {
        List<BlockPos2> out = new ArrayList<>();
        Long prev = null;
        for (BlockPos2 p : path) {
            long pk = p.packed();
            if (prev != null && pk == prev) continue;
            out.add(p);
            prev = pk;
        }
        return out;
    }

    private record Node(BlockPos2 pos, double g, double f, Node parent) {}
    private record PathResult(List<BlockPos2> path, List<PlannedBridge> bridges) {}

    private static List<PlannedSettlement> byTier(List<PlannedSettlement> realm, SettlementTier... tiers) {
        Set<SettlementTier> set = Set.of(tiers);
        List<PlannedSettlement> out = new ArrayList<>();
        for (PlannedSettlement s : realm) {
            if (set.contains(s.tier())) out.add(s);
        }
        out.sort(Comparator.comparing(s -> s.center().packed()));
        return out;
    }

    private static List<PlannedSettlement> nearestUnconnected(
            PlannedSettlement hub, List<PlannedSettlement> candidates, Set<SettlementId> connected, int limit,
            DeterministicRandom random
    ) {
        List<PlannedSettlement> sorted = new ArrayList<>(candidates);
        sorted.removeIf(s -> connected.contains(s.id()));
        sorted.sort(Comparator.comparingDouble(s -> s.center().distanceTo(hub.center())));
        int take = Math.min(limit, sorted.size());
        List<PlannedSettlement> result = new ArrayList<>(sorted.subList(0, take));
        if (take > 1 && random.chance(0.3)) {
            result.add(sorted.get(random.nextInt(take)));
        }
        return result;
    }

    private static PlannedSettlement nearestConnected(PlannedSettlement s, List<PlannedSettlement> realm,
                                                      Set<SettlementId> connected) {
        return realm.stream()
                .filter(r -> connected.contains(r.id()))
                .min(Comparator.comparingDouble(r -> r.center().distanceTo(s.center())))
                .orElse(null);
    }

    private static Map<KingdomId, List<PlannedSettlement>> groupByKingdom(List<PlannedSettlement> settlements) {
        Map<KingdomId, List<PlannedSettlement>> map = new HashMap<>();
        for (PlannedSettlement s : settlements) {
            s.ownerKingdom().ifPresent(k -> map.computeIfAbsent(k, x -> new ArrayList<>()).add(s));
        }
        return map;
    }
}
