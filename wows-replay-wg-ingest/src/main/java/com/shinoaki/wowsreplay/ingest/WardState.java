package com.shinoaki.wowsreplay.ingest;

import com.shinoaki.wowsreplay.core.model.EntityId;
import com.shinoaki.wowsreplay.core.model.Vec3;

/** 巡逻战斗机/警戒区状态。 */
public record WardState(long wardId, EntityId entityId,
                        EntityId ownerId,
                        Vec3 position,
                        float radius, float addedAt) {
}
