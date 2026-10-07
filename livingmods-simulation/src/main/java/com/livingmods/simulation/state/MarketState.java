package com.livingmods.simulation.state;

import com.livingmods.common.id.SettlementId;
import com.livingmods.common.model.ResourceType;

import java.util.EnumMap;
import java.util.Map;

public final class MarketState {
    private final SettlementId settlementId;
    private final Map<ResourceType, Double> prices;
    private double crisisSeverity;

    public MarketState(SettlementId settlementId) {
        this.settlementId = settlementId;
        this.prices = new EnumMap<>(ResourceType.class);
        for (ResourceType t : ResourceType.values()) {
            prices.put(t, basePrice(t));
        }
        this.crisisSeverity = 0.0;
    }

    public static double basePrice(ResourceType type) {
        return switch (type) {
            case GRAIN, VEGETABLES -> 1.0;
            case MEAT, FISH -> 2.0;
            case WOOD, STONE -> 1.5;
            case IRON, COAL -> 3.0;
            case GOLD, LUXURY -> 10.0;
            case TOOLS, WEAPONS, ARMOR -> 5.0;
            case MEDICINE -> 4.0;
            case KNOWLEDGE -> 6.0;
            default -> 2.0;
        };
    }

    public SettlementId settlementId() { return settlementId; }
    public Map<ResourceType, Double> prices() { return prices; }
    public double price(ResourceType type) { return prices.getOrDefault(type, basePrice(type)); }
    public void setPrice(ResourceType type, double price) { prices.put(type, Math.max(0.01, price)); }
    public double crisisSeverity() { return crisisSeverity; }
    public void setCrisisSeverity(double crisisSeverity) { this.crisisSeverity = crisisSeverity; }
}
