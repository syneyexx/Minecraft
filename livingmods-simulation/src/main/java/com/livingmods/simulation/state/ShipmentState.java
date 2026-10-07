package com.livingmods.simulation.state;

import com.livingmods.common.geo.BlockPos2;
import com.livingmods.common.id.ShipmentId;
import com.livingmods.common.id.SettlementId;
import com.livingmods.common.model.ResourceType;

import java.util.List;

public final class ShipmentState {
    private final ShipmentId id;
    private final SettlementId source;
    private final SettlementId destination;
    private final ResourceType goods;
    private final double quantity;
    private final List<BlockPos2> route;
    private int routeIndex;
    private boolean delivered;

    public ShipmentState(
            ShipmentId id,
            SettlementId source,
            SettlementId destination,
            ResourceType goods,
            double quantity,
            List<BlockPos2> route
    ) {
        this.id = id;
        this.source = source;
        this.destination = destination;
        this.goods = goods;
        this.quantity = quantity;
        this.route = List.copyOf(route);
        this.routeIndex = 0;
        this.delivered = false;
    }

    public ShipmentId id() { return id; }
    public SettlementId source() { return source; }
    public SettlementId destination() { return destination; }
    public ResourceType goods() { return goods; }
    public double quantity() { return quantity; }
    public List<BlockPos2> route() { return route; }
    public int routeIndex() { return routeIndex; }
    public void setRouteIndex(int routeIndex) { this.routeIndex = routeIndex; }
    public boolean delivered() { return delivered; }
    public void setDelivered(boolean delivered) { this.delivered = delivered; }
}
