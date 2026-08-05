package com.wows.replay.ingest;

/** Buff 掉落区。 */
public record BuffZoneState(int entityId, float x, float z, float radius,
                            int teamId, boolean isActive, Long dropParamsId) {
}
