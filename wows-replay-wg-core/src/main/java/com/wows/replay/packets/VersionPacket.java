package com.wows.replay.packets;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 0x16: 版本字符串。
 */
public record VersionPacket(
    @JsonProperty("version") String version
) {}
