package com.wows.replay.packets;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.wows.replay.core.rpc.ArgValue;
import com.wows.replay.core.types.EntityId;

import java.util.List;

/**
 * 0x08: 瀹炰綋鏂规硶璋冪敤锛圧PC锛夈€? */
public record EntityMethodPacket(
    @JsonProperty("entity_id") EntityId entityId,
    @JsonProperty("method") String method,
    @JsonProperty("args") List<ArgValue> args
) {}
