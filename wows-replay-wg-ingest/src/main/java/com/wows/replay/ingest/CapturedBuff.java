package com.wows.replay.ingest;

/** 已捕获的 Buff。 */
public record CapturedBuff(int entityId, long paramsId, int capturedBy, float clock) {
}
