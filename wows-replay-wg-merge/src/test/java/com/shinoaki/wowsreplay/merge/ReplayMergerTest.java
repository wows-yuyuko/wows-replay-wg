package com.shinoaki.wowsreplay.merge;

import com.shinoaki.wowsreplay.core.model.Version;
import com.shinoaki.wowsreplay.ingest.mapped.NormalizedReplay;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 结果级合并去重单元测试（构造已归一的 {@link NormalizedReplay} 视角数据，不依赖回放文件）。
 */
class ReplayMergerTest {

    private static final Version VERSION = new Version(15, 6, 0, 0);

    @Test
    @DisplayName("广播状态取主视角，其余事件流跨视角去重")
    void mergeDeduplicates() {
        var a = view(1, VERSION,
            List.of(player(1, 101, "Alice"), player(2, 102, "Bob")),
            List.of(kill(5, 1, 2, "Bob"), kill(9, 1, 3, "Carol")),
            List.of(chat(2, 1, "Alice", "hello")),
            List.of(damage(5, 1, 2, 500f)),
            List.of(consumable(3, 1, "Alice", 50)));
        var b = view(1, VERSION,
            List.of(player(1, 101, "Alice"), player(3, 103, "Carol")),
            List.of(kill(5, 1, 2, "Bob"), kill(9, 2, 4, "Dora")),
            List.of(chat(2, 1, "Alice", "hello"), chat(4, 2, "Bob", "hi")),
            List.of(damage(5, 1, 2, 500f), damage(9, 1, 2, 300f)),
            List.of(consumable(3, 1, "Alice", 50), consumable(7, 2, "Bob", 60)));

        var merged = new ReplayMerger(null, null).mergeNormalized(List.of(a, b));

        assertEquals(2, merged.replayCount());
        // 广播状态取主视角 A：玩家 [Alice, Bob]、击杀 [victim2@5, victim3@9]
        assertEquals(2, merged.replay().players().size(), "玩家取主视角");
        assertEquals(2, merged.replay().killLog().size(), "击杀取主视角");
        assertEquals(2, merged.replay().chatLog().size(), "聊天按 clock+metaId+message 去重");
        assertEquals(2, merged.replay().damageEvents().size(), "伤害按 metaId+clock+amount 去重");
        assertEquals(2, merged.replay().consumableLog().size(), "消耗品按 clock+metaId+consumable 去重");

        assertEquals(0, merged.dedupStats().get("players"), "广播状态不计去重");
        assertEquals(0, merged.dedupStats().get("kills"), "广播状态不计去重");
        assertEquals(1, merged.dedupStats().get("chat"));
        assertEquals(1, merged.dedupStats().get("damage"));
        assertEquals(1, merged.dedupStats().get("consumables"));

        // 时间线按 clock 升序
        for (int i = 1; i < merged.replay().killLog().size(); i++) {
            assertTrue(merged.replay().killLog().get(i - 1).clock() <= merged.replay().killLog().get(i).clock(),
                "kill_log 应按 clock 升序");
        }
    }

    @Test
    @DisplayName("版本不一致 → MergeException")
    void versionMismatchThrows() {
        var a = view(1, VERSION, List.of(), List.of(), List.of(), List.of(), List.of());
        var b = view(1, new Version(16, 0, 0, 0), List.of(), List.of(), List.of(), List.of(), List.of());
        assertThrows(MergeException.class, () -> new ReplayMerger(null, null).mergeNormalized(List.of(a, b)),
            "不同版本的 replay 不能合并");
    }

    @Test
    @DisplayName("竞技场不一致 → MergeException")
    void arenaMismatchThrows() {
        var a = view(1, VERSION, List.of(), List.of(), List.of(), List.of(), List.of());
        var b = view(2, VERSION, List.of(), List.of(), List.of(), List.of(), List.of());
        assertThrows(MergeException.class, () -> new ReplayMerger(null, null).mergeNormalized(List.of(a, b)),
            "不同场次的 replay 不能合并");
    }

    // ── 构造辅助 ────────────────────────────────────────────────────────

    private static NormalizedReplay view(long arenaId, Version version,
                                         List<NormalizedPlayer> players,
                                         List<NormalizedKill> kills,
                                         List<NormalizedChat> chats,
                                         List<NormalizedDamage> damages,
                                         List<NormalizedConsumable> consumables) {
        return new NormalizedReplay(
            arenaId, version, null, null, null, null, null, null, 0,
            0f, 0f, 0f, null,
            players, kills, damages, chats, consumables,
            List.of(), List.of(), List.of(), List.of(), List.of(),
            List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
            List.of());
    }

    private static NormalizedPlayer player(long metaId, long accountId, String name) {
        return new NormalizedPlayer(metaId, accountId, name, 1, 1, false);
    }

    private static NormalizedKill kill(float clock, long killerMetaId, long victimMetaId, String victimName) {
        return new NormalizedKill(clock, killerMetaId, "Killer", victimMetaId, victimName, 1);
    }

    private static NormalizedChat chat(float clock, long metaId, String username, String message) {
        return new NormalizedChat(clock, metaId, username, "team", message);
    }

    private static NormalizedDamage damage(float clock, long aggressorMetaId, long victimMetaId, float amount) {
        return new NormalizedDamage(clock, aggressorMetaId, victimMetaId, amount);
    }

    private static NormalizedConsumable consumable(float clock, long metaId, String username, long id) {
        return new NormalizedConsumable(clock, metaId, username, id, 10f);
    }
}
