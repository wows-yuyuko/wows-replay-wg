package com.shinoaki.wowsreplay.merge;

import com.shinoaki.wowsreplay.core.ReplayFile;
import com.shinoaki.wowsreplay.ingest.BattleWorld;
import com.shinoaki.wowsreplay.ingest.report.BattleReport;

/**
 * 一份已解析的回放（结果级合并的输入单元）。
 *
 * <p>由 {@link ReplayMerger#parse(ReplayFile)} 产出，或调用方自行用
 * core+ingest 管线构建（等价 {@link BattleReportBuilder} 的装配产物）。</p>
 *
 * @param replay 原始回放文件（元数据/版本）
 * @param world  摄入后的实时状态容器（事件流：击杀/聊天/伤害/消耗品/占领点…）
 * @param report 装配出的终局快照（players / 胜负 / 顶层元数据）
 */
public record ParsedReplay(ReplayFile replay, BattleWorld world, BattleReport report) {
}
