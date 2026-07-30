package com.wows.replay.packets;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.wows.replay.core.types.EntityId;

/**
 * 0x02: 瀹炰綋鎺у埗鏉冭浆绉汇€? */
public record EntityControlPacket(
    @JsonProperty("entity_id") EntityId entityId,
    @JsonProperty("is_controlled") boolean isControlled
) {}
