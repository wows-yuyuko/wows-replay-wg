package com.shinoaki.wowsreplay.ingest;

/** Buff 掉落区（clock = 实体创建时刻，用于记录掉落区出现历史）。 */
public record BuffZoneState(int entityId, float x, float z, float radius,
                            int teamId, boolean isActive, Long dropParamsId, float clock) {
}
