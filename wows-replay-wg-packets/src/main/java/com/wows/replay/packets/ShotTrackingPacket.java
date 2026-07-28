package com.wows.replay.packets;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.wows.replay.spec.types.EntityId;

/**
 * 0x33: 射击追踪变更。
 */
public record ShotTrackingPacket(
    @JsonProperty("entity_id") EntityId entityId,
    @JsonProperty("value") long value
) {}
