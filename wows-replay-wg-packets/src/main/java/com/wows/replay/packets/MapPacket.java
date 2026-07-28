package com.wows.replay.packets;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.wows.replay.spec.types.EntityId;

/**
 * Packet 0x28: Map / arena info.
 */
public record MapPacket(
    @JsonProperty("space_id") int spaceId,
    @JsonProperty("arena_id") long arenaId,
    @JsonProperty("unknown1") int unknown1,
    @JsonProperty("unknown2") int unknown2,
    @JsonProperty("blob") byte[] blob,
    @JsonProperty("map_name") String mapName,
    @JsonProperty("unknown") int unknown
) {}
