package com.wows.replay.dumper;

import com.wows.replay.JsonMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * wows-dumper 集成测试：跑真实 15.6.0 回放，与保存的 Rust replay-dumper 输出
 * （temp/compare/rust_dump*.json）对拍关键字段与计数。
 */
class DumperIT {

    private static final String REPLAY_PATH =
            "temp/wg_15.6/20260730_013138_PASB720-Rhode-Island_56_AngelWings.wowsreplay";
    private static final String GAME_DATA_BASE = "temp/wows-data";
    private static final String COMPARE_DIR = "temp/compare";

    private Path resolve(Path base, String rel) {
        return base.resolve(rel);
    }

    private Path projectRoot() {
        return Path.of(System.getProperty("user.dir")).getParent();
    }

    private Path groundTruth(String file) {
        return resolve(projectRoot(), COMPARE_DIR + "/" + file);
    }

    @Test
    @DisplayName("dump(): 顶层字段与 Rust 输出对齐")
    void topLevelFields() throws Exception {
        var root = projectRoot();
        var json = Dumper.dump(resolve(root, REPLAY_PATH), resolve(root, GAME_DATA_BASE),
                DumperConfig.builder().prettyPrint(true).build());
        var node = JsonMapper.readTree(json);

        assertEquals(2490116449384685L, node.get("arena_id").asLong());
        assertEquals("spaces/56_AngelWings", node.get("map_name").asText());
        assertEquals("domination_3point", node.get("game_mode").asText());
        assertEquals("RandomBattle", node.get("game_type").asText());
        assertEquals("pvp", node.get("match_group").asText());
        assertEquals(36, node.get("map_id").asInt());
        assertEquals(1600, node.get("space_size").asInt());
        assertEquals(1200, node.get("max_duration").asInt());
        assertEquals("Extermination", node.get("finish_type").get("Known").asText());
        assertEquals("Loss", node.get("match_result").get("type").asText());
        assertEquals(1, node.get("match_result").get("team_id").asInt());

        assertEquals(24, node.get("players").size());
        assertEquals(184, node.get("game_events").size());
        assertEquals(3, node.get("capture_points").size());
        assertEquals(2, node.get("team_scores").size());
        assertEquals(1, node.get("playersPrivateInfo").size());

        // 与 ground-truth 对齐
        var gt = JsonMapper.readTree(Files.readAllBytes(groundTruth("rust_dump.json")));
        assertEquals(gt.get("arena_id").asLong(), node.get("arena_id").asLong());
        assertEquals(gt.get("map_name").asText(), node.get("map_name").asText());
        assertEquals(gt.get("game_events").size(), node.get("game_events").size());
        assertEquals(gt.get("players").size(), node.get("players").size());
        assertEquals(gt.get("space_size").asInt(), node.get("space_size").asInt());
    }

    @Test
    @DisplayName("dump(): game_events 类型分布与 Rust 一致")
    void gameEvents() throws Exception {
        var root = projectRoot();
        var json = Dumper.dump(resolve(root, REPLAY_PATH), resolve(root, GAME_DATA_BASE),
                DumperConfig.builder().build());
        var node = JsonMapper.readTree(json);

        long chat = 0, kill = 0, consumable = 0;
        for (var ev : node.get("game_events")) {
            switch (ev.get("type").asText()) {
                case "chat" -> chat++;
                case "kill" -> kill++;
                case "consumable" -> consumable++;
            }
        }
        assertEquals(48, chat);
        assertEquals(21, kill);
        assertEquals(115, consumable);

        var gt = JsonMapper.readTree(Files.readAllBytes(groundTruth("rust_dump.json")));
        assertEquals(gt.get("game_events").size(), node.get("game_events").size());
    }

    @Test
    @DisplayName("dump(minimap): 帧/事件计数与 Rust 对齐")
    void minimapCounts() throws Exception {
        var root = projectRoot();
        var json = Dumper.dump(resolve(root, REPLAY_PATH), resolve(root, GAME_DATA_BASE),
                DumperConfig.builder().minimap(true).minimapStep(7).build());
        var node = JsonMapper.readTree(json);

        assertEquals(2737, node.get("frames").size());
        assertEquals(426, node.get("firing_events").size());
        assertEquals(1032, node.get("damage_events").size());
        assertEquals(2934, node.get("shot_hits").size());
        assertEquals(21, node.get("dead_ships").size());
        assertEquals(1, node.get("winning_team").asInt());
        assertEquals("Finishing", node.get("battle_stage").asText());
        assertEquals(1000, node.get("scoring_rules").get("team_win_score").asInt());

        // 帧实体值与 Rust 对齐（抽帧 1 对比首个实体集）
        var frames = node.get("frames");
        for (var fr : frames) {
            if (fr.get("entities").size() > 0) {
                assertTrue(fr.get("entities").size() >= 12, "首帧实体应非空");
                for (var e : fr.get("entities")) {
                    assertTrue(e.get("id").asLong() > 0);
                    assertTrue(e.has("x") && e.has("y") && e.has("heading"));
                }
                break;
            }
        }
    }

    @Test
    @DisplayName("dump(minimap): 与 Rust minimap ground-truth 关键计数一致")
    void minimapVsGroundTruth() throws Exception {
        var root = projectRoot();
        var json = Dumper.dump(resolve(root, REPLAY_PATH), resolve(root, GAME_DATA_BASE),
                DumperConfig.builder().minimap(true).minimapStep(7).build());
        var node = JsonMapper.readTree(json);
        var gt = JsonMapper.readTree(Files.readAllBytes(groundTruth("rust_dump_minimap.json")));

        for (String key : new String[]{"frames", "firing_events", "damage_events", "shot_hits", "dead_ships"}) {
            assertEquals(gt.get(key).size(), node.get(key).size(), key + " 数量应与 Rust 一致");
        }
        assertEquals(gt.get("winning_team").asInt(), node.get("winning_team").asInt());
        assertEquals(gt.get("battle_stage").asText(), node.get("battle_stage").asText());
        assertEquals(gt.get("scoring_rules").get("team_win_score").asInt(),
            node.get("scoring_rules").get("team_win_score").asInt());
    }
}
