package com.wows.replay.ingest;

import com.wows.replay.model.AccountId;

/** 语音指令事件。 */
public record VoiceLineEvent(float clock, AccountId senderId,
                             boolean isGlobal, String message) {
}
