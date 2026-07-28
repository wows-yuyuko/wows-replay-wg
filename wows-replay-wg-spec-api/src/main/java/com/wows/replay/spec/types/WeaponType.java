package com.wows.replay.spec.types;

import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 武器类型，枚举可选择的武装。
 * 对标 Rust {@code wowsunpack::game_types::WeaponType}.
 *
 * <p>线路上原始值来自客户端's integer {@code WeaponType}
 * enum ({@code scripts/WeaponType.pyc}), which is wider than the
 * selectable weapons modeled here.  Non-selectable types are preserved
 * as {@link Recognized.Unknown}.</p>
 */
public enum WeaponType {
    ARTILLERY,
    /** 副炮。 */
    SECONDARIES,
    TORPEDOES,
    PLANES,
    /** 声纳（潜艇）。 */
    PINGER;

    private static final Map<Integer, WeaponType> REVERSE = Stream.of(values())
        .collect(Collectors.toUnmodifiableMap(
            w -> switch (w) {
                case ARTILLERY  -> 0;
                case SECONDARIES -> 1;
                case TORPEDOES  -> 2;
                case PLANES     -> 3;
                case PINGER     -> 6;
            },
            w -> w));

    /** Python entity def 中使用的线级名称。 */
    @JsonValue
    public String wireName() {
        return switch (this) {
            case ARTILLERY  -> "ARTILLERY";
            case SECONDARIES -> "ATBA";
            case TORPEDOES  -> "TORPEDO";
            case PLANES     -> "AIRPLANES";
            case PINGER     -> "PINGER";
        };
    }

    /** 可读描述。 */
    public String description() {
        return switch (this) {
            case ARTILLERY  -> "Main Battery";
            case SECONDARIES -> "Secondaries";
            case TORPEDOES  -> "Torpedoes";
            case PLANES     -> "Planes";
            case PINGER     -> "Sonar";
        };
    }

    /**
     * 将线路上原始值映射为 {@link Recognized} 变体。
     * 非可选择武器类型返回 {@link Recognized.Unknown}。
     */
    public static Recognized<WeaponType> fromRaw(int raw) {
        var known = REVERSE.get(raw);
        return known != null ? new Recognized.Known<>(known) : new Recognized.Unknown<>(raw);
    }
}
