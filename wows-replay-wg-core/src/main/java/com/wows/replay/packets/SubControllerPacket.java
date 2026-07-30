package com.wows.replay.packets;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 0x31: 潜艇控制器模式变更。
 */
public record SubControllerPacket(
    @JsonProperty("mode") short mode
) {}
