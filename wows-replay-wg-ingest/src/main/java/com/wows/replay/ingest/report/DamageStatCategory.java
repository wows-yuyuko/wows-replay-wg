package com.wows.replay.ingest.report;

/**
 * 伤害统计类别（对标 Rust {@code DamageStatCategory}）。
 * 从 {@code receiveDamageStat} pickle 的 categoryId 解析：
 * DAMAGE_STATS_ENEMY=0, DAMAGE_STATS_ALLY=1, DAMAGE_STATS_SPOT=2, DAMAGE_STATS_AGRO=3.
 */
public enum DamageStatCategory {
    Enemy(0),
    Ally(1),
    Spot(2),
    Agro(3);

    private final int raw;

    DamageStatCategory(int raw) {
        this.raw = raw;
    }

    public int raw() {
        return raw;
    }

    /** 从 categoryId 原始值解析，未知类别 → Enemy（保守回退，Rust 用 u32 原始值）。 */
    public static DamageStatCategory fromRaw(long raw) {
        for (var c : values()) {
            if (c.raw == raw) return c;
        }
        return Enemy;
    }
}
