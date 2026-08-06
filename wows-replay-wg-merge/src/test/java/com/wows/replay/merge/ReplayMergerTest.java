package com.wows.replay.merge;

import com.wows.replay.ReplayMeta;
import com.wows.replay.decode.DecodedPayload;
import com.wows.replay.ingest.BattleWorld;
import com.wows.replay.ingest.report.BattleReport;
import com.wows.replay.ingest.report.Player;
import com.wows.replay.model.AccountId;
import com.wows.replay.model.EntityId;
import com.wows.replay.model.GameClock;
import com.wows.replay.model.Version;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 结果级合并去重单元测试（合成数据，不依赖回放文件）。
 */
class ReplayMergerTest {

    private static final Version VERSION = new Version(15, 6, 0, 0);

    private static final ReplayMeta META = new ReplayMeta(
        null, 0, null, "15.6.0.0", 0, null, 0, null, null, 0,
        null, null, null, null, List.of(), 0, null, null, null, 0, 2, null, null, 0);

    @Test
    @DisplayName("合并去重：跨视角重复的击杀/聊天/伤害/消耗品/玩家只保留一份，并集其余")
    void mergeDeduplicates() {
        var parsedA = new ParsedReplay(null, parseWorld("A"), report(1, VERSION,
            player(1, 101, "Alice"), player(2, 102, "Bob")));
        var parsedB = new ParsedReplay(null, parseWorld("B"), report(1, VERSION,
            player(1, 101, "Alice"), player(3, 103, "Carol")));

        var merged = new ReplayMerger(null, null).merge(List.of(parsedA, parsedB));

        assertEquals(2, merged.replayCount());
        assertEquals(3, merged.players().size(), "玩家按 metaId 并集去重（Alice 只保留一份）");

        // A: 击杀 victim 10/20, 聊天 hello, 伤害(1→10@5), 消耗品(1,50)
        // B: 击杀 victim 10(重复)/30, 聊天 hello(重复)+hi, 伤害(1→10@5 重复)+(1→10@9), 消耗品(1,50 重复)+(2,60)
        assertEquals(3, merged.killLog().size(), "击杀按 victim 实体 id 去重");
        assertEquals(2, merged.chatLog().size(), "聊天按 clock+sender+message 去重");
        assertEquals(2, merged.damageEvents().size(), "伤害按 aggressor+victim+clock 去重");
        assertEquals(2, merged.consumableLog().size(), "消耗品按 clock+entity+consumable 去重");

        assertEquals(1, merged.dedupStats().get("players"));
        assertEquals(1, merged.dedupStats().get("kills"));
        assertEquals(1, merged.dedupStats().get("chat"));
        assertEquals(1, merged.dedupStats().get("damage"));
        assertEquals(1, merged.dedupStats().get("consumables"));

        // 时间线按 clock 升序
        for (int i = 1; i < merged.killLog().size(); i++) {
            assertTrue(merged.killLog().get(i - 1).clock() <= merged.killLog().get(i).clock(),
                "kill_log 应按 clock 升序");
        }
    }

    @Test
    @DisplayName("版本不一致 → MergeException")
    void versionMismatchThrows() {
        var parsedA = new ParsedReplay(null, parseWorld("A"), report(1, VERSION));
        var parsedB = new ParsedReplay(null, parseWorld("B"), report(1, new Version(16, 0, 0, 0)));
        assertThrows(MergeException.class, () -> new ReplayMerger(null, null).merge(List.of(parsedA, parsedB)),
            "不同版本的 replay 不能合并");
    }

    @Test
    @DisplayName("竞技场不一致 → MergeException")
    void arenaMismatchThrows() {
        var parsedA = new ParsedReplay(null, parseWorld("A"), report(1, VERSION));
        var parsedB = new ParsedReplay(null, parseWorld("B"), report(2, VERSION));
        assertThrows(MergeException.class, () -> new ReplayMerger(null, null).merge(List.of(parsedA, parsedB)),
            "不同场次的 replay 不能合并");
    }

    // ── 合成数据辅助 ────────────────────────────────────────────────────

    /** 构建视角 A/B 的 BattleWorld（重叠事件制造重复，两个视角的事件并集 = 完整数据）。 */
    private static BattleWorld parseWorld(String tag) {
        var w = new BattleWorld(META, VERSION);
        if ("A".equals(tag)) {
            w.process(new DecodedPayload.ShipDestroyedPayload(new EntityId(1), new EntityId(10), 1), new GameClock(5f));
            w.process(new DecodedPayload.ShipDestroyedPayload(new EntityId(1), new EntityId(20), 1), new GameClock(9f));
            w.process(new DecodedPayload.ChatMessagePayload(new EntityId(9), new AccountId(100), "team", "hello", null), new GameClock(2f));
            w.process(new DecodedPayload.DamageReceivedPayload(new EntityId(10),
                List.of(new DecodedPayload.DamageReceivedEntry(new EntityId(1), 500f))), new GameClock(5f));
            w.process(new DecodedPayload.ConsumablePayload(new EntityId(1), 50, 10f, null), new GameClock(3f));
        } else {
            w.process(new DecodedPayload.ShipDestroyedPayload(new EntityId(1), new EntityId(10), 1), new GameClock(5f));
            w.process(new DecodedPayload.ShipDestroyedPayload(new EntityId(1), new EntityId(30), 1), new GameClock(9f));
            w.process(new DecodedPayload.ChatMessagePayload(new EntityId(9), new AccountId(100), "team", "hello", null), new GameClock(2f));
            w.process(new DecodedPayload.ChatMessagePayload(new EntityId(9), new AccountId(200), "team", "hi", null), new GameClock(4f));
            w.process(new DecodedPayload.DamageReceivedPayload(new EntityId(10),
                List.of(new DecodedPayload.DamageReceivedEntry(new EntityId(1), 500f))), new GameClock(5f));
            w.process(new DecodedPayload.DamageReceivedPayload(new EntityId(10),
                List.of(new DecodedPayload.DamageReceivedEntry(new EntityId(1), 300f))), new GameClock(9f));
            w.process(new DecodedPayload.ConsumablePayload(new EntityId(1), 50, 10f, null), new GameClock(3f));
            w.process(new DecodedPayload.ConsumablePayload(new EntityId(2), 60, 8f, null), new GameClock(7f));
        }
        return w;
    }

    private static Player player(long metaId, long dbId, String name) {
        return new Player(metaId, dbId, 0, name, 1, 1, false, null);
    }

    private static BattleReport report(long arenaId, Version version, Player... players) {
        return new BattleReport(
            arenaId,                    // arena_id
            null,                       // self_player
            version,                    // version
            null,                       // map_name
            null,                       // game_mode
            null,                       // game_type
            null,                       // match_group
            List.of(players),           // players
            List.of(),                  // game_chat
            null,                       // battle_results
            java.util.Map.of(),         // frags
            null,                       // match_result
            null,                       // finish_type
            List.of(),                  // capture_points
            java.util.Map.of(),         // buff_zones
            List.of(),                  // captured_buffs
            List.of(),                  // team_scores
            List.of(),                  // buildings
            List.of(),                  // local_weather_zones
            null,                       // battle_start_clock
            List.of(),                  // self_damage_stats
            java.util.Map.of(),         // active_consumables
            0L,                         // max_duration
            null,                       // played_duration
            null);                      // extra_duration
    }
}
