package com.wows.replay.ingest;

/** 击杀记录（直接用原始实体 id；killer/victimMetaId 是战斗内 meta id，= players 表 key）。 */
public record KillRecord(float clock, int killerEid, int victimEid,
                         long killerMetaId, String killerName,
                         long victimMetaId, String victimName, int cause) {
}
