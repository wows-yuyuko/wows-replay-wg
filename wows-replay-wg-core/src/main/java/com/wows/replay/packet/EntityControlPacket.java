package com.wows.replay.packet;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.wows.replay.model.EntityId;

/**
 * 0x02: 瀹炰綋鎺у埗鏉冭浆绉汇€? */
public record EntityControlPacket(
    @JsonProperty("entity_id") EntityId entityId,
    @JsonProperty("is_controlled") boolean isControlled
) {}
