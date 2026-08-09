package com.shinoaki.wowsreplay.core.packet;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.shinoaki.wowsreplay.core.model.EntityId;
import com.shinoaki.wowsreplay.core.model.Rot3;
import com.shinoaki.wowsreplay.core.model.Vec3;

/**
 * 0x0a: 实体位置更新。每个 tick 为每个跟踪实体写入。 */
public record PositionPacket(
    @JsonProperty("entity_id") EntityId entityId,
    @JsonProperty("space_id") int spaceId,
    @JsonProperty("position") Vec3 position,
    @JsonProperty("direction") Vec3 direction,
    @JsonProperty("rotation") Rot3 rotation,
    @JsonProperty("is_on_ground") boolean isOnGround
) {}
