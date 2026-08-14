package com.shinoaki.wowsreplay.dumper;

import com.shinoaki.wowsreplay.core.JsonMapper;
import com.shinoaki.wowsreplay.core.ReplayFile;
import com.shinoaki.wowsreplay.core.data.LangProvider;
import com.shinoaki.wowsreplay.core.spec.GameDataCache;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ReplayDumper 管线集成测试：单 JSON 装配（meta + report + game_events + space_size + minimap），
 * 对标 Rust replay-dumper 的 Single 模式（docs/replay-dumper-minimap.md §6 / pipeline）。
 */
@Slf4j
class ReplayDumperIT {

    private static final String REPLAY_PATH =
            "temp/wg_15.6/20260730_013138_PASB720-Rhode-Island_56_AngelWings.wowsreplay";
    private static final String WOWS_DATA_BASE = "temp/wows-data";

    private static ReplayFile replay;
    private static Path gameData;

    @BeforeAll
    static void setUp() throws Exception {
        var base = resolve(WOWS_DATA_BASE);
        replay = ReplayFile.fromFile(resolve(REPLAY_PATH), base);
        gameData = GameDataCache.resolveGameDataDir(replay);
        assertNotNull(gameData, "游戏数据未找到");
    }

    public static Path resolve(String path) {
        return Path.of(System.getProperty("user.dir")).getParent().resolve(path);
    }

    @Test
    @DisplayName("ReplayDumper: 单一 JSON 装配（report+game_events+space_size+minimap）")
    void dumpSingle() throws Exception {
        var options = new ReplayDumper.Options(LangProvider.DEFAULT_LANG, true, false, 6);
        var replayDumper = new ReplayDumper(replay, options);
        var dumper = replayDumper.dump();
        var json = JsonMapper.toPrettyJson(dumper);
        var tree = JsonMapper.readTree(json);
        var out = resolve("temp/compare/java_dump.json");
        Files.writeString(out, json);
        log.info("已写入 {}（game_events={} players={} minimap={}）",
                out, tree.get("game_events").size(), tree.get("players").size(),
                tree.get("minimap") != null ? "compressed" : "none");

        assertNotNull(tree.get("arena_id"), "应有 arena_id");
        assertNotNull(tree.get("version"), "应有 version");
        assertNotNull(tree.get("map_name"), "应有 map_name");
        assertNotNull(tree.get("space_size"), "应有 space_size（space.settings 解析）");
        assertTrue(tree.get("players").isArray() && tree.get("players").size() > 0, "应有 players");
        // vehicle 富化：ship_id + ship_config 原始 id（game_params 不做名称解析）
        assertTrue(playersWithVehicle(tree.get("players")) > 0, "players 应带 vehicle{ship_id/modernizations/consumables/exteriors}");
        assertTrue(tree.get("game_events").isArray() && tree.get("game_events").size() > 0, "应有 game_events");
        assertTrue(tree.get("battle_stage") != null && tree.get("battle_stage").isTextual(), "battle_stage 应为阶段名");
        // battle_results 具名解析（constants.json）
        assertTrue(tree.get("battle_results") != null && tree.get("battle_results").isObject(),
            "battle_results 应解析为具名对象");
        assertTrue(tree.get("battle_results").get("playersPublicInfo") != null
                && tree.get("battle_results").get("playersPublicInfo").isObject(),
            "battle_results.playersPublicInfo 应为具名对象");
    }

    @Test
    @DisplayName("space_size 解析（56_AngelWings → 1600）")
    void spaceSize() {
        Integer size = ReplayDumper.parseSpaceSize(gameData, "56_AngelWings");
        assertNotNull(size, "应解析出 space_size");
        assertEquals(1600, size, "bounds=(-10,-10)..(9,9) chunkSize=100 → space_size=1600");
    }


    private static int playersWithVehicle(tools.jackson.databind.JsonNode players) {
        int n = 0;
        for (var p : players) {
            if (p.get("vehicle") != null && p.get("vehicle").get("ship_id") != null) n++;
        }
        return n;
    }
}
