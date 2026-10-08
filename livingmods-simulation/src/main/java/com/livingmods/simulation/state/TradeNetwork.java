package com.livingmods.simulation.state;

import com.livingmods.common.geo.BlockPos2;
import com.livingmods.common.id.RoadId;
import com.livingmods.common.id.SettlementId;
import com.livingmods.worldgen.plan.PlannedRoad;
import com.livingmods.worldgen.plan.WorldPlan;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;

/**
 * Settlement adjacency over planned roads. Shipments must traverse RoadId segments
 * (or sampled corridor waypoints when no planned road exists).
 */
public final class TradeNetwork {
    public record RoadEdge(
            RoadId roadId,
            SettlementId from,
            SettlementId to,
            List<BlockPos2> path,
            double length
    ) {}

    public record RoutedPath(
            List<BlockPos2> waypoints,
            List<RoadId> roadSegments,
            double length
    ) {}

    private final Map<SettlementId, List<RoadEdge>> adjacency = new LinkedHashMap<>();
    private final List<RoadEdge> edges = new ArrayList<>();
    private final List<BlockPos2> banditHotspots = new ArrayList<>();

    public Map<SettlementId, List<RoadEdge>> adjacency() { return adjacency; }
    public List<RoadEdge> edges() { return edges; }
    public List<BlockPos2> banditHotspots() { return banditHotspots; }

    public void clear() {
        adjacency.clear();
        edges.clear();
        banditHotspots.clear();
    }

    public void loadFromWorldPlan(WorldPlan plan) {
        clear();
        for (PlannedRoad road : plan.roads()) {
            if (road.fromSettlement().isEmpty() || road.toSettlement().isEmpty()) continue;
            SettlementId a = road.fromSettlement().get();
            SettlementId b = road.toSettlement().get();
            List<BlockPos2> path = road.path() == null || road.path().isEmpty()
                    ? List.of()
                    : List.copyOf(road.path());
            double len = pathLength(path);
            RoadEdge forward = new RoadEdge(road.id(), a, b, path, len);
            RoadEdge reverse = new RoadEdge(road.id(), b, a, reversePath(path), len);
            edges.add(forward);
            adjacency.computeIfAbsent(a, id -> new ArrayList<>()).add(forward);
            adjacency.computeIfAbsent(b, id -> new ArrayList<>()).add(reverse);
        }
        if (plan.banditCamps() != null) {
            for (var camp : plan.banditCamps()) {
                banditHotspots.add(camp.center());
            }
        }
    }

    public Optional<RoutedPath> findRoute(SettlementId source, SettlementId dest,
                                          BlockPos2 sourceCenter, BlockPos2 destCenter) {
        if (source.equals(dest)) {
            return Optional.of(new RoutedPath(List.of(sourceCenter), List.of(), 0));
        }
        Optional<RoutedPath> roadPath = bfsRoadPath(source, dest);
        if (roadPath.isPresent()) {
            return roadPath;
        }
        return Optional.of(sampledCorridor(sourceCenter, destCenter));
    }

    private Optional<RoutedPath> bfsRoadPath(SettlementId source, SettlementId dest) {
        if (!adjacency.containsKey(source)) return Optional.empty();
        Map<SettlementId, RoadEdge> cameVia = new HashMap<>();
        Set<SettlementId> visited = new HashSet<>();
        Queue<SettlementId> q = new ArrayDeque<>();
        q.add(source);
        visited.add(source);
        while (!q.isEmpty()) {
            SettlementId cur = q.poll();
            if (cur.equals(dest)) {
                return Optional.of(reconstruct(source, dest, cameVia));
            }
            for (RoadEdge edge : adjacency.getOrDefault(cur, List.of())) {
                if (visited.add(edge.to())) {
                    cameVia.put(edge.to(), edge);
                    q.add(edge.to());
                }
            }
        }
        return Optional.empty();
    }

    private RoutedPath reconstruct(SettlementId source, SettlementId dest, Map<SettlementId, RoadEdge> cameVia) {
        List<RoadEdge> chain = new ArrayList<>();
        SettlementId cur = dest;
        while (!cur.equals(source)) {
            RoadEdge edge = cameVia.get(cur);
            if (edge == null) break;
            chain.add(edge);
            cur = edge.from();
        }
        Collections.reverse(chain);
        List<BlockPos2> waypoints = new ArrayList<>();
        List<RoadId> roads = new ArrayList<>();
        double length = 0;
        for (RoadEdge edge : chain) {
            roads.add(edge.roadId());
            length += edge.length();
            for (BlockPos2 p : edge.path()) {
                if (waypoints.isEmpty() || !waypoints.getLast().equals(p)) {
                    waypoints.add(p);
                }
            }
        }
        if (waypoints.isEmpty()) {
            waypoints.add(BlockPos2.of(0, 0));
        }
        return new RoutedPath(List.copyOf(waypoints), List.copyOf(roads), length);
    }

    /** Intermediate waypoints — never just [source, dest]. */
    public static RoutedPath sampledCorridor(BlockPos2 source, BlockPos2 dest) {
        double dist = Math.max(1.0, source.distanceTo(dest));
        int steps = Math.max(3, (int) Math.ceil(dist / 64.0) + 1);
        List<BlockPos2> points = new ArrayList<>(steps + 1);
        for (int i = 0; i <= steps; i++) {
            double t = i / (double) steps;
            int x = (int) Math.round(source.x() + (dest.x() - source.x()) * t);
            int z = (int) Math.round(source.z() + (dest.z() - source.z()) * t);
            points.add(BlockPos2.of(x, z));
        }
        return new RoutedPath(List.copyOf(points), List.of(), dist);
    }

    public void addHotspot(BlockPos2 position) {
        if (position == null) return;
        for (BlockPos2 existing : banditHotspots) {
            if (existing.distanceTo(position) < 24) {
                return;
            }
        }
        if (banditHotspots.size() < 512) {
            banditHotspots.add(position);
        }
    }

    public double hotspotRisk(BlockPos2 position) {
        double risk = 0.05;
        for (BlockPos2 camp : banditHotspots) {
            double d = position.distanceTo(camp);
            if (d < 96) {
                risk += (96 - d) / 96.0 * 0.35;
            }
        }
        return Math.min(0.85, risk);
    }

    private static double pathLength(List<BlockPos2> path) {
        if (path.size() < 2) return 0;
        double len = 0;
        for (int i = 1; i < path.size(); i++) {
            len += path.get(i - 1).distanceTo(path.get(i));
        }
        return len;
    }

    private static List<BlockPos2> reversePath(List<BlockPos2> path) {
        if (path.isEmpty()) return List.of();
        List<BlockPos2> rev = new ArrayList<>(path);
        Collections.reverse(rev);
        return List.copyOf(rev);
    }
}
