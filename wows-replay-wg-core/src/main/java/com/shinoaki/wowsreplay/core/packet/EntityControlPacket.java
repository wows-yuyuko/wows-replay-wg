package com.shinoaki.wowsreplay.core.packet;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.shinoaki.wowsreplay.core.model.EntityId;

/**
 * 0x02: 实体控制权转移。 */
public record EntityControlPacket(
    @JsonProperty("entity_id") EntityId entityId,
    @JsonProperty("is_controlled") boolean isControlled
) {}
