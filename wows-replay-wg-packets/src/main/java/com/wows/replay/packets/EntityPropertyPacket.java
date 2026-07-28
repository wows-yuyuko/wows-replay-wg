package com.wows.replay.packets;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.wows.replay.spec.rpc.ArgValue;
import com.wows.replay.spec.types.EntityId;

/**
 * Packet 0x07: Entity property update.
 */
public record EntityPropertyPacket(
    @JsonProperty("entity_id") EntityId entityId,
    @JsonProperty("property") String property,
    @JsonProperty("value") ArgValue value
) {}
