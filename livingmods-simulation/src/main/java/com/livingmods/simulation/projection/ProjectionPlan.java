package com.livingmods.simulation.projection;

import com.livingmods.common.geo.BlockPos2;
import com.livingmods.common.geo.BoundingBox2;
import com.livingmods.common.id.ArmyId;
import com.livingmods.common.id.CitizenId;
import com.livingmods.common.id.ShipmentId;
import com.livingmods.simulation.CanonicalWorldState;
import com.livingmods.simulation.spatial.SpatialIndex;
import com.livingmods.simulation.state.ArmyState;
import com.livingmods.simulation.state.CitizenState;
import com.livingmods.simulation.state.ShipmentState;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Describes which entities Minecraft should materialize near the player.
 */
public final class ProjectionPlan {
    private final List<CitizenId> citizens;
    private final List<ShipmentId> caravans;
    private final List<ArmyId> armies;

    public ProjectionPlan(List<CitizenId> citizens, List<ShipmentId> caravans, List<ArmyId> armies) {
        this.citizens = List.copyOf(citizens);
        this.caravans = List.copyOf(caravans);
        this.armies = List.copyOf(armies);
    }

    public List<CitizenId> citizens() { return citizens; }
    public List<ShipmentId> caravans() { return caravans; }
    public List<ArmyId> armies() { return armies; }

    public static ProjectionPlan near(CanonicalWorldState state, BlockPos2 center, int radius) {
        SpatialIndex index = state.spatialIndex();
        List<CitizenState> citizens = index.citizensNear(center, radius);
        List<CitizenId> citizenIds = new ArrayList<>();
        for (CitizenState c : citizens) {
            citizenIds.add(c.id());
        }

        BoundingBox2 box = BoundingBox2.of(
                center.x() - radius, center.z() - radius,
                center.x() + radius, center.z() + radius);

        List<ShipmentId> caravans = new ArrayList<>();
        for (ShipmentState sh : state.shipments().values()) {
            if (sh.delivered()) continue;
            int idx = Math.min(sh.routeIndex(), sh.route().size() - 1);
            BlockPos2 pos = sh.route().get(idx);
            if (box.contains(pos)) {
                caravans.add(sh.id());
            }
        }

        List<ArmyId> armies = new ArrayList<>();
        for (ArmyState army : state.armies().values()) {
            if (box.contains(army.position())) {
                armies.add(army.id());
            }
        }

        caravans.sort(Comparator.comparing(ShipmentId::toString));
        armies.sort(Comparator.comparing(ArmyId::toString));
        return new ProjectionPlan(citizenIds, caravans, armies);
    }
}
