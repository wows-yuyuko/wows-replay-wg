package com.wows.replay.ingest;

import com.wows.replay.model.EntityId;
import com.wows.replay.model.Vec3;

/** 巡逻战斗机/警戒区状态。 */
public record WardState(long wardId, EntityId entityId,
                        EntityId ownerId,
                        Vec3 position,
                        float radius, float addedAt) {
}
