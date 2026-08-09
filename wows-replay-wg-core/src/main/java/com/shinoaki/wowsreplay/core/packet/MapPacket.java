package com.shinoaki.wowsreplay.core.packet;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 0x28: 地图/竞技场信息。 */
public record MapPacket(
    @JsonProperty("space_id") int spaceId,
    @JsonProperty("arena_id") long arenaId,
    @JsonProperty("unknown1") int unknown1,
    @JsonProperty("unknown2") int unknown2,
    @JsonProperty("blob") byte[] blob,
    @JsonProperty("map_name") String mapName,
    @JsonProperty("unknown") int unknown
) {}
