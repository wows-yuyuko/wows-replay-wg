package com.shinoaki.wowsreplay.core.packet;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.shinoaki.wowsreplay.core.model.EntityId;
import com.shinoaki.wowsreplay.core.model.GameParamId;
import com.shinoaki.wowsreplay.core.model.Rot3;
import com.shinoaki.wowsreplay.core.model.Vec3;
import com.shinoaki.wowsreplay.core.types.ArgValue;

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
