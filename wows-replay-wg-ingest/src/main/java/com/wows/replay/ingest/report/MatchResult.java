package com.wows.replay.ingest.report;

/**
 * 胜负判定结果，对标 Rust {@code BattleResult}（report.rs §5.4）。
 */
public enum MatchResult {
    WIN,
    LOSS,
    DRAW
}
