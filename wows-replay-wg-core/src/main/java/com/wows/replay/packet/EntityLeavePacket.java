package com.wows.replay.packet;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.wows.replay.model.EntityId;

/**
 * 0x04: 实体离开玩家 AoI。 */
public record EntityLeavePacket(
    @JsonProperty("entity_id") EntityId entityId
) {}
