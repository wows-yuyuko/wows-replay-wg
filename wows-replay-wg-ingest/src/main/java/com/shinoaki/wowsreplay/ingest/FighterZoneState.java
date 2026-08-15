package com.shinoaki.wowsreplay.ingest;

/**
 * 战斗机巡逻圈（InteractiveZone {@code type=12}）。
 *
 * <p>{@code ownerId} = 拥有该巡逻圈的船实体 id（不是玩家 meta id）；{@code leftTime} = EntityCreate
 * 时剩余巡逻时间（秒）；{@code clock} = 实体创建时刻（raw clock，与 frames 同轴）。</p>
 */
public record FighterZoneState(int entityId, float x, float z, float radius,
                               int teamId, int ownerId, float leftTime, float clock) {
}
