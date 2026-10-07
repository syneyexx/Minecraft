package com.livingmods.simulation.projection;

import com.livingmods.common.geo.BlockPos2;
import com.livingmods.common.geo.BoundingBox2;
import com.livingmods.common.id.ArmyId;
import com.livingmods.common.id.CitizenId;
import com.livingmods.common.id.ShipmentId;
import com.livingmods.common.model.ScheduleState;
import com.livingmods.common.util.Hashing;
import com.livingmods.simulation.CanonicalWorldState;
import com.livingmods.simulation.spatial.SpatialIndex;
import com.livingmods.simulation.state.ArmyState;
import com.livingmods.simulation.state.CitizenState;
import com.livingmods.simulation.state.SettlementState;
import com.livingmods.simulation.state.ShipmentState;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Describes which entities Minecraft should materialize near the player.
 * Interest-based LOD — never a 1:1 population projection.
 */
public final class ProjectionPlan {
    private final List<ProjectedCitizen> citizens;
    private final List<ShipmentId> caravans;
    private final List<ArmyId> armies;
    private final long revision;
    private final int budget;

    public ProjectionPlan(
            List<ProjectedCitizen> citizens,
            List<ShipmentId> caravans,
            List<ArmyId> armies,
            long revision,
            int budget
    ) {
        this.citizens = List.copyOf(citizens);
        this.caravans = List.copyOf(caravans);
        this.armies = List.copyOf(armies);
        this.revision = revision;
        this.budget = budget;
    }

    /** Legacy constructor for callers that only need IDs. */
    public ProjectionPlan(List<CitizenId> citizens, List<ShipmentId> caravans, List<ArmyId> armies) {
        this(citizens.stream().map(id -> new ProjectedCitizen(id, "", ScheduleState.HOME, 0, 0, 0, 0L)).toList(),
                caravans, armies, 0L, citizens.size());
    }

    public List<ProjectedCitizen> projectedCitizens() { return citizens; }
    public List<CitizenId> citizens() {
        List<CitizenId> ids = new ArrayList<>(citizens.size());
        for (ProjectedCitizen c : citizens) {
            ids.add(c.citizenId());
        }
        return ids;
    }
    public List<ShipmentId> caravans() { return caravans; }
    public List<ArmyId> armies() { return armies; }
    public long revision() { return revision; }
    public int budget() { return budget; }

    public static ProjectionPlan near(CanonicalWorldState state, BlockPos2 center, int radius) {
        return near(state, center, radius, 48, 1);
    }

    /**
     * @param entityBudget hard cap on projected citizens
     * @param nearbyPlayers number of players contributing interest density
     */
    public static ProjectionPlan near(
            CanonicalWorldState state,
            BlockPos2 center,
            int radius,
            int entityBudget,
            int nearbyPlayers
    ) {
        SpatialIndex index = state.spatialIndex();
        List<CitizenState> nearby = index.citizensNear(center, radius);
        nearby.sort(Comparator.comparing(c -> c.id().value()));

        int settlementBoost = 0;
        for (SettlementState s : state.settlements().values()) {
            if (s.center().distanceTo(center) <= radius) {
                settlementBoost += switch (s.tier()) {
                    case CAMP -> 1;
                    case HAMLET -> 2;
                    case VILLAGE -> 4;
                    case TOWN -> 8;
                    case CITY -> 12;
                    case METROPOLIS -> 14;
                    case CAPITAL -> 16;
                };
            }
        }

        int density = Math.max(1, nearbyPlayers);
        int activityBonus = 0;
        for (CitizenState c : nearby) {
            if (c.schedule() == ScheduleState.MARKET || c.schedule() == ScheduleState.GUARD_DUTY) {
                activityBonus++;
            }
        }
        int budget = Math.min(entityBudget, Math.max(4,
                Math.min(nearby.size(), 6 * density + settlementBoost / 2 + Math.min(8, activityBonus / 4))));

        List<ProjectedCitizen> selected = new ArrayList<>();
        // Prefer active / notable citizens for the bounded budget.
        List<CitizenState> ranked = new ArrayList<>(nearby);
        ranked.sort(Comparator
                .comparingInt((CitizenState c) -> schedulePriority(c.schedule()))
                .thenComparing(c -> c.ruler() ? 0 : 1)
                .thenComparing(c -> c.id().value()));

        long maxRevision = state.saveRevision();
        for (CitizenState c : ranked) {
            if (selected.size() >= budget) break;
            if (!c.alive()) continue;
            SettlementState s = state.settlements().get(c.settlementId());
            BlockPos2 pos = s == null ? center : offsetForCitizen(c, s.center());
            selected.add(new ProjectedCitizen(
                    c.id(),
                    c.displayName(),
                    c.schedule(),
                    pos.x(),
                    64,
                    pos.z(),
                    c.projectionRevision()
            ));
            maxRevision = Math.max(maxRevision, c.projectionRevision());
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
        return new ProjectionPlan(selected, caravans, armies, maxRevision, budget);
    }

    private static int schedulePriority(ScheduleState schedule) {
        return switch (schedule) {
            case GUARD_DUTY, WORK, MARKET -> 0;
            case SOCIAL, RELIGIOUS, COMMUTE -> 1;
            case HOME, TRAVEL -> 2;
            case SLEEP -> 3;
        };
    }

    private static BlockPos2 offsetForCitizen(CitizenState c, BlockPos2 center) {
        long h = Hashing.mix(c.id().value().getMostSignificantBits(), c.id().value().getLeastSignificantBits());
        int dx = (int) (h % 24) - 12;
        int dz = (int) ((h >>> 8) % 24) - 12;
        return BlockPos2.of(center.x() + dx, center.z() + dz);
    }

    public record ProjectedCitizen(
            CitizenId citizenId,
            String displayName,
            ScheduleState schedule,
            int x,
            int y,
            int z,
            long projectionRevision
    ) {}
}
