package com.wows.replay.analyzer;
import lombok.extern.slf4j.Slf4j;

import com.wows.replay.core.ReplayException;
import com.wows.replay.core.ReplayFile;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 完整集成测试：回放文件 → parse → analyze → JSON.
 *
 * <p>The test replay is expected at {@code temp/wg_15.6/} relative to the
 * project root.  Tests are skipped gracefully if the file is absent.</p>
 */
@Slf4j
class ReplayAnalyzerIT {

    private static final String REPLAY_PATH =
        "temp/wg_15.6/20260727_230908_PJSB720-Aki_18_NE_ice_islands.wowsreplay";

    private Path resolveReplay() {
        var candidate = Path.of(REPLAY_PATH);
        if (Files.exists(candidate)) return candidate;
        candidate = Path.of("../" + REPLAY_PATH);
        if (Files.exists(candidate)) return candidate;
        return null;
    }

    @Test
    @DisplayName("快速分析生成有效 JSON")
    void quickAnalysisProducesJson() throws Exception {
        var path = resolveReplay();
        if (path == null) {
            log.info("⚠ Skipping: replay file not found at " + REPLAY_PATH);
            return;
        }

        String json = ReplayAnalyzer.quick(path);

        assertNotNull(json);
        assertFalse(json.isBlank(), "JSON output should not be blank");
        assertTrue(json.contains("\"meta\""), "JSON should contain meta section");
        assertTrue(json.contains("\"summary\""), "JSON should contain summary section");
        assertTrue(json.contains("\"packets\""), "JSON should contain packets section");

        log.info("JSON length: " + json.length() + " chars");
        // Print first ~500 chars for inspection
        log.info(json);
    }

    @Test
    @DisplayName("从内存 ReplayFile 快速分析")
    void quickAnalysisFromReplayFile() throws Exception {
        var path = resolveReplay();
        if (path == null) {
            log.info("⚠ Skipping: replay file not found at " + REPLAY_PATH);
            return;
        }

        var replay = ReplayFile.fromFile(path);
        String json = ReplayAnalyzer.quick(replay);

        assertNotNull(json);
        assertTrue(json.contains("\"meta\""));
        log.info("Packet count: " + replay.packetCount());
        log.info("JSON output:  " + json.length() + " chars");
    }

    @Test
    @DisplayName("buildReport 返回结构化数据")
    void buildReportReturnsStructuredData() throws Exception {
        var path = resolveReplay();
        if (path == null) {
            log.info("⚠ Skipping: replay file not found at " + REPLAY_PATH);
            return;
        }

        var replay = ReplayFile.fromFile(path);
        var analyzer = ReplayAnalyzer.builder().build();
        var report = analyzer.buildReport(replay);

        assertNotNull(report);
        assertNotNull(report.meta());
        assertNotNull(report.summary());
        assertNotNull(report.packets());
        assertTrue(report.summary().totalPackets() > 0, "should have at least one packet");

        log.info("Player:      " + report.meta().playerName());
        log.info("Map:         " + report.meta().mapName());
        log.info("Total pkts:  " + report.summary().totalPackets());
        log.info("Duration:    " + report.summary().totalDuration() + "s");
        log.info("Packet types: " + report.packets().byType().size());
    }

    @Test
    @DisplayName("quick(null) 抛出异常")
    void quickWithNullReplay() {
        assertThrows(ReplayException.class, () -> ReplayAnalyzer.quick((ReplayFile) null),
            "quick(null) should throw ReplayException");
    }

    @Test
    @DisplayName("quick(不存在的文件) 抛出 IOException")
    void quickWithNonExistentFile() {
        assertThrows(IOException.class,
            () -> ReplayAnalyzer.quick(Path.of("nonexistent_12345.wowsreplay")),
            "quick(nonexistent path) should throw IOException");
    }
}
