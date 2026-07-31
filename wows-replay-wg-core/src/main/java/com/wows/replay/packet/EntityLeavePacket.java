package com.wows.replay.packet;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.wows.replay.model.EntityId;

/**
 * 0x04: 瀹炰綋绂诲紑鐜╁ AoI銆? */
public record EntityLeavePacket(
    @JsonProperty("entity_id") EntityId entityId
) {}
