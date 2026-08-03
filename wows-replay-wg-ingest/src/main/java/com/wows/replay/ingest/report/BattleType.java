package com.wows.replay.ingest.report;

import com.wows.replay.model.Recognized;
import com.wows.replay.model.Version;

/**
 * 游戏类型枚举（对标 Rust {@code BattleType}，game_types.rs）。
 * 从 {@link com.wows.replay.ReplayMeta#gameType()} 字符串解析。
 */
public enum BattleType {
    Unknown,
    RandomBattle,
    CoopBattle,
    RankedBattle,
    ClanBattle,
    TrainingBattle,
    Scenario,
    PvEScenario,
    SectorBattle,
    Operation,
    SpaceBattle,
    BattleRoyale,
    AirshipEscort;

    /**
     * 从 gameType 字符串解析，带版本兼容。未知值 → {@link Recognized.Unknown}。
     */
    public static Recognized<BattleType> fromValue(String s, Version version) {
        if (s == null || s.isBlank()) return new Recognized.Unknown<>(0);
        for (var t : values()) {
            if (t.name().equalsIgnoreCase(s)) return new Recognized.Known<>(t);
        }
        // 常见别名
        return switch (s) {
            case "RandomBattle", "RANDOM_BATTLE", "random" -> new Recognized.Known<>(RandomBattle);
            case "CoopBattle", "COOP_BATTLE", "coop" -> new Recognized.Known<>(CoopBattle);
            case "Scenario", "SCENARIO", "scenario" -> new Recognized.Known<>(Scenario);
            case "PvEScenario", "PVE_SCENARIO" -> new Recognized.Known<>(PvEScenario);
            case "RankedBattle", "RANKED_BATTLE" -> new Recognized.Known<>(RankedBattle);
            case "ClanBattle", "CLAN_BATTLE" -> new Recognized.Known<>(ClanBattle);
            case "Operation", "OPERATION" -> new Recognized.Known<>(Operation);
            default -> new Recognized.Unknown<>(0);
        };
    }
}
