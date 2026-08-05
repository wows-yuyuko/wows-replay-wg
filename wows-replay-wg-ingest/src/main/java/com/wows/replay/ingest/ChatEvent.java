package com.wows.replay.ingest;

/** 聊天消息事件。 */
public record ChatEvent(float clock, int entityId, long dbId,
                        String senderName, String channel, String message) {
}
