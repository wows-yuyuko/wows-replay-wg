package com.shinoaki.wowsreplay.dumper;

import com.shinoaki.wowsreplay.core.JsonMapper;
import com.shinoaki.wowsreplay.core.ReplayFile;
import com.shinoaki.wowsreplay.core.spec.GameDataCache;
import com.shinoaki.wowsreplay.dumper.minimap.MinimapOutput;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertNotNull;

@Slf4j
public class ReplayDumperMeTest {
    private static final String REPLAY_PATH =
            "temp/wg_15.7/20260816_105341_PVSA710-Independencia_22_tierra_del_fuego.wowsreplay";
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
        var options = new ReplayDumper.Options(true, false, 0);
        var replayDumper = new ReplayDumper(replay, options);
        var dumper = replayDumper.dump();
        long accountId = 2022515210;
        Map<String, Object> minimap = (Map<String, Object>) dumper.get("minimap");
        List<MinimapOutput.MinimapFrame> tempFrames = (List<MinimapOutput.MinimapFrame>) minimap.get("frames");
        List<MinimapOutput.PlaneEntry> entries = new ArrayList<>();
        for (var temp : tempFrames) {
            entries.addAll(temp.planes());
        }
        var map = entries.stream().collect(Collectors.groupingBy(m -> m.ownerMetaId()));
        var info = map.getOrDefault(537315308L, List.of());
        Set<Long> pl = new HashSet<>();
        info.forEach(x -> pl.add(x.paramsId()));

        Set<Long> pl2 = new HashSet<>();
        info.forEach(x -> pl2.add(x.planeId()));
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
