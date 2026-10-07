package com.livingmods.simulation.spatial;

import com.livingmods.common.geo.BlockPos2;
import com.livingmods.common.geo.BoundingBox2;
import com.livingmods.common.id.CitizenId;
import com.livingmods.common.id.SettlementId;
import com.livingmods.simulation.CanonicalWorldState;
import com.livingmods.simulation.state.CitizenState;
import com.livingmods.simulation.state.SettlementState;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Maintained spatial queries over live {@link CanonicalWorldState} indexes.
 * Cell buckets rebuild only when {@link CanonicalWorldState#spatialEpoch()} changes —
 * never on every projection query.
 */
public final class SpatialIndex {
    public static final int MAX_RESULTS = 256;
    public static final int DEFAULT_QUERY_RADIUS = 512;
    private static final int CELL_SIZE = 512;

    private final CanonicalWorldState state;
    private long builtEpoch = Long.MIN_VALUE;
    private final Map<Long, List<SettlementId>> cells = new HashMap<>();

    public SpatialIndex(CanonicalWorldState state) {
        this.state = state;
    }

    public List<SettlementState> settlementsNear(BlockPos2 center, int radius) {
        int r = Math.min(Math.max(0, radius), DEFAULT_QUERY_RADIUS);
        ensureCells();
        BoundingBox2 box = BoundingBox2.of(
                center.x() - r, center.z() - r,
                center.x() + r, center.z() + r);
        int minCX = Math.floorDiv(center.x() - r, CELL_SIZE);
        int maxCX = Math.floorDiv(center.x() + r, CELL_SIZE);
        int minCZ = Math.floorDiv(center.z() - r, CELL_SIZE);
        int maxCZ = Math.floorDiv(center.z() + r, CELL_SIZE);

        List<SettlementState> hits = new ArrayList<>();
        for (int cx = minCX; cx <= maxCX; cx++) {
            for (int cz = minCZ; cz <= maxCZ; cz++) {
                List<SettlementId> bucket = cells.get(cellKey(cx, cz));
                if (bucket == null) {
                    continue;
                }
                for (SettlementId id : bucket) {
                    SettlementState s = state.settlements().get(id);
                    if (s != null && box.contains(s.center())) {
                        hits.add(s);
                    }
                }
            }
        }
        hits.sort(Comparator.comparing(s -> s.center().distanceTo(center)));
        if (hits.size() > MAX_RESULTS) {
            return new ArrayList<>(hits.subList(0, MAX_RESULTS));
        }
        return hits;
    }

    public List<CitizenState> citizensNear(BlockPos2 center, int radius) {
        List<SettlementState> nearby = settlementsNear(center, radius);
        Map<SettlementId, Double> settlementDist = new HashMap<>();
        for (SettlementState s : nearby) {
            settlementDist.put(s.id(), s.center().distanceTo(center));
        }

        List<CitizenState> hits = new ArrayList<>();
        for (SettlementState s : nearby) {
            Set<CitizenId> ids = state.citizensBySettlement().get(s.id());
            if (ids == null) {
                continue;
            }
            Double d = settlementDist.get(s.id());
            if (d == null || d > radius) {
                continue;
            }
            for (CitizenId id : ids) {
                CitizenState c = state.citizens().get(id);
                if (c != null && c.alive()) {
                    hits.add(c);
                }
            }
        }
        hits.sort(Comparator.comparing(c -> settlementDist.getOrDefault(c.settlementId(), Double.MAX_VALUE)));
        if (hits.size() > MAX_RESULTS) {
            return new ArrayList<>(hits.subList(0, MAX_RESULTS));
        }
        return hits;
    }

    private void ensureCells() {
        long epoch = state.spatialEpoch();
        if (epoch == builtEpoch) {
            return;
        }
        cells.clear();
        for (SettlementState s : state.settlements().values()) {
            long key = cellKey(
                    Math.floorDiv(s.center().x(), CELL_SIZE),
                    Math.floorDiv(s.center().z(), CELL_SIZE));
            cells.computeIfAbsent(key, ignored -> new ArrayList<>()).add(s.id());
        }
        builtEpoch = epoch;
    }

    private static long cellKey(int cx, int cz) {
        return (((long) cx) << 32) ^ (cz & 0xffffffffL);
    }
}
