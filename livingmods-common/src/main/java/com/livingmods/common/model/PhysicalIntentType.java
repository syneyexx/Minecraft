package com.livingmods.common.model;

/**
 * Typed physical intents that map onto existing canonical systems.
 * Only types with a real simulation cause are listed.
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
    FOUND_PLAYER_SETTLEMENT
}
