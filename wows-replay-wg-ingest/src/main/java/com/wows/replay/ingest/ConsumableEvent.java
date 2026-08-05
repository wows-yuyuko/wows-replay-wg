package com.wows.replay.ingest;

/** 消耗品激活事件。 */
public record ConsumableEvent(float clock, int entityId, long dbId,
                              String username, long consumableId, float duration) {
}
