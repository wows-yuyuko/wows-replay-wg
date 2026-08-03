package com.wows.replay.packet;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.wows.replay.model.EntityId;

/**
 * 0x02: 实体控制权转移。 */
public record EntityControlPacket(
    @JsonProperty("entity_id") EntityId entityId,
    @JsonProperty("is_controlled") boolean isControlled
) {}
