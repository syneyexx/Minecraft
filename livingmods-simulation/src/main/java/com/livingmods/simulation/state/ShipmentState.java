package com.livingmods.simulation.state;

import com.livingmods.common.geo.BlockPos2;
import com.livingmods.common.id.RoadId;
import com.livingmods.common.id.ShipmentId;
import com.livingmods.common.id.SettlementId;
import com.livingmods.common.model.ResourceType;

import java.util.ArrayList;
import java.util.List;

/**
 * Canonical caravan / shipment. Physical caravan entities are projections of this record.
 */
public final class ShipmentState {
    private final ShipmentId id;
    private final SettlementId source;
    private final SettlementId destination;
    private ResourceType goods;
    private double quantity;
    private final List<BlockPos2> route;
    private final List<RoadId> roadSegments;
    private int routeIndex;
    private boolean delivered;
    private boolean looted;
    private int guards;
    private double risk;
    private long etaDay;
    private long departedDay;

    public ShipmentState(
            ShipmentId id,
            SettlementId source,
            SettlementId destination,
            ResourceType goods,
            double quantity,
            List<BlockPos2> route
    ) {
        this(id, source, destination, goods, quantity, route, List.of(), 0, 0.1, 0L, 0L);
    }

    public ShipmentState(
            ShipmentId id,
            SettlementId source,
            SettlementId destination,
            ResourceType goods,
            double quantity,
            List<BlockPos2> route,
            List<RoadId> roadSegments,
            int guards,
            double risk,
            long departedDay,
            long etaDay
    ) {
        this.id = id;
        this.source = source;
        this.destination = destination;
        this.goods = goods;
        this.quantity = quantity;
        this.route = List.copyOf(route);
        this.roadSegments = List.copyOf(roadSegments);
        this.routeIndex = 0;
        this.delivered = false;
        this.looted = false;
        this.guards = Math.max(0, guards);
        this.risk = Math.max(0.0, Math.min(1.0, risk));
        this.departedDay = departedDay;
        this.etaDay = etaDay;
    }

    public ShipmentId id() { return id; }
    public SettlementId source() { return source; }
    public SettlementId destination() { return destination; }
    public ResourceType goods() { return goods; }
    public void setGoods(ResourceType goods) { this.goods = goods; }
    public double quantity() { return quantity; }
    public void setQuantity(double quantity) { this.quantity = Math.max(0.0, quantity); }
    public List<BlockPos2> route() { return route; }
    public List<RoadId> roadSegments() { return roadSegments; }
    public int routeIndex() { return routeIndex; }
    public void setRouteIndex(int routeIndex) { this.routeIndex = Math.max(0, routeIndex); }
    public boolean delivered() { return delivered; }
    public void setDelivered(boolean delivered) { this.delivered = delivered; }
    public boolean looted() { return looted; }
    public void setLooted(boolean looted) { this.looted = looted; }
    public int guards() { return guards; }
    public void setGuards(int guards) { this.guards = Math.max(0, guards); }
    public double risk() { return risk; }
    public void setRisk(double risk) { this.risk = Math.max(0.0, Math.min(1.0, risk)); }
    public long etaDay() { return etaDay; }
    public void setEtaDay(long etaDay) { this.etaDay = etaDay; }
    public long departedDay() { return departedDay; }
    public void setDepartedDay(long departedDay) { this.departedDay = departedDay; }

    public double progress() {
        if (route.isEmpty()) return 1.0;
        return Math.min(1.0, routeIndex / (double) Math.max(1, route.size() - 1));
    }

    public BlockPos2 currentPosition() {
        if (route.isEmpty()) return BlockPos2.of(0, 0);
        int idx = Math.min(routeIndex, route.size() - 1);
        return route.get(idx);
    }

    public List<RoadId> mutableRoadSegmentsCopy() {
        return new ArrayList<>(roadSegments);
    }
}
