package com.wows.replay.packets;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.wows.replay.core.types.EntityId;
import com.wows.replay.core.types.GameParamId;

/**
 * 0x03: 瀹炰綋杩涘叆鐜╁ AoI銆? */
public record EntityEnterPacket(
    @JsonProperty("entity_id") EntityId entityId,
    @JsonProperty("space_id") int spaceId,
    @JsonProperty("vehicle_id") GameParamId vehicleId
) {}
