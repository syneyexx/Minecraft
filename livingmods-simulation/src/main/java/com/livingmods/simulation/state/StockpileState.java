package com.livingmods.simulation.state;

import com.livingmods.common.id.SettlementId;
import com.livingmods.common.model.ResourceType;

import java.util.EnumMap;
import java.util.Map;

public final class StockpileState {
    private final SettlementId settlementId;
    private final Map<ResourceType, Double> quantities;

    public StockpileState(SettlementId settlementId) {
        this.settlementId = settlementId;
        this.quantities = new EnumMap<>(ResourceType.class);
    }

    public StockpileState(SettlementId settlementId, Map<ResourceType, Double> quantities) {
        this.settlementId = settlementId;
        this.quantities = new EnumMap<>(ResourceType.class);
        this.quantities.putAll(quantities);
    }

    public SettlementId settlementId() { return settlementId; }
    public Map<ResourceType, Double> quantities() { return quantities; }

    public double get(ResourceType type) {
        return quantities.getOrDefault(type, 0.0);
    }

    public void set(ResourceType type, double amount) {
        quantities.put(type, Math.max(0.0, amount));
    }

    public void add(ResourceType type, double delta) {
        set(type, get(type) + delta);
    }
}
