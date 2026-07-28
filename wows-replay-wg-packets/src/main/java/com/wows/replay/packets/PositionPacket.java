package com.wows.replay.packets;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.wows.replay.spec.types.EntityId;
import com.wows.replay.spec.types.Rot3;
import com.wows.replay.spec.types.Vec3;

/**
 * Packet 0x0a: Entity position update. Written every tick for each tracked entity.
 */
public record PositionPacket(
    @JsonProperty("entity_id") EntityId entityId,
    @JsonProperty("space_id") int spaceId,
    @JsonProperty("position") Vec3 position,
    @JsonProperty("direction") Vec3 direction,
    @JsonProperty("rotation") Rot3 rotation,
    @JsonProperty("is_on_ground") boolean isOnGround
) {}
