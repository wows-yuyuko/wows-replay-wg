package com.wows.replay.core;


import com.wows.replay.core.types.GameClock;
import com.wows.replay.core.types.Version;
import com.wows.replay.gamedata.GameDataCache;
import com.wows.replay.packets.Packet;
import com.wows.replay.packets.PacketParser;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 使用真实 .wowsreplay 文件的端到端测试。
 *
 * <p>The test replay is expected at {@code temp/wg_15.6/} relative to the
 * project root.  Tests are skipped gracefully if the file is absent.</p>
 */
@Slf4j
class ReplayFileTest {

    /** Path relative to project root. */
    private static final String REPLAY_PATH =
        "temp/wg_15.6/20260727_230908_PJSB720-Aki_18_NE_ice_islands.wowsreplay";
    private static final String REPLAY_PATH2 =
            "temp/wg_15.6/20260730_013138_PASB720-Rhode-Island_56_AngelWings.wowsreplay";
    private static final String WOWS_DATA_PATH =
            "temp/wows-data";

    private Path resolveReplay() {
        String projectRoot = System.getProperty("user.dir");
        return Path.of(projectRoot).getParent().resolve(REPLAY_PATH);
    }

    private Path resolveWowsData() {
        String projectRoot = System.getProperty("user.dir");
        return Path.of(projectRoot).getParent().resolve(WOWS_DATA_PATH);
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
        log.info("fromFile: " + count + " packets, " + replay.packetData().length + " bytes, start=" + start.seconds() + "s");

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
        log.info("fromBytes: first " + sampled + " packets OK");

        // ── 全部 packet 解码（带 EntitySpec）──────────────────────────────
        var wowsDataBase = resolveWowsData();
        var version = Version.fromClientExe(replay.meta().clientVersionFromExe());
        var gameData = findGameDataDir(wowsDataBase, version);
        assertNotNull(gameData, "should find matching game data under " + wowsDataBase);

        var cache = GameDataCache.withMaxSize(4);
        var specProvider = cache.entitySpecs(GameDataCache.VersionKey.from(gameData), gameData);
        assertNotNull(specProvider, "entity spec provider should not be null");

        var parser = new PacketParser(specProvider, replay.version());
        var byType = new LinkedHashMap<String, Integer>();
        var unknownIds = new LinkedHashMap<Integer, Integer>();
        int decoded = 0, unknown = 0, invalid = 0;

        var iter2 = replay.packetIterator();
        while (iter2.hasNext()) {
            var raw = iter2.next();
            if (raw.isUnknown()) {
                unknownIds.merge(raw.rawType(), 1, Integer::sum);
                // Dump payload for unknown types
                if (raw.rawType() == 0x2e) {
                    log.info("0x2e payload[{}]: {}", raw.payload().length, hexDump(raw.payload(), 64));
                }
                if (raw.rawType() == -1) {
                    log.warn("Corrupt packet at clock={}: size={} type=0x{} payload[{}]\n  hex: {}",
                        raw.clock().seconds(), raw.packetSize(),
                        Integer.toHexString(raw.rawType()),
                        raw.payload().length, hexDump(raw.payload(), 128));
                }
            }
            var pkt = parser.parse(raw);
            if (pkt.payload() instanceof Packet.InvalidPayload) {
                invalid++;
            } else if (pkt.packetType() == null) {
                unknown++;
            } else {
                decoded++;
                byType.merge(pkt.packetType().name(), 1, Integer::sum);
            }
        }

        log.info("全部解码: {} decoded, {} unknown, {} invalid ({} packet types)",
            decoded, unknown, invalid, byType.size());
        if (!unknownIds.isEmpty()) {
            unknownIds.forEach((id, n) -> log.info("  Unknown rawType=0x{} ({}): {} packets", Integer.toHexString(id), id, n));
        }
        assertTrue(decoded > 0, "should decode at least some packets");
        byType.forEach((type, n) -> log.info("  {}: {}", type, n));
    }

    /** 在 wows-data 目录下查找匹配版本的 data-{version}/live/ 目录。 */
    private static Path findGameDataDir(Path wowsDataBase, Version version) {
        var prefix = "data-" + version.major() + "." + version.minor() + ".";
        var dir = wowsDataBase.toFile();
        if (!dir.exists()) return null;
        var children = dir.listFiles();
        if (children == null) return null;
        for (var f : children) {
            if (f.isDirectory() && f.getName().startsWith(prefix)) {
                return f.toPath().resolve("live");
            }
        }
        return null;
    }

    private static String hexDump(byte[] data, int maxLen) {
        int len = Math.min(data.length, maxLen);
        var sb = new StringBuilder(len * 3);
        for (int i = 0; i < len; i++) {
            sb.append(String.format("%02x ", data[i] & 0xFF));
            if ((i + 1) % 32 == 0 && i + 1 < len) sb.append('\n');
        }
        if (data.length > maxLen) sb.append("... (").append(data.length).append(" bytes total)");
        return sb.toString();
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
