package com.wows.replay.packets;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.wows.replay.spec.types.EntityId;
import com.wows.replay.spec.types.Rot3;
import com.wows.replay.spec.types.Vec3;

/**
 * 0x2c（新版）/ 0x2b（旧版）: 玩家朝向更新。
 * 32 字节: entity_id(u32), parent_id(u32), position(Vec3), rotation(Rot3)。
 */
public record PlayerOrientationPacket(
    @JsonProperty("entity_id") EntityId entityId,
    @JsonProperty("parent_id") EntityId parentId,
    @JsonProperty("position") Vec3 position,
    @JsonProperty("rotation") Rot3 rotation
) {}
