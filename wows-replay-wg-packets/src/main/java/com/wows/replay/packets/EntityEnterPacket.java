package com.wows.replay.packets;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.wows.replay.spec.types.EntityId;
import com.wows.replay.spec.types.GameParamId;

/**
 * Packet 0x03: Entity entering the player's AoI.
 */
public record EntityEnterPacket(
    @JsonProperty("entity_id") EntityId entityId,
    @JsonProperty("space_id") int spaceId,
    @JsonProperty("vehicle_id") GameParamId vehicleId
) {}
