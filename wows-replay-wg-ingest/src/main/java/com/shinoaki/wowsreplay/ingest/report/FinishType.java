package com.shinoaki.wowsreplay.ingest.report;

import com.shinoaki.wowsreplay.core.constant.GameConstants;
import com.shinoaki.wowsreplay.core.model.Recognized;
import com.shinoaki.wowsreplay.core.model.Version;

/**
 * 结束类型枚举（FinishType，battle.xml FINISH_TYPE）。
 *
 * <p>枚举常量集是固定的<b>类型契约</b>（战报 {@code finish_type} 的序列化形态）；
 * id→常量 的解析不做本地硬编码布局，而是委托 {@link GameConstants#finishTypeName}
 * （统一布局管理器：规范表 → 外部 provider → 原始回退），按名称匹配枚举常量。
 * 无法匹配（未知/新增 id）→ {@link Recognized.Unknown}。</p>
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

    /**
     * 从 finishType 原始 int 解析。
     *
     * <p>用 {@code gc.finishTypeName(id, version)} 取得规范名，再与枚举常量名精确匹配；
     * 匹配失败（含 provider 兜底名 / {@code "FinishType(id)"} 回退串）→ Unknown，保留原始 id。</p>
     *
     * @param id      原始 FINISH_TYPE id（BattleLogic {@code battleResult.finishReason}）
     * @param gc      统一常量布局管理器
     * @param version 回放版本
     */
    public static Recognized<FinishType> fromRaw(int id, GameConstants gc, Version version) {
        String name = gc.finishTypeName(id, version);
        for (var c : values()) {
            if (c.name().equals(name)) return new Recognized.Known<>(c);
        }
        return new Recognized.Unknown<>(id);
    }
}
