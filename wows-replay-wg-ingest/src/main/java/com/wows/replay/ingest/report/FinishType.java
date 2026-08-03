package com.wows.replay.ingest.report;

import com.wows.replay.model.Recognized;

/**
 * 结束类型枚举（对标 Rust {@code FinishType}，battle.xml FINISH_TYPE）。
 * 与 BattleWorld.finishTypeName(int) 的映射保持一致。
 */
public enum FinishType {
    Unknown,
    Extermination,
    BaseCaptured,
    Timeout,
    Failure,
    Technical,
    Score,
    ScoreOnTimeout,
    PveMainTaskSucceeded,
    PveMainTaskFailed,
    ScoreZero,
    ScoreExcess;

    /** 从 finishType 原始 int 解析。 */
    public static Recognized<FinishType> fromRaw(int id) {
        return switch (id) {
            case 0 -> new Recognized.Unknown<>(0);
            case 1 -> new Recognized.Known<>(Extermination);
            case 2 -> new Recognized.Known<>(BaseCaptured);
            case 3 -> new Recognized.Known<>(Timeout);
            case 4 -> new Recognized.Known<>(Failure);
            case 5 -> new Recognized.Known<>(Technical);
            case 8 -> new Recognized.Known<>(Score);
            case 9 -> new Recognized.Known<>(ScoreOnTimeout);
            case 10 -> new Recognized.Known<>(PveMainTaskSucceeded);
            case 11 -> new Recognized.Known<>(PveMainTaskFailed);
            case 12 -> new Recognized.Known<>(ScoreZero);
            case 13 -> new Recognized.Known<>(ScoreExcess);
            default -> new Recognized.Unknown<>(id);
        };
    }
}
