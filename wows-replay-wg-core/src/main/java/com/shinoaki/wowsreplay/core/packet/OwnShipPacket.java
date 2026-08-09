package com.shinoaki.wowsreplay.core.packet;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.shinoaki.wowsreplay.core.model.EntityId;

/**
 * 0x20: 将 Avatar 关联到其控制的舰船实体。 */
public record OwnShipPacket(
    @JsonProperty("entity_id") EntityId entityId
) {}
