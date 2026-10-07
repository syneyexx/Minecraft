package com.livingmods.worldgen.planner;

import com.livingmods.common.culture.CultureDefinition;
import com.livingmods.common.model.SettlementTier;

/**
 * Scales settlement footprints by population / density / wealth / culture —
 * not tiny fixed radii for thousands of people.
 */
public final class SettlementFootprint {
    private SettlementFootprint() {}

    /**
     * Target residential building count to house {@code population}.
     * Assumes ~3.5 residents per dwelling, adjusted by culture density prefs.
     */
    public static int housingBuildingTarget(int population, CultureDefinition culture) {
        double residentsPerHouse = culture.architecture().layoutStyle() == CultureDefinition.LayoutStyle.GRID
                ? 3.2 : 3.8;
        double windowBonus = culture.architecture().windowDensity() > 0.4 ? 0.9 : 1.0;
        return Math.max(4, (int) Math.ceil((population / residentsPerHouse) * windowBonus));
    }

    /** People per block² inside the settlement footprint circle. */
    public static double residentialDensity(SettlementTier tier, CultureDefinition culture, double densityScale) {
        double base = switch (tier) {
            case CAMP -> 0.04;
            case HAMLET -> 0.06;
            case VILLAGE -> 0.09;
            case TOWN -> 0.12;
            case CITY -> 0.16;
            case METROPOLIS, CAPITAL -> 0.20;
        };
        if (culture.architecture().layoutStyle() == CultureDefinition.LayoutStyle.GRID) {
            base *= 1.15;
        } else if (culture.architecture().layoutStyle() == CultureDefinition.LayoutStyle.ORGANIC_RADIAL) {
            base *= 0.9;
        }
        return Math.max(0.03, base * Math.max(0.25, densityScale));
    }

    public static int footprintRadius(
            SettlementTier tier,
            int population,
            CultureDefinition culture,
            double densityScale
    ) {
        double density = residentialDensity(tier, culture, densityScale);
        // Include streets/plazas/public buildings (~40% overhead).
        double effectiveArea = (population / density) * 1.4;
        int radius = (int) Math.ceil(Math.sqrt(effectiveArea / Math.PI));
        int min = Math.max(tier.footprintRadius(), 16);
        int max = switch (tier) {
            case CAMP, HAMLET -> 96;
            case VILLAGE -> 160;
            case TOWN -> 280;
            case CITY -> 420;
            case METROPOLIS, CAPITAL -> 560;
        };
        return Math.max(min, Math.min(max, radius));
    }

    public static int primaryStreetSpacing(CultureDefinition culture) {
        return culture.architecture().preferStraightStreets() ? 10 : 12;
    }

    public static int primaryStreetWidth(CultureDefinition culture, SettlementTier tier) {
        int base = culture.architecture().preferStraightStreets() ? 3 : 2;
        if (tier.isUrban() || tier.isCapitalClass()) base += 1;
        return base;
    }
}
