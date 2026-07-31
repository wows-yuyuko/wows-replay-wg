package com.wows.replay.packet;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.wows.replay.model.EntityId;

/**
 * 0x23(新版)/ 0x22(旧版): 嵌套属性更新。用于更新复杂类型的子属性。 */
public record PropertyUpdatePacket(
    @JsonProperty("entity_id") EntityId entityId,
    @JsonProperty("property") String property,
    @JsonProperty("update_cmd") Object updateCmd
) {}
