package com.shinoaki.wowsreplay.ingest;

/** 已捕获的 Buff（CapturedBuff：paramsId + 捕获队伍 teamId，无实体 id）。 */
public record CapturedBuff(long paramsId, int teamId, float clock) {
}
