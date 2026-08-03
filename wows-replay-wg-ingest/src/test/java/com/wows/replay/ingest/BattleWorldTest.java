package com.wows.replay.ingest;

import com.wows.replay.ReplayMeta;
import com.wows.replay.decode.DecodedPayload;
import com.wows.replay.ingest.report.BattleReportBuilder;
import com.wows.replay.model.AccountId;
import com.wows.replay.model.GameClock;
import com.wows.replay.model.GameParamId;
import com.wows.replay.model.Version;
import com.wows.replay.spi.GameConstantsProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * BattleWorld 语义单元测试：§12.4.3 常量兜底 + §12.4.4 时钟推进。
 */
class BattleWorldTest {

    private static final Version VERSION = new Version(15, 6, 0, 12830008);

    private static ReplayMeta minimalMeta() {
        return new ReplayMeta(
            "test",                  // matchGroup
            1,                       // gameMode
            "RandomBattle",          // gameType
            "15,6,0,12830008",       // clientVersionFromExe
            0,                       // scenarioUiCategoryId
            "Test Display Map",      // mapDisplayName
            0,                       // mapId
            "15,6,0",                // clientVersionFromXml
            null,                    // weatherParams
            600,                     // duration
            null,                    // gameLogic
            "SelfPlayer",            // name
            "test_scenario",         // scenario
            new AccountId(100),      // playerID
            List.of(new ReplayMeta.VehicleInfoMeta(
                new GameParamId(500), 0, new AccountId(100), "SelfPlayer")), // vehicles (self)
            12,                      // playersPerTeam
            "2026-01-01T00:00:00",   // dateTime
            "spaces/test_map",       // mapName
            "SelfPlayer",            // playerName
            0,                       // scenarioConfigId
            2,                       // teamsCount
            null,                    // logic
            "RHODE_ISLAND",          // playerVehicle
            600                      // battleDuration
        );
    }

    // ── §12.4.4 时钟推进 ──────────────────────────────────────────────

    @Test
    @DisplayName("时钟推进: clock>0 || 当前==0 才更新，clock=0 不倒退已推进的时钟")
    void clockAdvancement() {
        var world = new BattleWorld(minimalMeta(), VERSION);

        // 开局 clock=0
        world.process(new DecodedPayload.RibbonPayload(1), GameClock.ZERO);
        assertEquals(0f, world.currentClock().seconds());

        // 正时钟推进
        world.process(new DecodedPayload.RibbonPayload(2), new GameClock(5f));
        assertEquals(5f, world.currentClock().seconds());

        // clock=0 的包不回退（§12.4.4）
        world.process(new DecodedPayload.RibbonPayload(3), GameClock.ZERO);
        assertEquals(5f, world.currentClock().seconds());

        // handler 使用推进后的时钟
        assertEquals(5f, world.ribbonLog().getLast().clock(), "事件时钟应取推进后的时钟");
    }

    // ── §12.4.3 常量兜底 ──────────────────────────────────────────────

    @Test
    @DisplayName("常量兜底: null 常量 → 空实现，不喂 null")
    void constantsDefaultFallback() {
        var world = new BattleWorld(minimalMeta(), VERSION, null);
        assertNotNull(world.constants(), "无 GameConstants 时应用默认空实现");
        assertEquals(Optional.empty(), world.constants().gameModeName(1));
    }

    @Test
    @DisplayName("常量注入: gameModeName 进入 report.gameMode 兜底链")
    void constantsFlowIntoReport() {
        GameConstantsProvider fake = new GameConstantsProvider() {
            @Override public Optional<String> gameModeName(int id) { return Optional.of("自定义模式"); }
        };
        var world = new BattleWorld(minimalMeta(), VERSION, fake);
        var report = new BattleReportBuilder(world, minimalMeta()).build();
        assertEquals("自定义模式", report.gameMode(), "本地化缺失时用 constants.gameModeName");
    }

    @Test
    @DisplayName("常量缺失: gameMode 回退到原始 scenario")
    void constantsAbsentFallsBackToScenario() {
        var world = new BattleWorld(minimalMeta(), VERSION);
        var report = new BattleReportBuilder(world, minimalMeta()).build();
        assertEquals("test_scenario", report.gameMode());
    }
}
