package com.shinoaki.wowsreplay.ingest;

import com.shinoaki.wowsreplay.core.decode.DecodedPayload;
import com.shinoaki.wowsreplay.core.model.GameParamId;

/**
 * 中队状态。
 *
 * <p>{@code squadronState} 来自 {@code receive_addSquadron} 的完整血量/状态；
 * 尚未收到该事件（或该视角未下发完整数据）时为 null。</p>
 */
public record PlaneState(long planeId, int ownerEntityId, int teamId,
                         GameParamId paramsId,
                         float x, float z, float addedAt, float lastUpdateAt,
                         DecodedPayload.SquadronState squadronState) {

    public PlaneState withPosition(float nx, float nz, float t) {
        return new PlaneState(planeId, ownerEntityId, teamId, paramsId, nx, nz, addedAt, t, squadronState);
    }

    public PlaneState withSquadronState(DecodedPayload.SquadronState s) {
        return new PlaneState(planeId, ownerEntityId, teamId, paramsId, x, z, addedAt, lastUpdateAt, s);
    }

    public PlaneState withHealthPart(float part) {
        return squadronState == null ? this : withSquadronState(squadronState.withHealthPart(part));
    }

    public PlaneState withPlaneHealth(long ph) {
        return squadronState == null ? this : withSquadronState(squadronState.withPlaneHealth(ph));
    }
}
