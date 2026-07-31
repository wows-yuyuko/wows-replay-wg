package com.wows.replay.packet;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.wows.replay.model.EntityId;
import com.wows.replay.model.Rot3;
import com.wows.replay.model.Vec3;

/**
 * 0x2a锛堟柊鐗堬級/ 0x29锛堟棫鐗堬級: 闈炴槗澶辨€у疄浣撲綅缃洿鏂般€? * 涓?Position 鏍煎紡鐩稿悓锛屼絾鏃犳柟鍚戝悜閲忓拰 is_on_ground銆? */
public record NonVolatilePositionPacket(
    @JsonProperty("entity_id") EntityId entityId,
    @JsonProperty("space_id") int spaceId,
    @JsonProperty("position") Vec3 position,
    @JsonProperty("rotation") Rot3 rotation
) {}
