package com.wows.replay.packets;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.wows.replay.spec.rpc.ArgValue;
import com.wows.replay.spec.types.EntityId;

import java.util.Map;

/**
 * 0x00: 基础玩家实体创建（含基础属性）。
 */
public record BasePlayerCreatePacket(
    @JsonProperty("entity_id") EntityId entityId,
    @JsonProperty("entity_type") String entityType,
    @JsonProperty("props") Map<String, ArgValue> props,
    @JsonProperty("component_data") byte[] componentData
) {}
