package com.shinoaki.wowsreplay.ingest;

/**
 * 军备竞赛掉落计划（{@code state.drop.data} 的 SetRange 条目）。
 *
 * <p>{@code id} = 掉落事件序号；{@code zoneId} = 掉落点实体 id；{@code paramsId} = 将掉落的
 * Buff（powerup）GameParamId；{@code startTime} = 计划掉落时间（游戏内秒）。这是「哪个点何时
 * 掉什么 buff」的权威信息；掉出后的 buff 拾取物（powerup）实体不含 paramsId。</p>
 */
public record DropEvent(long id, int zoneId, long paramsId, float startTime) {
}
