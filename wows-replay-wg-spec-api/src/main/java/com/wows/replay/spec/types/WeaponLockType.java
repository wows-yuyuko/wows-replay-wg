package com.wows.replay.spec.types;

import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 武器锁定状态，枚举四种锁定模式。
 * 对标 Rust's {@code wowsunpack::game_types::WeaponLockType}.
 */
public enum WeaponLockType {
    /** 无锁定（解锁）。 */
    NONE,
    /** 锁定世界空间中的固定点。 */
    ABSOLUTE,
    /** 锁定相对于发射舰船的点。 */
    RELATIVE,
    /** 硬锁定目标实体。 */
    TARGET;

    private static final Map<Integer, WeaponLockType> REVERSE = Stream.of(values())
        .collect(Collectors.toUnmodifiableMap(
            w -> switch (w) {
                case NONE     -> 0;
                case ABSOLUTE -> 1;
                case RELATIVE -> 2;
                case TARGET   -> 3;
            },
            w -> w));

    @JsonValue
    public String wireName() {
        return name();
    }

    /**
     * 将线路上原始值映射为 {@link Recognized} 变体。
     */
    public static Recognized<WeaponLockType> fromRaw(int raw) {
        var known = REVERSE.get(raw);
        return known != null ? new Recognized.Known<>(known) : new Recognized.Unknown<>(raw);
    }
}
