package com.wows.replay.packets;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.wows.replay.spec.types.EntityId;

/**
 * Packet 0x23 (modern) / 0x22 (legacy): Nested property update.
 * Used to update sub-properties of complex types.
 */
public record PropertyUpdatePacket(
    @JsonProperty("entity_id") EntityId entityId,
    @JsonProperty("property") String property,
    @JsonProperty("update_cmd") Object updateCmd
) {}
