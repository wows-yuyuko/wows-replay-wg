package com.shinoaki.wowsreplay.core.packet;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.shinoaki.wowsreplay.core.model.EntityId;
import com.shinoaki.wowsreplay.core.types.ArgValue;

/**
 * 0x07: 实体属性更新。 */
public record EntityPropertyPacket(
    @JsonProperty("entity_id") EntityId entityId,
    @JsonProperty("property") String property,
    @JsonProperty("value") ArgValue value
) {}
