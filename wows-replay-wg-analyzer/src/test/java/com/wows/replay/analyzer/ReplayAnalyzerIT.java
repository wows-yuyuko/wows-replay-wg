package com.wows.replay.analyzer;

import com.wows.replay.core.ReplayException;
import com.wows.replay.core.ReplayFile;
import com.wows.replay.core.spi.EntitySpecProvider;
import com.wows.replay.core.types.Version;
import com.wows.replay.gamedata.GameDataCache;
import com.wows.replay.packets.PacketParser;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 完整集成测试：回放文件 → PacketParser → PacketDecoder → BattleWorld.
 *
 * <p>Mirrors Rust {@code replay-dumper} pipeline.</p>
 */
@Slf4j
class ReplayAnalyzerIT {

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

    private Path resolveReplay2() {
        String projectRoot = System.getProperty("user.dir");
        return Path.of(projectRoot).getParent().resolve(REPLAY_PATH2);
    }

    private Path resolveWowsData() {
        String projectRoot = System.getProperty("user.dir");
        return Path.of(projectRoot).getParent().resolve(WOWS_DATA_PATH);
    }


    @Test
    @DisplayName("BattleWorld 完整管线：PacketParser → PacketDecoder → BattleWorld")
    void battleWorldFullPipeline() throws Exception {
        var path = resolveReplay();
        var replay = ReplayFile.fromFile(path);
        var version = replay.version();

        // ── 加载 EntitySpec ──────────────────────────────────────────────
        var wowsData = resolveWowsData();
        var gameData = findGameDataDir(wowsData, version);
        assertNotNull(gameData, "should find game data under " + wowsData);

        var cache = GameDataCache.withMaxSize(4);
        EntitySpecProvider specProvider = cache.entitySpecs(
            GameDataCache.VersionKey.from(gameData), gameData);
        assertNotNull(specProvider, "entity spec provider should not be null");

        // ── Layer 1: PacketParser ───────────────────────────────────────
        var parser = new PacketParser(specProvider, version);

        // ── Layer 2: PacketDecoder ──────────────────────────────────────
        var decoder = new PacketDecoder(version);

        // ── Layer 3: BattleWorld ────────────────────────────────────────
        var world = new BattleWorld(replay.meta(), version);

        // ── 主循环：遍历所有 packet ──────────────────────────────────────
        int totalPackets = 0;
        int decodedPackets = 0;
        int entityMethods = 0;
        int entityCreates = 0;
        int positions = 0;

        var iter = replay.packetIterator();
        while (iter.hasNext()) {
            var raw = iter.next();
            totalPackets++;

            var packet = parser.parse(raw);
            if (packet == null || packet.payload() instanceof com.wows.replay.packets.Packet.InvalidPayload) {
                continue;
            }
            if (packet.packetType() == null) continue;

            var payload = decoder.decode(packet);
            world.process(payload, raw.clock());
            decodedPackets++;

            // 统计
            if (packet.packetType().name().contains("ENTITY_METHOD")) entityMethods++;
            if (packet.packetType().name().contains("ENTITY_CREATE")) entityCreates++;
            if (packet.packetType().name().contains("POSITION")) positions++;
        }

        world.finish();

        // ── 验证 ─────────────────────────────────────────────────────────
        log.info("数据包: {} 总计, {} 已解码, {} EntityMethod, {} EntityCreate, {} Position",
            totalPackets, decodedPackets, entityMethods, entityCreates, positions);
        log.info("实体: {} 个, 类型: {}", world.entities.size(), world.entityTypes);
        log.info("玩家: {} 个, entity→player: {} 映射",
            world.players.size(), world.entityToPlayer.size());
        log.info("击杀: {} 条, 伤害: {} 条, 聊天: {} 条, 消耗品: {} 条",
            world.killLog.size(), world.damageEvents.size(),
            world.chatLog.size(), world.consumableLog.size());
        log.info("占点: {} 个, Buff区域: {} 个, 天气: {} 个, 建筑: {} 个",
            world.capturePoints.size(), world.buffZones.size(),
            world.weatherZones.size(), world.buildings.size());
        log.info("mapName={}, winningTeam={}, finishType={}, matchResult={}",
            world.mapName, world.winningTeam, world.finishType, world.matchResult);

        assertTrue(totalPackets > 0, "should have packets");
        assertTrue(decodedPackets > 0, "should decode packets");
        assertFalse(world.entities.isEmpty(), "should have entities");

        // 验证玩家数据
        int playersWithName = 0;
        for (var es : world.entities.values()) {
            if (es.playerName != null && !es.playerName.isEmpty()) {
                playersWithName++;
                log.info("玩家实体: eid={} type={} team={} hp={}/{} name={} dbId={}",
                    es.id, es.type, es.teamId, es.health, es.maxHealth,
                    es.playerName, es.dbId);
            }
        }
        log.info("有名称的玩家实体: {} 个", playersWithName);
        assertTrue(playersWithName > 0, "should find at least one named player entity");

        // 打印玩家信息
        for (var entry : world.players.entrySet()) {
            var pi = entry.getValue();
            log.info("Player dbId={} name={} entityId={} team={}",
                entry.getKey(), pi.username, pi.entityId, pi.teamId);
        }
    }

    @Test
    @DisplayName("BattleWorld: 第二个 replay 文件")
    void battleWorldReplay2() throws Exception {
        var path = resolveReplay2();
        var replay = ReplayFile.fromFile(path);
        var version = replay.version();

        var wowsData = resolveWowsData();
        var gameData = findGameDataDir(wowsData, version);
        assertNotNull(gameData, "should find game data");

        var cache = GameDataCache.withMaxSize(4);
        var specProvider = cache.entitySpecs(GameDataCache.VersionKey.from(gameData), gameData);

        var parser = new PacketParser(specProvider, version);
        var decoder = new PacketDecoder(version);
        var world = new BattleWorld(replay.meta(), version);

        int decoded = 0;
        var iter = replay.packetIterator();
        while (iter.hasNext()) {
            var raw = iter.next();
            var packet = parser.parse(raw);
            if (packet == null || packet.payload() instanceof com.wows.replay.packets.Packet.InvalidPayload) continue;
            if (packet.packetType() == null) continue;

            var payload = decoder.decode(packet);
            world.process(payload, raw.clock());
            decoded++;
        }

        world.finish();
        log.info("Replay2: {} decoded, {} entities, {} players, {} kills",
            decoded, world.entities.size(), world.players.size(), world.killLog.size());
        assertTrue(decoded > 0);
    }

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
