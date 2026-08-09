package com.shinoaki.wowsreplay.core.model;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 战斗内玩家 meta id（u32）— 竞技场玩家状态 pickle 的 {@code id} 字段
 * （对应 {@code PlayerStateData.KEY_ID} / {@code PlayerStateData.metaShipId()}），
 * 与 {@code ReplayMeta.vehicles[].id} 同属一个命名空间。
 *
 * <p>注意：它<strong>不是</strong>账号 ID。真正的账号 ID 是玩家状态里的
 * {@code accountDBID} 键（{@code PlayerStateData.dbId()}），录制者的账号 ID 则是
 * {@code ReplayMeta.playerID}。这里只是沿用线上 {@code id} 字段名。</p>
 */
public record MetaId(int value) implements Comparable<MetaId> {

    @JsonValue
    public int value() { return value; }

    @Override
    public int compareTo(MetaId o) { return Integer.compare(value, o.value); }

    @Override
    public String toString() { return String.valueOf(value); }
}
