package com.shinoaki.wowsreplay.ingest;

import com.shinoaki.wowsreplay.core.JsonMapper;
import com.shinoaki.wowsreplay.core.ReplayFile;
import com.shinoaki.wowsreplay.core.decode.DecodedPayload;
import com.shinoaki.wowsreplay.core.decode.PacketDecoder;
import com.shinoaki.wowsreplay.core.model.GameClock;
import com.shinoaki.wowsreplay.core.model.Version;
import com.shinoaki.wowsreplay.core.packet.Packet;
import com.shinoaki.wowsreplay.core.packet.Parser;
import com.shinoaki.wowsreplay.core.spec.GameDataCache;
import com.shinoaki.wowsreplay.core.spi.EntitySpecProvider;
import com.shinoaki.wowsreplay.core.spi.GameConstantsProvider;
import com.shinoaki.wowsreplay.ingest.report.BattleReportBuilder;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * BattleWorld 摄入集成测试：回放文件 → Parser → PacketDecoder → BattleWorld。
 *
 * <p>ReplayAnalyzer / BattleReport 装配 / minimap 提取测试在 dumper 模块。</p>
 */
@Slf4j
class BattleWorldIT {

    private static final String REPLAY_PATH =
            "temp/wg_15.6/20260730_013138_PASB720-Rhode-Island_56_AngelWings.wowsreplay";
    private static final String WOWS_DATA_PATH = "temp/wows-data";

    private static ReplayFile replay;
    private static Version version;
    private static EntitySpecProvider specProvider;
    private static BattleWorld world;

    @BeforeAll
    static void setUp() throws Exception {
        replay = ReplayFile.fromFile(resolve(REPLAY_PATH));
        version = replay.version();

        var gameData = findGameDataDir(resolve(WOWS_DATA_PATH), version);
        assertNotNull(gameData, "游戏数据未找到: " + resolve(WOWS_DATA_PATH));
        specProvider = GameDataCache.withMaxSize(4)
            .entitySpecs(GameDataCache.VersionKey.from(gameData), gameData);

        // 完整管线：Parser → PacketDecoder → BattleWorld
        var parser = new Parser(specProvider, version);
        var decoder = new PacketDecoder(version);
        world = new BattleWorld(replay.meta(), version);
        var iter = replay.packetIterator();
        while (iter.hasNext()) {
            var raw = iter.next();
            var packet = parser.parse(raw);
            if (packet == null || packet.payload() instanceof Packet.InvalidPayload) continue;
            if (packet.packetType() == null) continue;
            world.process(decoder.decode(packet), raw.clock());
        }
        world.finish();
        log.info("=== 管线完成: {} players, {} entities, {} kills, {} chat, {} damage ===",
            world.players.size(), world.entities.size(), world.killLog.size(),
            world.chatLog.size(), world.damageEvents.size());
    }

    private static Path resolve(String path) {
        return Path.of(System.getProperty("user.dir")).getParent().resolve(path);
    }

    private static Path findGameDataDir(Path wowsDataBase, Version version) {
        var prefix = "data-" + version.major() + "." + version.minor() + ".";
        var children = wowsDataBase.toFile().listFiles();
        if (children == null) return null;
        for (var f : children) {
            if (f.isDirectory() && f.getName().startsWith(prefix)) {
                return f.toPath().resolve("live");
            }
        }
        return null;
    }

    @Test
    @DisplayName("BattleWorld: 实体/玩家/地图/战斗结果摄入正确")
    void battleWorldIngest() {
        assertFalse(world.entities.isEmpty(), "应摄入实体");
        assertTrue(world.entityTypes.contains("Avatar") && world.entityTypes.contains("Vehicle"),
            "应有 Avatar / Vehicle 实体");
        assertTrue(world.players.size() >= 20, "players 12v12 + bots (实际: " + world.players.size() + ")");
        assertFalse(world.chatLog.isEmpty(), "应有聊天消息");
        assertFalse(world.damageEvents.isEmpty(), "应有伤害事件");

        assertEquals("spaces/56_AngelWings", world.mapName, "map_name");
        assertEquals(Integer.valueOf(1), world.winningTeam, "winning_team");
        assertEquals("Loss", world.matchResult, "match_result");
        assertEquals(1200f, world.maxDuration, 0.001f, "max_duration");
        assertNotNull(world.playedDuration, "played_duration 不应为 null");
        log.info("BattleWorld ✓: {} kills, {} damage, {} chat, {} players",
            world.killLog.size(), world.damageEvents.size(), world.chatLog.size(), world.players.size());
    }

    @Test
    @DisplayName("BattleWorld.intoReport: 终局快照与内部状态一致且可序列化")
    void battleSnapshot() {
        var snap = world.intoReport();
        assertEquals("spaces/56_AngelWings", snap.mapName());
        assertEquals(Integer.valueOf(1), snap.winningTeam());
        assertEquals("Loss", snap.matchResult());
        assertEquals(world.players.size(), snap.players().size());
        assertEquals(world.killLog.size(), snap.kills().size());
        assertEquals(world.chatLog.size(), snap.chat().size());
        assertEquals(1200f, snap.maxDuration(), 0.001f);
        assertTrue(snap.playedDuration() > 0, "played_duration 应 > 0");
        assertTrue(snap.players().stream().allMatch(p -> p.dbId() > 0));

        var json = JsonMapper.toPrettyJson(snap);
        assertTrue(json.contains("\"map_name\""));
        log.info("BattleSnapshot ✓: players={}, kills={}, chat={}",
            snap.players().size(), snap.kills().size(), snap.chat().size());
    }

    @Test
    @DisplayName("BattleWorld 时钟推进: clock>0 || 当前==0 才更新，clock=0 不倒退（§12.4.4）")
    void clockAdvancement() {
        var w = new BattleWorld(replay.meta(), version);

        w.process(new DecodedPayload.RibbonPayload(1), GameClock.ZERO);
        assertEquals(0f, w.currentClock().seconds());

        w.process(new DecodedPayload.RibbonPayload(2), new GameClock(5f));
        assertEquals(5f, w.currentClock().seconds());

        w.process(new DecodedPayload.RibbonPayload(3), GameClock.ZERO);
        assertEquals(5f, w.currentClock().seconds(), "clock=0 的包不应倒退已推进的时钟");

        assertEquals(5f, w.ribbonLog().getLast().clock(), "事件时钟应取推进后的时钟");
    }

    @Test
    @DisplayName("BattleWorld 常量兜底: null → 空实现，gameModeName 进入 report 兜底链（§12.4.3）")
    void constantsFallback() {
        var worldNull = new BattleWorld(replay.meta(), version, null);
        assertNotNull(worldNull.constants(), "无 GameConstants 时应用默认空实现");
        assertEquals(Optional.empty(), worldNull.constants().gameModeName(1));

        var fake = new GameConstantsProvider() {
            @Override public Optional<String> gameModeName(int id) { return Optional.of("自定义模式"); }
        };
        var report = new BattleReportBuilder(new BattleWorld(replay.meta(), version, fake), replay.meta()).build();
        assertEquals("自定义模式", report.gameMode(), "本地化缺失时用 constants.gameModeName");

        var fallback = new BattleReportBuilder(new BattleWorld(replay.meta(), version), replay.meta()).build();
        assertEquals(replay.meta().scenario(), fallback.gameMode(), "常量缺失时回退到原始 scenario");
    }
}
