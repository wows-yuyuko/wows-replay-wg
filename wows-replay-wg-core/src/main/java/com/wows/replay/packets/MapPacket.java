package com.wows.replay.packets;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.wows.replay.core.types.EntityId;

/**
 * 0x28: 鍦板浘/绔炴妧鍦轰俊鎭€? */
public record MapPacket(
    @JsonProperty("space_id") int spaceId,
    @JsonProperty("arena_id") long arenaId,
    @JsonProperty("unknown1") int unknown1,
    @JsonProperty("unknown2") int unknown2,
    @JsonProperty("blob") byte[] blob,
    @JsonProperty("map_name") String mapName,
    @JsonProperty("unknown") int unknown
) {}
