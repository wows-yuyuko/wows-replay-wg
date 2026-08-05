package com.wows.replay.ingest;

import com.wows.replay.decode.DecodedPayload;
import com.wows.replay.model.AvatarId;
import com.wows.replay.model.Vec3;

/** 炮弹命中记录（含受害者命中时刻位置）。 */
public record ShotHitRecord(float clock, AvatarId avatarId,
                            DecodedPayload.ShotHitEntry hit,
                            Vec3 victimPosition) {
}
