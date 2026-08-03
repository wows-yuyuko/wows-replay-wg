package com.wows.replay.ingest.report;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 聊天消息（对标 Rust {@code GameMessage}，report.rs §2.1 ChatLog）。
 */
public record GameMessage(
    @JsonProperty("clock") float clock,
    @JsonProperty("sender_db_id") long senderDbId,
    @JsonProperty("sender_name") String senderName,
    @JsonProperty("audience") String audience,
    @JsonProperty("message") String message
) {}
