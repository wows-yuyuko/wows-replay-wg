package com.wows.replay.packets;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.wows.replay.spec.types.EntityId;

/**
 * Packet 0x20: Links Avatar to its owned ship entity.
 */
public record OwnShipPacket(
    @JsonProperty("entity_id") EntityId entityId
) {}
