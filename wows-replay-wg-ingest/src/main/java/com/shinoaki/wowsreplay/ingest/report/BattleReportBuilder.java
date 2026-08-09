package com.shinoaki.wowsreplay.ingest.report;

import com.shinoaki.wowsreplay.core.JsonConstantsProvider;
import com.shinoaki.wowsreplay.core.JsonMapper;
import com.shinoaki.wowsreplay.core.ReplayMeta;
import com.shinoaki.wowsreplay.core.data.BattleResultsResolver;
import com.shinoaki.wowsreplay.core.data.ShipConfig;
import com.shinoaki.wowsreplay.ingest.BattleWorld;
import com.shinoaki.wowsreplay.ingest.EntityState;
import com.shinoaki.wowsreplay.core.model.*;
import tools.jackson.databind.JsonNode;

import java.util.*;

/**
 * 战斗结束报告装配器（对标 Rust {@code BattleWorld::into_report()}，report.rs）。
 *
 * <p>一次性、消费式装配：读 {@link BattleWorld} 累积的状态 + {@link ReplayMeta}，
 * 产出独立拥有的 {@link BattleReport} 快照。不解析任何新包。</p>
 *
 * <h2>装配流程（report.rs §4）</h2>
 * <ol>
 *   <li>KillLog 展开（fragsByKiller）</li>
 *   <li>战报 JSON 解析（playersPublicInfo → battleResults）</li>
 *   <li>构建 Player 列表（含 VehicleEntity）</li>
 *   <li>frags 关联到 Player</li>
 *   <li>self_player 解析（必须存在，§7.3）</li>
 *   <li>时钟与时长（§5.5）</li>
 *   <li>胜负判定（§5.4）</li>
 *   <li>元数据字段（§5.7）</li>
 *   <li>快照其余字段</li>
 * </ol>
 */
public final class BattleReportBuilder {

    private final BattleWorld world;
    private final ReplayMeta meta;

    public BattleReportBuilder(BattleWorld world, ReplayMeta meta) {
        this.world = world;
        this.meta = meta;
    }

    /** 核心入口：装配完整战报。应在 {@code world.finish()} 之后调用。 */
    public BattleReport build() {
        // 1. KillLog 展开（fragsByKiller：击杀者战舰实体 → 击杀列表，供顶层 frags）
        var fragsByKiller = new LinkedHashMap<EntityId, List<DeathInfo>>();
        for (var kill : world.killLog()) {
            fragsByKiller.computeIfAbsent(new EntityId(kill.killerEid()), k -> new ArrayList<>())
                .add(DeathInfo.from(kill));
        }

        // 2. 战报 JSON 解析（用 constants.json 解析为具名对象，作为 BattleReport.battleResults）
        JsonNode parsedBattleResults = null;
        JsonNode resolvedResults = null;
        if (world.battleResultsJson() != null) {
            try {
                parsedBattleResults = JsonMapper.readTree(world.battleResultsJson());
                // 用 constants.json 把 playersPublicInfo/playersPrivateInfo 位置数组解析为具名对象
                // （对标 pipeline.rs resolve_battle_results），作为 BattleReport.battleResults。
                if (world.constants() instanceof JsonConstantsProvider jcp) {
                    resolvedResults = BattleResultsResolver.resolve(parsedBattleResults, jcp.root());
                }
            } catch (Exception ignored) { /* 解析失败→null，不崩溃（§7.2） */ }
        }

        // 3. 构建 Player 列表
        List<Player> players = new ArrayList<>();
        for (var entry : world.players().entrySet()) {
            long metaId = entry.getKey();
            long dbId = world.accountIdOf(metaId);
            var info = entry.getValue();
            boolean isSelf = info.relation == 0;
            var vehicle = buildVehicleEntity(info.entityId, isSelf);
            var initialState = world.arenaPlayers().get(metaId);
            players.add(new Player(metaId, dbId, info.entityId, info.username, info.teamId, info.relation,
                isBot(metaId, info.entityId), initialState, vehicle));
        }

        // 4. frags 关联到 Player（用战舰实体 id 反查）
        Map<Long, List<DeathInfo>> frags = new LinkedHashMap<>();
        for (var p : players) {
            var veh = p.vehicleEntity();
            if (veh != null) {
                var f = fragsByKiller.get(veh.id());
                if (f != null && !f.isEmpty()) frags.put(p.metaId(), f);
            }
        }

        // 5. self_player
        Player selfPlayer = players.stream().filter(p -> p.relation() == 0).findFirst()
            .orElseThrow(() -> new IllegalStateException(
                "could not resolve the recording (self) player: replay carries no roster RPC (pre-0.9 format)"));

        // 6. 时钟与时长（§5.5）；0 作为"未设置"哨兵（战斗时钟实际都远大于 0）。
        float matchEnd = world.battleResultClock() != 0f
            ? world.battleResultClock() : world.battleEndClock();
        float playedDuration = 0f;
        if (world.battleStartClock() != 0f && matchEnd != 0f) {
            playedDuration = matchEnd - world.battleStartClock();
        }
        float extraDuration = 0f;
        if (matchEnd != 0f && world.battleEndClock() != 0f && world.battleEndClock() > matchEnd) {
            extraDuration = world.battleEndClock() - matchEnd;
        }

        // 7. 胜负判定（§5.4）
        MatchResult matchResult = null;
        if (world.matchFinished()) {
            int winning = world.winningTeam();
            if (winning == selfPlayer.teamId()) matchResult = MatchResult.WIN;
            else if (winning >= 0) matchResult = MatchResult.LOSS;
            else matchResult = MatchResult.DRAW;
        }

        // 8. 元数据（§5.7）
        Version version = Version.fromClientExe(meta.clientVersionFromExe());
        String mapName = meta.mapName();
        // 无本地化资源时回退到常量里的模式名（§12.4.3），再无则原始 scenario。
        String gameMode = world.constants().gameModeName(meta.gameMode()).orElse(meta.scenario());
        Recognized<BattleType> gameType = BattleType.fromValue(meta.gameType(), version);
        String matchGroup = meta.matchGroup() != null ? meta.matchGroup() : "";
        long maxDuration = world.maxDuration() != 0f
            ? (long) world.maxDuration() : meta.duration();
        long arenaId = parseArenaId(world.arenaId());

        // 9. 快照其余字段
        var gameChat = world.chatLog().stream()
            .map(c -> new GameMessage(c.clock(), world.accountIdOf(c.metaId()), c.senderName(), c.channel(), c.message()))
            .toList();
        var capturePoints = world.capturePoints().stream()
            .map(this::toCapturePoint)
            .toList();
        var teamScores = world.teamScores().stream()
            .map(t -> new TeamScore(t.teamIndex(), t.score()))
            .toList();
        var capturedBuffs = world.capturedBuffs().stream()
            .map(c -> new CapturedBuff(c.entityId(), c.paramsId(), c.capturedBy(), c.clock()))
            .toList();
        var buildings = world.buildings().stream()
            .map(b -> new BuildingEntity(new EntityId(b.entityId()), b.x(), b.z(),
                b.isAlive(), false, false, b.teamId(), new GameParamId(b.paramsId())))
            .toList();
        var weatherZones = world.weatherZones().stream()
            .map(w -> new LocalWeatherZone(w.name(), w.x(), w.z(), w.radius(), w.paramsId(), w.entityId()))
            .toList();
        Map<EntityId, BuffZoneState> buffZones = new LinkedHashMap<>();
        for (var e : world.buffZones().entrySet()) {
            var b = e.getValue();
            buffZones.put(new EntityId(b.entityId()),
                new BuffZoneState(b.entityId(), b.x(), b.z(), b.radius(), b.teamId(), b.isActive(), b.dropParamsId()));
        }
        Map<EntityId, List<ActiveConsumable>> activeConsumables = new LinkedHashMap<>();
        for (var c : world.consumableLog()) {
            activeConsumables.computeIfAbsent(new EntityId(c.entityId()), k -> new ArrayList<>())
                .add(new ActiveConsumable((int) c.consumableId(), c.duration(), c.clock()));
        }

        Recognized<FinishType> finishType = world.finishTypeId() != 0
            ? FinishType.fromRaw(world.finishTypeId(), world.gameConstants(), world.version()) : null;

        return new BattleReport(
            arenaId, selfPlayer, version, mapName, gameMode, gameType, matchGroup,
            players, gameChat, resolvedResults != null ? resolvedResults : parsedBattleResults, frags, matchResult,
            finishType,
            capturePoints, buffZones, capturedBuffs, teamScores, buildings, weatherZones,
            world.battleStartClock(), world.selfDamageStats(), activeConsumables,
            maxDuration, playedDuration, extraDuration);
    }

    // ── VehicleEntity 构建（§5.1 / §5.3）────────────────────────────────────

    private VehicleEntity buildVehicleEntity(int playerEntityId, boolean isSelf) {
        int vehicleEid = resolveVehicleEid(playerEntityId);
        EntityState es = world.entities().get(vehicleEid);
        if (es == null) {
            // §7.3：真实战斗（已摄入实体）中 self 玩家的 VehicleEntity 必须存在，
            // 找不到说明战舰实体未摄入成功；空世界（合成/未处理）则容错返回 null。
            if (isSelf && !world.entities().isEmpty()) {
                throw new IllegalStateException(
                    "self player 的战舰实体未找到: playerEntityId=" + playerEntityId + ", vehicleEid=" + vehicleEid);
            }
            return null;
        }

        EntityId id = new EntityId(vehicleEid);
        GameParamId captain = es.captainParamsId != null && es.captainParamsId != 0
            ? new GameParamId(es.captainParamsId) : null;

        ShipConfig shipConfig = null;
        if (es.shipConfig != null) {
            shipConfig = ShipConfig.parse(es.shipConfig,
                Version.fromClientExe(meta.clientVersionFromExe()));
        }
        if (shipConfig != null) {
            shipConfig = shipConfig.withCommander(es.captainSkills, es.captainParamsId);
        }

        return new VehicleEntity(
            id,
            0.0f,                                   // visibilityChangedAt 恒为 0.0（§5.3）
            captain,
            shipConfig);
    }

    /** 反查 vehicleToOwner 得到玩家战舰的实体 id；玩家船复用 Avatar id 时就是它自己。 */
    private int resolveVehicleEid(int playerEntityId) {
        for (var e : world.vehicleToOwner().entrySet()) {
            if (e.getValue() == playerEntityId) return e.getKey();
        }
        return playerEntityId;
    }

    private boolean isBot(long metaId, int entityId) {
        var arena = world.arenaPlayers().get(metaId);
        if (arena != null) return arena.isBot();
        var es = world.entities().get(entityId);
        return es != null && es.isBot;
    }

    private CapturePointState toCapturePoint(com.shinoaki.wowsreplay.ingest.CapturePointState cp) {
        float x = cp.position != null && cp.position.length >= 2 ? cp.position[0] : 0f;
        float z = cp.position != null && cp.position.length >= 2 ? cp.position[1] : 0f;
        return new CapturePointState(cp.index, cp.teamId, cp.invaderTeam,
            cp.hasInvaders, cp.bothInside, cp.isEnabled, cp.progress, x, z, cp.radius);
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    private static long parseArenaId(String arenaId) {
        if (arenaId == null) return 0L;
        try {
            return Long.parseLong(arenaId);
        } catch (NumberFormatException e) {
            return 0L;
        }
    }
}
