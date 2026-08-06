package com.wows.replay.merge;

import com.wows.replay.JsonMapper;
import com.wows.replay.ReplayFile;
import com.wows.replay.model.Version;
import com.wows.replay.spec.GameDataCache;
import com.wows.replay.spi.EntitySpecProvider;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 结果级合并集成测试：同一份 15.6.0 真实回放解析成"两个视角"后合并——
 * 广播/视角事件应完全去重回单视角数量，玩家并集去重，可序列化。
 */
@Slf4j
class ReplayMergerIT {

    private static final String REPLAY_PATH =
            "temp/wg_15.6/20260730_013138_PASB720-Rhode-Island_56_AngelWings.wowsreplay";
    private static final String WOWS_DATA_PATH = "temp/wows-data";

    private static ReplayFile replay;
    private static ReplayMerger merger;

    @BeforeAll
    static void setUp() throws Exception {
        replay = ReplayFile.fromFile(resolve(REPLAY_PATH));
        var gameData = findGameDataDir(resolve(WOWS_DATA_PATH), replay.version());
        assertNotNull(gameData, "游戏数据未找到: " + resolve(WOWS_DATA_PATH));
        EntitySpecProvider specProvider = GameDataCache.withMaxSize(4)
            .entitySpecs(GameDataCache.VersionKey.from(gameData), gameData);
        merger = new ReplayMerger(specProvider, null);
    }

    private static Path resolve(String path) {
        return Path.of(System.getProperty("user.dir")).getParent().resolve(path);
    }

    private static Path findGameDataDir(Path wowsDataBase, Version version) {
        var prefix = "data-" + version.major() + "." + version.minor() + ".";
        var children = wowsDataBase.toFile().listFiles();
        if (children == null) return null;
        for (var f : children) {
            if (f.isDirectory() && f.getName().startsWith(prefix)) {
                return f.toPath().resolve("live");
            }
        }
        return null;
    }

    @Test
    @DisplayName("同一回放两视角合并：各事件流去重回单视角数量，玩家并集去重")
    void mergeSameBattleTwice() {
        var p1 = merger.parse(replay);
        var p2 = merger.parse(replay);

        int singlePlayers = p1.report().players().size();
        int singleKills = p1.world().killLog().size();
        int singleChat = p1.world().chatLog().size();
        int singleDamage = p1.world().damageEvents().size();
        int singleConsumables = p1.world().consumableLog().size();
        log.info("单视角: {} players, {} kills, {} chat, {} damage, {} consumables",
            singlePlayers, singleKills, singleChat, singleDamage, singleConsumables);

        var merged = merger.merge(List.of(p1, p2));

        assertEquals(2, merged.replayCount());
        assertEquals(singlePlayers, merged.players().size(), "玩家并集去重");
        assertEquals(singleKills, merged.killLog().size(), "击杀去重");
        assertEquals(singleChat, merged.chatLog().size(), "聊天去重");
        assertEquals(singleConsumables, merged.consumableLog().size(), "消耗品去重");

        // 伤害：单视角内可能存在 (clock,aggr,victim,amount) 完全相同的并发条目（真实数据如此），
        // 两视角合并后应等于单视角内的去重条数，而非单视角原始条数。
        java.util.Set<String> seen = new java.util.HashSet<>();
        int uniqueDamage = (int) p1.world().damageEvents().stream()
            .filter(d -> seen.add(d.clock() + "|" + d.aggressorId() + "|" + d.victimId() + "|" + d.amount()))
            .count();
        assertEquals(uniqueDamage, merged.damageEvents().size(), "伤害去重到单视角内唯一条数");
        assertTrue(uniqueDamage <= singleDamage, "单视角内存在重复伤害元组");

        assertEquals(singlePlayers, merged.dedupStats().get("players"));
        assertEquals(singleKills, merged.dedupStats().get("kills"));
        assertEquals(singleChat, merged.dedupStats().get("chat"));
        assertEquals(2 * singleDamage - merged.damageEvents().size(), merged.dedupStats().get("damage"));
        assertEquals(singleConsumables, merged.dedupStats().get("consumables"));

        for (int i = 1; i < merged.killLog().size(); i++) {
            assertTrue(merged.killLog().get(i - 1).clock() <= merged.killLog().get(i).clock(),
                "kill_log 应按 clock 升序");
        }

        assertNotNull(JsonMapper.toJson(merged), "MergedResult 应可序列化为 JSON");
    }
}
