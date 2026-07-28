package com.wows.replay.packets;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.wows.replay.spec.rpc.ArgValue;
import com.wows.replay.spec.types.EntityId;
import com.wows.replay.spec.types.GameParamId;
import com.wows.replay.spec.types.Rot3;
import com.wows.replay.spec.types.Vec3;

import java.util.Map;

/**
 * Packet 0x01: Cell player entity creation (with internal properties + position).
 */
public record CellPlayerCreatePacket(
    @JsonProperty("entity_id") EntityId entityId,
    @JsonProperty("entity_type") String entityType,
    @JsonProperty("space_id") int spaceId,
    @JsonProperty("vehicle_id") GameParamId vehicleId,
    @JsonProperty("position") Vec3 position,
    @JsonProperty("rotation") Rot3 rotation,
    @JsonProperty("props") Map<String, ArgValue> props,
    @JsonProperty("component_data") byte[] componentData
) {}
