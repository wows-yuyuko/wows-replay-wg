package com.shinoaki.wowsreplay.ingest.mapped;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 规范化聊天消息（映射层输出）。发送者用 {@code metaId}（全局一致）。
 */
public record NormalizedChat(
    @JsonProperty("clock") float clock,
    @JsonProperty("meta_id") long metaId,
    @JsonProperty("username") String username,
    @JsonProperty("channel") String channel,
    @JsonProperty("message") String message
) {}
