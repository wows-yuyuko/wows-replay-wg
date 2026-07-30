package com.wows.replay.packets;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.wows.replay.core.rpc.ArgValue;
import com.wows.replay.core.types.EntityId;

import java.util.Map;

/**
 * 0x00: 鍩虹鐜╁瀹炰綋鍒涘缓锛堝惈鍩虹灞炴€э級銆? */
public record BasePlayerCreatePacket(
    @JsonProperty("entity_id") EntityId entityId,
    @JsonProperty("entity_type") String entityType,
    @JsonProperty("props") Map<String, ArgValue> props,
    @JsonProperty("component_data") byte[] componentData
) {}
