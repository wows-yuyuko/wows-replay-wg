package com.shinoaki.wowsreplay.ingest;

/**
 * 军备竞赛掉落计划（{@code state.drop.data} 的 SetRange 条目）。
 *
 * <p>{@code id} = 掉落事件序号；{@code zoneId} = 掉落点实体 id；{@code paramsId} = 将掉落的
 * Buff（powerup）GameParamId；{@code isContested} = 该掉落是否处于争夺中（服务器下发）；
 * {@code startTime} = 计划掉落时间（游戏内秒，服务器下发）；
 * {@code clock} = 该计划被解析时的 raw clock（回放基准，与 frames/captured_buffs 同轴）。
 * 这是「哪个点何时掉什么 buff」的权威信息；掉出后的 buff 拾取物（powerup）实体不含 paramsId。</p>
 */
public record DropEvent(long id, int zoneId, long paramsId, boolean isContested, float startTime, float clock) {
}
