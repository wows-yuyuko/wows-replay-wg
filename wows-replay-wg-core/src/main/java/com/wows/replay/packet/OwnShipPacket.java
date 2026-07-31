package com.wows.replay.packet;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.wows.replay.model.EntityId;

/**
 * 0x20: 将 Avatar 关联到其控制的舰船实体。 */
public record OwnShipPacket(
    @JsonProperty("entity_id") EntityId entityId
) {}
