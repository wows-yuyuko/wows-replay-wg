package com.wows.replay.packet;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.wows.replay.types.ArgValue;
import com.wows.replay.model.EntityId;

/**
 * 0x07: 实体属性更新。 */
public record EntityPropertyPacket(
    @JsonProperty("entity_id") EntityId entityId,
    @JsonProperty("property") String property,
    @JsonProperty("value") ArgValue value
) {}
