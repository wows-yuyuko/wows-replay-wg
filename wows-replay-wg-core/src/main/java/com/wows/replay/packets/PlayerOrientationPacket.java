package com.wows.replay.packets;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.wows.replay.core.types.EntityId;
import com.wows.replay.core.types.Rot3;
import com.wows.replay.core.types.Vec3;

/**
 * 0x2c锛堟柊鐗堬級/ 0x2b锛堟棫鐗堬級: 鐜╁鏈濆悜鏇存柊銆? * 32 瀛楄妭: entity_id(u32), parent_id(u32), position(Vec3), rotation(Rot3)銆? */
public record PlayerOrientationPacket(
    @JsonProperty("entity_id") EntityId entityId,
    @JsonProperty("parent_id") EntityId parentId,
    @JsonProperty("position") Vec3 position,
    @JsonProperty("rotation") Rot3 rotation
) {}
