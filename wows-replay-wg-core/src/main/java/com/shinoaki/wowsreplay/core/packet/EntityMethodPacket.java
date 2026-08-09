package com.shinoaki.wowsreplay.core.packet;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.shinoaki.wowsreplay.core.model.EntityId;

/**
 * 0x08: Entity method call (RPC).
 */
public record EntityMethodPacket(
    @JsonProperty("entity_id") EntityId entityId,
    @JsonProperty("method") String method,
    @JsonProperty("args") NamedArgs args
) {}
