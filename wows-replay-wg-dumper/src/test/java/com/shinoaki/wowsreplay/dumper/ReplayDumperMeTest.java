package com.shinoaki.wowsreplay.dumper;

import com.shinoaki.wowsreplay.core.JsonMapper;
import com.shinoaki.wowsreplay.core.ReplayFile;
import com.shinoaki.wowsreplay.core.data.LangProvider;
import com.shinoaki.wowsreplay.core.spec.GameDataCache;
import com.shinoaki.wowsreplay.dumper.minimap.MinimapOutput;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertNotNull;

@Slf4j
public class ReplayDumperMeTest {
    private static final String REPLAY_PATH =
            "temp/wg_15.6/20260730_013138_PASB720-Rhode-Island_56_AngelWings.wowsreplay";
    private static final String WOWS_DATA_BASE = "temp/wows-data";

    private static ReplayFile replay;
    private static Path gameData;

    @BeforeAll
    static void setUp() throws Exception {
        var base = ReplayDumperIT.resolve(WOWS_DATA_BASE);
        replay = ReplayFile.fromFile(ReplayDumperIT.resolve(REPLAY_PATH), base);
        gameData = GameDataCache.resolveGameDataDir(replay);
        assertNotNull(gameData, "游戏数据未找到");
    }

    @Test
    @DisplayName("ReplayDumper: 自定义测试")
    void dumpSingle() throws Exception {
        var options = new ReplayDumper.Options(LangProvider.DEFAULT_LANG, true, false, 0);
        var replayDumper = new ReplayDumper(replay, options);
        var dumper = replayDumper.dump();
        long accountId = 2022515210;
        Map<String, Object> minimap = (Map<String, Object>) dumper.get("minimap");
        List<MinimapOutput.MinimapFrame> tempFrames = (List<MinimapOutput.MinimapFrame>) minimap.get("frames");
        List<MinimapOutput.MinimapFrame> frames = new ArrayList<>();
        for (var temp : tempFrames) {
            if (temp.smokeScreens().size() > 2) {
                frames.add(temp);
            }
        }
        //过滤数据
        var json = JsonMapper.toPrettyJson(dumper);
        var tree = JsonMapper.readTree(json);
        var out = ReplayDumperIT.resolve("temp/compare/java_my_dump.json");
        Files.writeString(out, json);
        log.info("已写入 {}（game_events={} players={} minimap={}）",
                out, tree.get("game_events").size(), tree.get("players").size(),
                tree.get("minimap") != null ? "compressed" : "none");
    }
}
