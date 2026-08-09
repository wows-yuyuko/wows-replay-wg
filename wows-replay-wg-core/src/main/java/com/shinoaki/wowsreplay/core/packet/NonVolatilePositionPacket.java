package com.shinoaki.wowsreplay.core.packet;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.shinoaki.wowsreplay.core.model.EntityId;
import com.shinoaki.wowsreplay.core.model.Rot3;
import com.shinoaki.wowsreplay.core.model.Vec3;

/**
 * 0x2a(新版)/ 0x29(旧版): 非易失性实体位置更新。与 Position 格式相同，但无方向向量和 is_on_ground。 */
public record NonVolatilePositionPacket(
    @JsonProperty("entity_id") EntityId entityId,
    @JsonProperty("space_id") int spaceId,
    @JsonProperty("position") Vec3 position,
    @JsonProperty("rotation") Rot3 rotation
) {}
