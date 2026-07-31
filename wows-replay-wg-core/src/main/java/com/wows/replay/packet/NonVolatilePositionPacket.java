package com.wows.replay.packet;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.wows.replay.model.EntityId;
import com.wows.replay.model.Rot3;
import com.wows.replay.model.Vec3;

/**
 * 0x2a(新版)/ 0x29(旧版): 非易失性实体位置更新。与 Position 格式相同，但无方向向量和 is_on_ground。 */
public record NonVolatilePositionPacket(
    @JsonProperty("entity_id") EntityId entityId,
    @JsonProperty("space_id") int spaceId,
    @JsonProperty("position") Vec3 position,
    @JsonProperty("rotation") Rot3 rotation
) {}
