package com.wows.replay.packets;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.wows.replay.spec.types.EntityId;

/**
 * Packet 0x04: Entity leaving the player's AoI.
 */
public record EntityLeavePacket(
    @JsonProperty("entity_id") EntityId entityId
) {}
