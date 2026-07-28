package com.wows.replay.packets;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.wows.replay.spec.types.EntityId;

/**
 * Packet 0x33: Shot tracking change.
 */
public record ShotTrackingPacket(
    @JsonProperty("entity_id") EntityId entityId,
    @JsonProperty("value") long value
) {}
