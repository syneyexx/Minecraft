package com.livingmods.simulation.state;

import com.livingmods.common.id.SettlementId;
import com.livingmods.common.model.ResourceType;

import java.util.EnumMap;
import java.util.Map;

public final class MarketState {
    public static final double MIN_PRICE = 0.05;
    public static final double MAX_PRICE_MULT = 12.0;

    private final SettlementId settlementId;
    private final Map<ResourceType, Double> prices;
    private final Map<ResourceType, Double> production;
    private final Map<ResourceType, Double> consumption;
    private final Map<ResourceType, Double> imports;
    private final Map<ResourceType, Double> exports;
    private double crisisSeverity;
    private double transportCostFactor;
    private double taxPressure;
    private double riskPressure;
    private double warPressure;
    private double diseasePressure;
    private double securityResponse;

    public MarketState(SettlementId settlementId) {
        this.settlementId = settlementId;
        this.prices = new EnumMap<>(ResourceType.class);
        this.production = new EnumMap<>(ResourceType.class);
        this.consumption = new EnumMap<>(ResourceType.class);
        this.imports = new EnumMap<>(ResourceType.class);
        this.exports = new EnumMap<>(ResourceType.class);
        for (ResourceType t : ResourceType.values()) {
            prices.put(t, basePrice(t));
            production.put(t, 0.0);
            consumption.put(t, 0.0);
            imports.put(t, 0.0);
            exports.put(t, 0.0);
        }
        this.crisisSeverity = 0.0;
        this.transportCostFactor = 1.0;
        this.taxPressure = 0.0;
        this.riskPressure = 0.0;
        this.warPressure = 0.0;
        this.diseasePressure = 0.0;
        this.securityResponse = 0.0;
    }

    public static double basePrice(ResourceType type) {
        return switch (type) {
            case GRAIN, VEGETABLES, FOOD -> 1.0;
            case MEAT, FISH, LIVESTOCK -> 2.0;
            case WOOD, STONE, CONSTRUCTION -> 1.5;
            case IRON_ORE -> 2.0;
            case IRON, COAL, FUEL -> 3.0;
            case GOLD, LUXURY -> 10.0;
            case TOOLS, WEAPONS, ARMOR -> 5.0;
            case CLOTH, TEXTILES -> 2.5;
            case MEDICINE -> 4.0;
            case KNOWLEDGE -> 6.0;
            case WATER -> 0.5;
        };
    }

    /** Clamp runaway prices relative to base. */
    public static double clampPrice(ResourceType type, double price) {
        double base = basePrice(type);
        return Math.max(MIN_PRICE, Math.min(base * MAX_PRICE_MULT, price));
    }

    public SettlementId settlementId() { return settlementId; }
    public Map<ResourceType, Double> prices() { return prices; }
    public double price(ResourceType type) { return prices.getOrDefault(type, basePrice(type)); }

    public void setPrice(ResourceType type, double price) {
        prices.put(type, clampPrice(type, price));
    }

    public Map<ResourceType, Double> production() { return production; }
    public Map<ResourceType, Double> consumption() { return consumption; }
    public Map<ResourceType, Double> imports() { return imports; }
    public Map<ResourceType, Double> exports() { return exports; }

    public void setFlow(ResourceType type, double prod, double cons, double imp, double exp) {
        production.put(type, prod);
        consumption.put(type, cons);
        imports.put(type, imp);
        exports.put(type, exp);
    }

    public void addImport(ResourceType type, double qty) {
        imports.put(type, imports.getOrDefault(type, 0.0) + qty);
    }

    public void addExport(ResourceType type, double qty) {
        exports.put(type, exports.getOrDefault(type, 0.0) + qty);
    }

    public double crisisSeverity() { return crisisSeverity; }
    public void setCrisisSeverity(double crisisSeverity) {
        this.crisisSeverity = Math.max(0.0, Math.min(1.0, crisisSeverity));
    }

    public double transportCostFactor() { return transportCostFactor; }
    public void setTransportCostFactor(double transportCostFactor) {
        this.transportCostFactor = Math.max(0.5, Math.min(4.0, transportCostFactor));
    }

    public double taxPressure() { return taxPressure; }
    public void setTaxPressure(double taxPressure) {
        this.taxPressure = Math.max(0.0, Math.min(2.0, taxPressure));
    }

    public double riskPressure() { return riskPressure; }
    public void setRiskPressure(double riskPressure) {
        this.riskPressure = Math.max(0.0, Math.min(2.0, riskPressure));
    }

    public double warPressure() { return warPressure; }
    public void setWarPressure(double warPressure) {
        this.warPressure = Math.max(0.0, Math.min(2.0, warPressure));
    }

    public double diseasePressure() { return diseasePressure; }
    public void setDiseasePressure(double diseasePressure) {
        this.diseasePressure = Math.max(0.0, Math.min(2.0, diseasePressure));
    }

    public double securityResponse() { return securityResponse; }
    public void setSecurityResponse(double securityResponse) {
        this.securityResponse = Math.max(0.0, Math.min(1.0, securityResponse));
    }
}
