package com.shinoaki.wowsreplay.ingest;

import com.shinoaki.wowsreplay.core.decode.DecodedPayload;
import com.shinoaki.wowsreplay.core.model.AvatarId;
import com.shinoaki.wowsreplay.core.model.Vec3;

/** 炮弹命中记录（含受害者命中时刻位置）。 */
public record ShotHitRecord(float clock, AvatarId avatarId,
                            DecodedPayload.ShotHitEntry hit,
                            Vec3 victimPosition) {
}
