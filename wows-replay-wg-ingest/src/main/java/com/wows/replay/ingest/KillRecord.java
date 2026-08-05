package com.wows.replay.ingest;

/** 击杀记录（直接用原始实体 id）。 */
public record KillRecord(float clock, int killerEid, int victimEid,
                         long killerDbId, String killerName,
                         long victimDbId, String victimName, int cause) {
}
