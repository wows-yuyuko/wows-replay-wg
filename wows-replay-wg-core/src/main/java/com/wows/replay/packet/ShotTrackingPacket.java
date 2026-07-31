package com.wows.replay.packet;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.wows.replay.model.EntityId;

/**
 * 0x33: 灏勫嚮杩借釜鍙樻洿銆? */
public record ShotTrackingPacket(
    @JsonProperty("entity_id") EntityId entityId,
    @JsonProperty("value") long value
) {}
