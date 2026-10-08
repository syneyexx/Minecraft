package com.livingmods.common.model;

/**
 * Typed physical intents that map onto existing canonical systems.
 * Only types with a real simulation cause are listed.
 *
 * Realization category (exhaustive — no ambiguous members):
 * <ul>
 *   <li>{@link #isPersistentGeometry()} — block/chunk materialization via PhysicalReconciliationEngine</li>
 *   <li>{@link #isPlanningMeta()} — planning commits that spawn child geometry intents</li>
 *   <li>{@link #isTransientProjection()} — interest-based entity binders, never block-materialization success</li>
 * </ul>
 */
public enum PhysicalIntentType {
    CONSTRUCT_BUILDING,
    EXPAND_SETTLEMENT,
    CREATE_DISTRICT,
    EXTEND_ROAD,
    BUILD_BRIDGE,
    BUILD_WALL,
    BUILD_GATE,
    REPAIR_STRUCTURE,
    DAMAGE_STRUCTURE,
    DESTROY_STRUCTURE,
    CREATE_RUIN,
    CREATE_RESOURCE_SITE,
    CREATE_BANDIT_CAMP,
    UPGRADE_BANDIT_CAMP,
    REMOVE_BANDIT_CAMP,
    CREATE_FORTIFICATION,
    SIEGE_DAMAGE,
    PROJECT_CARAVAN,
    PROJECT_ARMY,
    PROJECT_GUARDS,
    PROJECT_REFUGEES,
    PROJECT_MIGRANTS,
    FOUND_PLAYER_SETTLEMENT;

    /** Block/chunk work handled by PhysicalReconciliationEngine materializers. */
    public boolean isPersistentGeometry() {
        return switch (this) {
            case CONSTRUCT_BUILDING, EXTEND_ROAD, BUILD_BRIDGE, BUILD_WALL, BUILD_GATE,
                 REPAIR_STRUCTURE, DAMAGE_STRUCTURE, DESTROY_STRUCTURE, CREATE_RUIN,
                 CREATE_RESOURCE_SITE, CREATE_BANDIT_CAMP, UPGRADE_BANDIT_CAMP,
                 REMOVE_BANDIT_CAMP, CREATE_FORTIFICATION, SIEGE_DAMAGE -> true;
            case EXPAND_SETTLEMENT, CREATE_DISTRICT, FOUND_PLAYER_SETTLEMENT,
                 PROJECT_CARAVAN, PROJECT_ARMY, PROJECT_GUARDS, PROJECT_REFUGEES, PROJECT_MIGRANTS -> false;
        };
    }

    /**
     * Higher-level geometry planning: produces child intents / dynamic urban plans.
     * Must not materialize as a single giant building.
     */
    public boolean isPlanningMeta() {
        return switch (this) {
            case EXPAND_SETTLEMENT, CREATE_DISTRICT, FOUND_PLAYER_SETTLEMENT -> true;
            default -> false;
        };
    }

    /** Interest-based entity projection — never treated as block-slice completion. */
    public boolean isTransientProjection() {
        return switch (this) {
            case PROJECT_CARAVAN, PROJECT_ARMY, PROJECT_GUARDS, PROJECT_REFUGEES, PROJECT_MIGRANTS -> true;
            default -> false;
        };
    }

    /** Creates a DynamicStructureRecord on successful materialization. */
    public boolean createsStructureRecord() {
        return switch (this) {
            case CONSTRUCT_BUILDING, REPAIR_STRUCTURE, CREATE_RESOURCE_SITE,
                 CREATE_BANDIT_CAMP, UPGRADE_BANDIT_CAMP, CREATE_FORTIFICATION,
                 CREATE_RUIN -> true;
            default -> false;
        };
    }
}
