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
    QUERY_PLAYER_CONTEXT,
    /** M5: request exact integer GOLD_INGOT market quote (session-bound). */
    MARKET_QUOTE,
    /** M5: commit a previously issued market quote (single-use). */
    MARKET_COMMIT,
    /** M5: typed task journal snapshot for the requesting player. */
    QUERY_TASK_JOURNAL,
    /** M5: validate founding eligibility/cost without mutating state. */
    PREVIEW_FOUND_REALM,
    /** M5: query exact outstanding fine for a jurisdiction. */
    QUERY_FINE
}
