package com.wows.replay.packets;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.wows.replay.spec.types.EntityId;
import com.wows.replay.spec.types.Rot3;
import com.wows.replay.spec.types.Vec3;

/**
 * 0x0a: 实体位置更新。每个 tick 为每个追踪实体写入。
 */
public record PositionPacket(
    @JsonProperty("entity_id") EntityId entityId,
    @JsonProperty("space_id") int spaceId,
    @JsonProperty("position") Vec3 position,
    @JsonProperty("direction") Vec3 direction,
    @JsonProperty("rotation") Rot3 rotation,
    @JsonProperty("is_on_ground") boolean isOnGround
) {}
