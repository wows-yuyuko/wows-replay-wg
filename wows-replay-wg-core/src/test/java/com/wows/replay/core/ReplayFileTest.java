package com.wows.replay.core;

import com.wows.replay.core.types.GameClock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 使用真实 .wowsreplay 文件的端到端测试。
 *
 * <p>The test replay is expected at {@code temp/wg_15.6/} relative to the
 * project root.  Tests are skipped gracefully if the file is absent.</p>
 */
class ReplayFileTest {

    /** Path relative to project root. */
    private static final String REPLAY_PATH =
        "temp/wg_15.6/20260727_230908_PJSB720-Aki_18_NE_ice_islands.wowsreplay";

    private Path resolveReplay() {
        String projectRoot = System.getProperty("user.dir");
        return Path.of(projectRoot).getParent().resolve(REPLAY_PATH);
    }



    // ── Full parse ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("完整解析：fromFile + fromBytes 两条路径")
    void fullParse() throws Exception {
        var path = resolveReplay();
        // ── fromFile ──────────────────────────────────────────────────────
        var replay = ReplayFile.fromFile(path);

        assertNotNull(replay);
        assertNotNull(replay.meta());
        assertNotNull(replay.packetData());
        assertTrue(replay.packetData().length > 0, "packet data should not be empty");

        int count = replay.packetCount();
        assertTrue(count > 0, "should have at least one packet");

        GameClock start = replay.battleStartClock();
        assertNotNull(start);
        System.out.println("fromFile: " + count + " packets, " + replay.packetData().length + " bytes, start=" + start.seconds() + "s");

        // ── fromBytes ─────────────────────────────────────────────────────
        byte[] bytes = Files.readAllBytes(path);
        var replay2 = ReplayFile.fromBytes(bytes);

        assertNotNull(replay2);
        assertEquals(count, replay2.packetCount(), "fromFile and fromBytes should yield same packet count");

        // Spot-check: first 10 packets should parse without error
        var iter = replay2.packetIterator();
        int sampled = 0;
        while (iter.hasNext() && sampled < 10) {
            var pkt = iter.next();
            assertNotNull(pkt);
            assertTrue(pkt.packetSize() > 0, "packet size must be > 0");
            assertNotNull(pkt.payload());
            sampled++;
        }
        assertTrue(sampled > 0, "should have parsed at least one packet");
        System.out.println("fromBytes: first " + sampled + " packets OK");
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
