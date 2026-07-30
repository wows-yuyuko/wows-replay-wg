package com.wows.dumper;
import lombok.extern.slf4j.Slf4j;

import com.wows.replay.core.ReplayException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

@Slf4j
class DumperPipelineTest {

    private static final String REPLAY_PATH =
            "temp/wg_15.6/20260727_230908_PJSB720-Aki_18_NE_ice_islands.wowsreplay";
    private static final Path DATA_PATH = new File(System.getProperty("user.dir").replace("wows-dumper", "") + "temp" + File.separator + "wows-data").toPath();
    ;

    private Path resolveReplay() {
        var candidate = Path.of(REPLAY_PATH);
        if (Files.exists(candidate)) return candidate;
        candidate = Path.of("../" + REPLAY_PATH);
        return Files.exists(candidate) ? candidate : null;
    }

    @Test
    @DisplayName("默认选项导出 JSON")
    void dumpWithDefaultOptions() throws Exception {
        var path = resolveReplay();
        if (path == null) {
            log.info("⚠ Skipping: replay file not found");
            return;
        }

        var pipeline = new DumperPipeline(DATA_PATH);
        String json = pipeline.dump(path, DumperPipeline.Options.DEFAULT);

        assertNotNull(json);
        assertFalse(json.isBlank());
        log.info("JSON length: " + json.length());
        log.info(json);
    }

    @Test
    @DisplayName("从字节数组导出")
    void dumpFromBytes() throws Exception {
        var path = resolveReplay();
        if (path == null) {
            log.info("⚠ Skipping: replay file not found");
            return;
        }

        byte[] bytes = Files.readAllBytes(path);
        var pipeline = new DumperPipeline(DATA_PATH);
        String json = pipeline.dump(bytes, DumperPipeline.Options.DEFAULT);

        assertTrue(json.contains("\"meta\""));
    }

    @Test
    @DisplayName("Options 默认值")
    void optionsDefaults() {
        var opts = DumperPipeline.Options.DEFAULT;
        assertFalse(opts.minimap());
        assertEquals(7, opts.minimapStep());
        assertNull(opts.constantsFile());
    }

    @Test
    @DisplayName("不存在的文件抛出异常")
    void dumpNonExistent() {
        var pipeline = new DumperPipeline(DATA_PATH);
        assertThrows(IOException.class, () ->
                pipeline.dump(Path.of("nonexistent_12345.wowsreplay"), DumperPipeline.Options.DEFAULT));
    }

    @Test
    @DisplayName("null 字节抛出异常")
    void dumpNullBytes() {
        var pipeline = new DumperPipeline(DATA_PATH);
        assertThrows(ReplayException.class, () ->
                pipeline.dump((byte[]) null, DumperPipeline.Options.DEFAULT));
    }
}
