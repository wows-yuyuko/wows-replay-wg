package com.shinoaki.wowsreplay.ingest;

import com.shinoaki.wowsreplay.core.decode.DecodedPayload;

/** 消耗品激活事件。{@code metaId} 是使用者的战斗内 meta id（= players 表 key）。 */
public record ConsumableEvent(float clock, int entityId, long metaId,
                              String username, long consumableId, float duration,
                              DecodedPayload.ConsumableKind kind) {
}
