package com.wows.replay.packet;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.wows.replay.model.EntityId;

/**
 * 0x08: Entity method call (RPC).
 */
public record EntityMethodPacket(
    @JsonProperty("entity_id") EntityId entityId,
    @JsonProperty("method") String method,
    @JsonProperty("args") NamedArgs args
) {}
