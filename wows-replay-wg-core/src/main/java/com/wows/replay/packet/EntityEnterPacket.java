package com.wows.replay.packet;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.wows.replay.model.EntityId;
import com.wows.replay.model.GameParamId;

/**
 * 0x03: 瀹炰綋杩涘叆鐜╁ AoI銆? */
public record EntityEnterPacket(
    @JsonProperty("entity_id") EntityId entityId,
    @JsonProperty("space_id") int spaceId,
    @JsonProperty("vehicle_id") GameParamId vehicleId
) {}
