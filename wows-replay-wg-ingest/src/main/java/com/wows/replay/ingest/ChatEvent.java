package com.wows.replay.ingest;

/** 聊天消息事件。{@code metaId} 是发送者的战斗内 meta id（= players 表 key）。 */
public record ChatEvent(float clock, int entityId, long metaId,
                        String senderName, String channel, String message) {
}
