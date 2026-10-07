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
 * Does not copy entity maps on construction or query.
 */
public final class SpatialIndex {
    public static final int MAX_RESULTS = 256;
    public static final int DEFAULT_QUERY_RADIUS = 512;

    private final CanonicalWorldState state;

    public SpatialIndex(CanonicalWorldState state) {
        this.state = state;
    }

    public List<SettlementState> settlementsNear(BlockPos2 center, int radius) {
        int r = Math.min(radius, DEFAULT_QUERY_RADIUS);
        BoundingBox2 box = BoundingBox2.of(
                center.x() - r, center.z() - r,
                center.x() + r, center.z() + r);
        List<SettlementState> hits = new ArrayList<>();
        for (SettlementState s : state.settlements().values()) {
            if (box.contains(s.center())) {
                hits.add(s);
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
        hits.sort(Comparator.comparing(c -> settlementDist.get(c.settlementId())));
        if (hits.size() > MAX_RESULTS) {
            return new ArrayList<>(hits.subList(0, MAX_RESULTS));
        }
        return hits;
    }
}
