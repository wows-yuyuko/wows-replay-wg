package com.wows.replay.ingest;

/** 伤害事件。 */
public record DamageEvent(float clock, int aggressorId, int victimId, float amount) {
}
