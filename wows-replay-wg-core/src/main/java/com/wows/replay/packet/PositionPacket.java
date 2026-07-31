package com.wows.replay.packet;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.wows.replay.model.EntityId;
import com.wows.replay.model.Rot3;
import com.wows.replay.model.Vec3;

/**
 * 0x0a: 瀹炰綋浣嶇疆鏇存柊銆傛瘡涓?tick 涓烘瘡涓拷韪疄浣撳啓鍏ャ€? */
public record PositionPacket(
    @JsonProperty("entity_id") EntityId entityId,
    @JsonProperty("space_id") int spaceId,
    @JsonProperty("position") Vec3 position,
    @JsonProperty("direction") Vec3 direction,
    @JsonProperty("rotation") Rot3 rotation,
    @JsonProperty("is_on_ground") boolean isOnGround
) {}
