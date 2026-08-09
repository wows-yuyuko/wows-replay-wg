package com.shinoaki.wowsreplay.core.packet;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.shinoaki.wowsreplay.core.model.EntityId;
import com.shinoaki.wowsreplay.core.model.GameParamId;
import com.shinoaki.wowsreplay.core.model.Rot3;
import com.shinoaki.wowsreplay.core.model.Vec3;
import com.shinoaki.wowsreplay.core.types.ArgValue;

import java.util.Map;

/**
 * 0x05: 实体创建。
 */
public record EntityCreatePacket(
    @JsonProperty("entity_id") EntityId entityId,
    @JsonProperty("spec_idx") int specIdx,
    @JsonProperty("entity_type") String entityType,
    @JsonProperty("space_id") int spaceId,
    @JsonProperty("vehicle_id") GameParamId vehicleId,
    @JsonProperty("position") Vec3 position,
    @JsonProperty("rotation") Rot3 rotation,
    @JsonProperty("state_length") int stateLength,
    @JsonProperty("props") Map<String, ArgValue> props
) {}
