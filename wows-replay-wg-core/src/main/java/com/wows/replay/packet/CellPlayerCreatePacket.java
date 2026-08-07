package com.wows.replay.packet;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.wows.replay.model.EntityId;
import com.wows.replay.model.GameParamId;
import com.wows.replay.model.Rot3;
import com.wows.replay.model.Vec3;
import com.wows.replay.types.ArgValue;

import java.util.Map;

/**
 * 0x01: Cell 玩家实体创建（含内部属性+位置）。
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
