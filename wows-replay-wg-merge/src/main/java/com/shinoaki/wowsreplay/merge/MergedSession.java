package com.shinoaki.wowsreplay.merge;

import com.shinoaki.wowsreplay.core.model.GameClock;
import com.shinoaki.wowsreplay.ingest.BattleWorld;

/**
 * 多视角合并会话（预留接口，对标 Rust {@code MergedReplays}，merged.rs §10）。
 *
 * <p>当前合并模块为<b>结果级</b>实现（{@link ReplayMerger}：各自解析后合并去重）。
 * 本接口为后续<b>流式</b>合并预留契约——把主视角 + 任意数量同场次 alt 视角的包流按
 * safe_clock 同步步进喂进同一个 {@code BattleWorld}，主视角拥有广播型状态，alt 只贡献
 * "其他玩家战舰"的更新。实现见 docs/replay-parser-call-chain.md §10。</p>
 *
 * <p><b>id 归一</b>：流式收尾同样复用映射层 {@link ReplayMapper#map}，把最终 world
 * 归一为 {@link NormalizedReplay}（实体 id → 全局一致 metaId），保证全局输出与单视角/结果级
 * 合并一致：只有 metaId（accountId 保留在玩家信息）。</p>
 *
 * <p>批处理驱动与流式驱动共享同一份 ingest 代码，区别只在喂包的节奏（docs §1）。</p>
 */
public interface MergedSession {

    /**
     * 推进一个包（从时钟最落后的未完成回放取），返回安全时钟。
     *
     * @return {@code Some(safe_clock)} 处理了一包后的安全时钟（所有未完成流都已到达的时钟），
     *         {@code None} 表示所有流已耗尽
     */
    java.util.Optional<GameClock> step();

    /** 合并视图所在的 BattleWorld（可读侧查询）。 */
    BattleWorld world();

    /** 所有回放流是否已耗尽。 */
    boolean isDone();

    /** 收尾（等价 {@code world.finish()}）。 */
    void finish();
}
