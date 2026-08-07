package com.wows.replay.merge;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.wows.replay.ingest.mapped.NormalizedReplay;
import tools.jackson.databind.JsonNode;

import java.util.Map;

/**
 * 同场次多视角合并结果（整体消费映射层 {@link NormalizedReplay}）。
 *
 * <p>由 {@link ReplayMerger} 产出：广播状态（玩家/击杀/队伍比分/控制点/buff 掉落区/天气区域）
 * 直接取主视角；其余事件流（聊天/伤害/消耗品/沉船/语音/勋带/齐射/鱼雷/命中/已捕获 Buff）跨视角
 * 并集后按事件身份去重。所有玩家身份均为全局一致的 {@code metaId}（accountId 保留在玩家信息），
 * 与单视角输出（{@code NormalizedReplay}）同构。</p>
 *
 * <p>{@code playersPrivateInfo} 是多视角合并的<b>私有战报汇总</b>：每个回放的
 * {@code battle_result.playersPrivateInfo / privateDataList} 提取后按 db_id 并集
 * （每个玩家的私有数据在其自身回放里最完整）。</p>
 *
 * @param replay              合并后的规范化回放（元数据 + 玩家 + 事件流 + 状态集）
 * @param playersPrivateInfo  私有战报：db_id → 具名对象（跨回放并集）
 * @param dedupStats          去重统计：流名 → 被合并掉（重复）的条数
 * @see ReplayMerger
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record MergedResult(
    /** 参与合并的回放份数（含主视角）。 */
    @JsonProperty("replay_count") int replayCount,

    /** 合并后的规范化回放数据（与单视角输出同构）。 */
    @JsonProperty("replay") NormalizedReplay replay,

    /** 私有战报汇总：db_id → 具名对象（各回放 battle_result.playersPrivateInfo/privateDataList 并集）。 */
    @JsonProperty("playersPrivateInfo") Map<String, JsonNode> playersPrivateInfo,

    /** 去重统计：流名 → 被合并掉（重复）的条数。 */
    @JsonProperty("dedup_stats") Map<String, Integer> dedupStats
) {}
