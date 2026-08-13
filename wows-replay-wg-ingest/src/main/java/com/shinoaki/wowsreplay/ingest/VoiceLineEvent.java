package com.shinoaki.wowsreplay.ingest;

import com.shinoaki.wowsreplay.core.model.AccountId;
import com.shinoaki.wowsreplay.core.model.VoiceLine;

/** 语音指令事件。 */
public record VoiceLineEvent(float clock, AccountId senderId,
                             boolean isGlobal, VoiceLine voiceLine) {
}
