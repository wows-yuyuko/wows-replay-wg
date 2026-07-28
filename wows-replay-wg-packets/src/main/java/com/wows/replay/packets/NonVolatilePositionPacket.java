package com.wows.replay.packets;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.wows.replay.spec.types.EntityId;
import com.wows.replay.spec.types.Rot3;
import com.wows.replay.spec.types.Vec3;

/**
 * Packet 0x2a (modern) / 0x29 (legacy): Non-volatile entity position update.
 * Same format as Position but without direction vector and is_on_ground.
 */
public record NonVolatilePositionPacket(
    @JsonProperty("entity_id") EntityId entityId,
    @JsonProperty("space_id") int spaceId,
    @JsonProperty("position") Vec3 position,
    @JsonProperty("rotation") Rot3 rotation
) {}
