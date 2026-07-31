package com.wows.replay.packet;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.wows.replay.model.EntityId;

/**
 * 0x20: 灏?Avatar 鍏宠仈鍒板叾鎺у埗鐨勮埌鑸瑰疄浣撱€? */
public record OwnShipPacket(
    @JsonProperty("entity_id") EntityId entityId
) {}
