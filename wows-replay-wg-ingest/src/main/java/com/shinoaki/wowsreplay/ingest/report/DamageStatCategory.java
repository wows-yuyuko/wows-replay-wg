package com.shinoaki.wowsreplay.ingest.report;

import com.shinoaki.wowsreplay.core.constant.GameConstants;
import com.shinoaki.wowsreplay.core.model.Version;

/**
 * 伤害统计类别（DamageStatCategory）。
 * 从 {@code receiveDamageStat} pickle 的 categoryId 解析：
 * DAMAGE_STATS_ENEMY=0, DAMAGE_STATS_ALLY=1, DAMAGE_STATS_SPOT=2, DAMAGE_STATS_AGRO=3.
 *
 * <p>枚举常量集是固定的<b>类型契约</b>（战报 {@code category} 的序列化形态）；
 * id→常量 的解析委托 {@link GameConstants#damageStatCategoryName}
 * （统一布局管理器：规范表 → 外部 provider → 原始回退），按名称匹配枚举常量。</p>
 */
public enum DamageStatCategory {
    Enemy,
    Ally,
    Spot,
    Agro;

    /**
     * 从 categoryId 原始值解析。
     *
     * <p>用 {@code gc.damageStatCategoryName(id, version)} 取得规范名再匹配枚举常量；
     * 未知类别 → Enemy（保守回退，）。</p>
     *
     * @param raw     原始 categoryId
     * @param gc      统一常量布局管理器
     * @param version 回放版本
     */
    public static DamageStatCategory fromRaw(long raw, GameConstants gc, Version version) {
        String name = gc.damageStatCategoryName((int) raw, version);
        for (var c : values()) {
            if (c.name().equals(name)) return c;
        }
        return Enemy;
    }
}
