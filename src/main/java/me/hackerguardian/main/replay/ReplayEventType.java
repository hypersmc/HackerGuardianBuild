package me.hackerguardian.main.replay;
// NEVER put a new event type in front of others.
// IT will mess up older recordings.
// if new is needed add it at the end!
public enum ReplayEventType {
    PLAYER_SNAPSHOT,
    ARM_SWING,
    SNEAK_TOGGLE,
    SPRINT_TOGGLE,
    ITEM_CONSUME,
    INVENTORY_CLICK,
    ITEM_DROP,
    ITEM_PICKUP,
    PROJECTILE_LAUNCH,
    PROJECTILE_HIT,
    BLOCK_BREAK,
    BLOCK_PLACE,

    NEARBY_SNAPSHOT
}
