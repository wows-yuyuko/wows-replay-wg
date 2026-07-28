package com.wows.replay.packets;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.wows.replay.spec.rpc.ArgValue;
import com.wows.replay.spec.types.EntityId;

import java.util.List;

/**
 * Packet 0x08: Entity method invocation (RPC call).
 */
public record EntityMethodPacket(
    @JsonProperty("entity_id") EntityId entityId,
    @JsonProperty("method") String method,
    @JsonProperty("args") List<ArgValue> args
) {}
