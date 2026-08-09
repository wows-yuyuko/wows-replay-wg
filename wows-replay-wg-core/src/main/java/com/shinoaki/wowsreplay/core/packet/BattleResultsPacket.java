package com.shinoaki.wowsreplay.core.packet;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 0x22（仅新版）: 战斗结果 JSON（回放末尾附近）。
 */
public record BattleResultsPacket(
    @JsonProperty("json") String json
) {}
