package com.wows.replay.packets;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.wows.replay.spec.types.EntityId;
import com.wows.replay.spec.types.Rot3;
import com.wows.replay.spec.types.Vec3;

/**
 * Packet 0x2c (modern) / 0x2b (legacy): Player orientation update.
 * 32 bytes: entity_id(u32), parent_id(u32), position(Vec3), rotation(Rot3).
 */
public record PlayerOrientationPacket(
    @JsonProperty("entity_id") EntityId entityId,
    @JsonProperty("parent_id") EntityId parentId,
    @JsonProperty("position") Vec3 position,
    @JsonProperty("rotation") Rot3 rotation
) {}
