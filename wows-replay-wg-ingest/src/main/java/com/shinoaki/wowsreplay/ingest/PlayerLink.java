package com.shinoaki.wowsreplay.ingest;

/** entity_id → 战斗内玩家（meta id, username）链接。 */
public record PlayerLink(long metaId, String username) {
}
