package com.shinoaki.wowsreplay.ingest;

/** 建筑状态。 */
public record BuildingState(int entityId, float x, float z, int teamId,
                            long paramsId, boolean isAlive) {
}
