package com.wows.dumper;

import com.wows.replay.core.ReplayException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class DumperPipelineTest {

    private static final String REPLAY_PATH =
        "temp/wg_15.7/20260727_230908_PJSB720-Aki_18_NE_ice_islands.wowsreplay";

    private Path resolveReplay() {
        var candidate = Path.of(REPLAY_PATH);
        if (Files.exists(candidate)) return candidate;
        candidate = Path.of("../" + REPLAY_PATH);
        return Files.exists(candidate) ? candidate : null;
    }

    @Test
    @DisplayName("Dump replay to JSON with default options")
    void dumpWithDefaultOptions() throws Exception {
        var path = resolveReplay();
        if (path == null) {
            System.out.println("⚠ Skipping: replay file not found");
            return;
        }

        var pipeline = new DumperPipeline(Path.of("."));
        String json = pipeline.dump(path, DumperPipeline.Options.DEFAULT);

        assertNotNull(json);
        assertFalse(json.isBlank());
        assertTrue(json.contains("\"meta\""));
        assertTrue(json.contains("\"summary\""));
        assertTrue(json.contains("\"packets\""));
        assertTrue(json.contains("\"version\""));
        System.out.println("JSON length: " + json.length());
    }

    @Test
    @DisplayName("Dump replay bytes")
    void dumpFromBytes() throws Exception {
        var path = resolveReplay();
        if (path == null) {
            System.out.println("⚠ Skipping: replay file not found");
            return;
        }

        byte[] bytes = Files.readAllBytes(path);
        var pipeline = new DumperPipeline(Path.of("."));
        String json = pipeline.dump(bytes, DumperPipeline.Options.DEFAULT);

        assertTrue(json.contains("\"meta\""));
    }

    @Test
    @DisplayName("Options defaults")
    void optionsDefaults() {
        var opts = DumperPipeline.Options.DEFAULT;
        assertFalse(opts.minimap());
        assertEquals(7, opts.minimapStep());
        assertNull(opts.constantsFile());
    }

    @Test
    @DisplayName("dump with non-existent file throws")
    void dumpNonExistent() {
        var pipeline = new DumperPipeline(Path.of("."));
        assertThrows(IOException.class, () ->
            pipeline.dump(Path.of("nonexistent_12345.wowsreplay"), DumperPipeline.Options.DEFAULT));
    }

    @Test
    @DisplayName("dump with null bytes throws")
    void dumpNullBytes() {
        var pipeline = new DumperPipeline(Path.of("."));
        assertThrows(ReplayException.class, () ->
            pipeline.dump((byte[]) null, DumperPipeline.Options.DEFAULT));
    }
}
