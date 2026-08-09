package com.shinoaki.wowsreplay.ingest;

import com.shinoaki.wowsreplay.core.model.GameParamId;

/** 中队状态。 */
public record PlaneState(long planeId, int ownerEntityId, int teamId,
                         GameParamId paramsId,
                         float x, float z, float addedAt, float lastUpdateAt) {
    public PlaneState withPosition(float nx, float nz, float t) {
        return new PlaneState(planeId, ownerEntityId, teamId, paramsId, nx, nz, addedAt, t);
    }
}
