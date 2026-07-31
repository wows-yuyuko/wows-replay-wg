package com.wows.replay.packet;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 0x10: 初始化标志（clock=0 时恒为 0）。
 */
public record InitFlagPacket(
    @JsonProperty("flag") int flag
) {}
