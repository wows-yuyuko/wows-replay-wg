package com.shinoaki.wowsreplay.ingest;

import com.shinoaki.wowsreplay.core.model.MetaId;
import com.shinoaki.wowsreplay.core.model.VoiceLine;

/** 语音指令事件。senderId 是战斗内 meta id（非账号 ID）。 */
public record VoiceLineEvent(float clock, MetaId senderId,
                             boolean isGlobal, VoiceLine voiceLine) {
}
