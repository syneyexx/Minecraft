package com.livingmods.protocol;

/**
 * Typed player actions with real canonical handlers.
 * Ordinals are wire-stable — append only.
 */
public enum PlayerActionType {
    TALK,
    REQUEST_DIALOGUE_TOPIC,
    OPEN_INTERACTION,
    ACCEPT_TASK,
    ABANDON_TASK,
    BUY_RESOURCE,
    SELL_RESOURCE,
    JOIN_FACTION,
    LEAVE_FACTION,
    FOUND_REALM,
    SET_TAX_POLICY,
    SET_DEFENSE_POLICY,
    SET_FOOD_POLICY,
    SET_CONSTRUCTION_POLICY,
    SET_MIGRATION_POLICY,
    REQUEST_DIPLOMATIC_ACTION,
    DECLARE_SUPPORT_IN_WAR,
    PAY_FINE,
    SURRENDER_TO_GUARDS,
    DISCOVER_SETTLEMENT,
    QUERY_PLAYER_CONTEXT
}
