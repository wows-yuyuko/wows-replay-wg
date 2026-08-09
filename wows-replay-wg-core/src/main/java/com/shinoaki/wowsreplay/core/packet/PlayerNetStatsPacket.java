package com.shinoaki.wowsreplay.core.packet;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 0x1d: 玩家网络统计（每 tick）。
 * 将 fps, ping, isLagging 打包为单个 u32。
 */
public record PlayerNetStatsPacket(
    @JsonProperty("fps") int fps,
    @JsonProperty("ping") int ping,
    @JsonProperty("is_lagging") boolean isLagging
) {}
