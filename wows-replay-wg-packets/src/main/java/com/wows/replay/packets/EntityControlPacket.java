package com.wows.replay.packets;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.wows.replay.spec.types.EntityId;

/**
 * Packet 0x02: Entity control transfer (ownership).
 */
public record EntityControlPacket(
    @JsonProperty("entity_id") EntityId entityId,
    @JsonProperty("is_controlled") boolean isControlled
) {}
