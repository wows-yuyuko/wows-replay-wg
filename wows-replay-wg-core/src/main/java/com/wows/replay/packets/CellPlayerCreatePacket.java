package com.wows.replay.packets;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.wows.replay.core.rpc.ArgValue;
import com.wows.replay.core.types.EntityId;
import com.wows.replay.core.types.GameParamId;
import com.wows.replay.core.types.Rot3;
import com.wows.replay.core.types.Vec3;

import java.util.Map;

/**
 * 0x01: Cell 鐜╁瀹炰綋鍒涘缓锛堝惈鍐呴儴灞炴€?浣嶇疆锛夈€? */
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
