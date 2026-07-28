package com.wows.replay.core;

import com.wows.replay.spec.types.GameClock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 使用真实 .wowsreplay 文件的端到端测试。
 *
 * <p>The test replay is expected at {@code temp/wg_15.7/} relative to the
 * project root.  Tests are skipped gracefully if the file is absent.</p>
 */
class ReplayFileTest {

    /** Path relative to project root. */
    private static final String REPLAY_PATH =
        "temp/wg_15.7/20260727_230908_PJSB720-Aki_18_NE_ice_islands.wowsreplay";

    private Path resolveReplay() {
        // Try relative to user.dir (project root when run via `mvn test`)
        var candidate = Path.of(REPLAY_PATH);
        if (Files.exists(candidate)) return candidate;

        // Try relative to the core module directory
        candidate = Path.of("../" + REPLAY_PATH);
        if (Files.exists(candidate)) return candidate;

        return null;
    }

    // ── Metadata-only ────────────────────────────────────────────────────────

    @Test
    @DisplayName("不解密包，只解析元数据")
    void parseMetadataOnly() throws Exception {
        var path = resolveReplay();
        if (path == null) {
            System.out.println("⚠ Skipping: replay file not found at " + REPLAY_PATH);
            return;
        }

        byte[] bytes = Files.readAllBytes(path);
        var meta = ReplayFile.metaFromBytes(bytes);

        assertNotNull(meta);
        assertNotNull(meta.playerName());
        assertFalse(meta.playerName().isBlank(), "playerName should not be blank");
        assertNotNull(meta.mapName(), "mapName should not be null");
        assertTrue(meta.duration() >= 0, "duration should be non-negative");
        assertNotNull(meta.playerVehicle(), "playerVehicle should not be null");

        System.out.println("Player:     " + meta.playerName());
        System.out.println("Vehicle:    " + meta.playerVehicle());
        System.out.println("Map:        " + meta.mapName());
        System.out.println("Version:    " + meta.clientVersionFromExe());
        System.out.println("Duration:   " + meta.duration() + "s");
        System.out.println("Vehicles:   " + meta.vehicles().size());
    }

    // ── Full parse ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("完整解析：解密+解压+包遍历")
    void fullParse() throws Exception {
        var path = resolveReplay();
        if (path == null) {
            System.out.println("⚠ Skipping: replay file not found at " + REPLAY_PATH);
            return;
        }

        var replay = ReplayFile.fromFile(path);

        assertNotNull(replay);
        assertNotNull(replay.meta());
        assertNotNull(replay.packetData());
        assertTrue(replay.packetData().length > 0, "packet data should not be empty");

        // Iterate packets
        int count = replay.packetCount();
        assertTrue(count > 0, "should have at least one packet");

        System.out.println("Packets:    " + count);
        System.out.println("Data size:  " + replay.packetData().length + " bytes");

        // Verify battle start clock
        GameClock start = replay.battleStartClock();
        assertNotNull(start);
        System.out.println("Battle start: " + start.seconds() + "s");
    }

    @Test
    @DisplayName("Full parse from in-memory bytes")
    void fullParseFromBytes() throws Exception {
        var path = resolveReplay();
        if (path == null) {
            System.out.println("⚠ Skipping: replay file not found at " + REPLAY_PATH);
            return;
        }

        byte[] bytes = Files.readAllBytes(path);
        var replay = ReplayFile.fromBytes(bytes);

        assertNotNull(replay);
        assertTrue(replay.packetCount() > 0);
        assertEquals(replay.packetData().length, replay.packetData().length);

        // Spot-check: first 10 packets should parse without error
        var iter = replay.packetIterator();
        int sampled = 0;
        while (iter.hasNext() && sampled < 10) {
            var pkt = iter.next();
            assertNotNull(pkt);
            assertTrue(pkt.packetSize() > 0, "packet size must be > 0");
            assertNotNull(pkt.payload());
            sampled++;
        }
        assertTrue(sampled > 0, "should have parsed at least one packet");
        System.out.println("First " + sampled + " packets parsed OK");
    }

    // ── Error handling ───────────────────────────────────────────────────────

    @Test
    @DisplayName("无效数据抛出 ReplayException")
    void fromBytesWithInvalidData() {
        assertThrows(ReplayException.class, () -> ReplayFile.fromBytes(new byte[]{1, 2, 3}));
    }

    @Test
    @DisplayName("null 抛出 ReplayException")
    void fromBytesWithNull() {
        assertThrows(ReplayException.class, () -> ReplayFile.fromBytes(null));
    }
}
