package com.wows.replay.merge;

/**
 * 多视角合并错误。
 *
 * <p>合并要求所有回放是<b>同一场战斗</b>：客户端版本与竞技场 id 必须一致，
 * 否则合并结果无意义（docs/replay-parser-call-chain.md §10.1/§10.2：VersionMismatch / ArenaIdMismatch）。</p>
 */
public class MergeException extends RuntimeException {

    public MergeException(String message) {
        super(message);
    }

    public MergeException(String message, Throwable cause) {
        super(message, cause);
    }

    /** 客户端版本不一致。 */
    public static MergeException versionMismatch(String expected, String actual) {
        return new MergeException("版本不一致: 主视角 " + expected + " ≠ " + actual
            + "（同场次所有 replay 必须同版本）");
    }

    /** 竞技场 id 不一致（不同场次）。 */
    public static MergeException arenaMismatch(String primary, String other) {
        return new MergeException("竞技场不一致: 主视角 " + primary + " ≠ " + other
            + "（不同场次的 replay 不能合并）");
    }
}
