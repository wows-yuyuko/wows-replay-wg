package com.wows.replay.packets;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.wows.replay.core.rpc.ArgValue;
import com.wows.replay.core.types.EntityId;

/**
 * 0x07: 瀹炰綋灞炴€ф洿鏂般€? */
public record EntityPropertyPacket(
    @JsonProperty("entity_id") EntityId entityId,
    @JsonProperty("property") String property,
    @JsonProperty("value") ArgValue value
) {}
