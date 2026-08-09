package com.shinoaki.wowsreplay.core.packet;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.shinoaki.wowsreplay.core.model.EntityId;

/**
 * 0x04: 实体离开玩家 AoI。 */
public record EntityLeavePacket(
    @JsonProperty("entity_id") EntityId entityId
) {}
