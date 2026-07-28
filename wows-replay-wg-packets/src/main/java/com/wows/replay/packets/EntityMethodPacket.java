package com.wows.replay.packets;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.wows.replay.spec.rpc.ArgValue;
import com.wows.replay.spec.types.EntityId;

import java.util.List;

/**
 * 0x08: 实体方法调用（RPC）。
 */
public record EntityMethodPacket(
    @JsonProperty("entity_id") EntityId entityId,
    @JsonProperty("method") String method,
    @JsonProperty("args") List<ArgValue> args
) {}
