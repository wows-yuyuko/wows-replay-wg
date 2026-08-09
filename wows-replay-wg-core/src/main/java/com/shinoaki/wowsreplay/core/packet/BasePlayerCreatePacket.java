package com.shinoaki.wowsreplay.core.packet;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.shinoaki.wowsreplay.core.model.EntityId;
import com.shinoaki.wowsreplay.core.types.ArgValue;

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
