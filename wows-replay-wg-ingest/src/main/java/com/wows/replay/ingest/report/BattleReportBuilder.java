package com.wows.replay.ingest.report;

import com.wows.replay.JsonMapper;
import com.wows.replay.ReplayMeta;
import com.wows.replay.ingest.BattleWorld;
import com.wows.replay.ingest.DamageEvent;
import com.wows.replay.ingest.EntityState;
import com.wows.replay.model.*;
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
 *   <li>伤害汇总（damageByEntity + authoritativeSelfDamage）</li>
 *   <li>KillLog 展开（fragsByKiller / deathByVictim）</li>
 *   <li>战报 JSON 解析（playersPublicInfo）</li>
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
        // 1. 伤害汇总
        Map<EntityId, Double> damageByEntity = new LinkedHashMap<>();
        for (var e : world.damageByAggressor().entrySet()) {
            double total = e.getValue().stream().mapToDouble(DamageEvent::amount).sum();
            damageByEntity.put(new EntityId(e.getKey()), total);
        }
        Optional<Double> authoritativeSelfDamage = world.selfDamageStats().isEmpty()
            ? Optional.empty()
            : Optional.of(world.selfDamageStats().stream()
                .filter(e -> e.category() == DamageStatCategory.Enemy)
                .mapToDouble(DamageStatEntry::total).sum());

        // 2. KillLog 展开
        var fragsByKiller = new LinkedHashMap<EntityId, List<DeathInfo>>();
        var deathByVictim = new LinkedHashMap<EntityId, DeathInfo>();
        for (var kill : world.killLog()) {
            fragsByKiller.computeIfAbsent(new EntityId(kill.killerEid()), k -> new ArrayList<>())
                .add(DeathInfo.from(kill));
            deathByVictim.putIfAbsent(new EntityId(kill.victimEid()), DeathInfo.from(kill));
        }

        // 3. 战报 JSON 解析
        JsonNode parsedBattleResults = null;
        if (world.battleResultsJson() != null) {
            try {
                parsedBattleResults = JsonMapper.readTree(world.battleResultsJson());
            } catch (Exception ignored) { /* 解析失败→null，不崩溃（§7.2） */ }
        }

        // 4. 构建 Player 列表
        List<Player> players = new ArrayList<>();
        for (var entry : world.players().entrySet()) {
            long metaId = entry.getKey();
            long dbId = world.accountIdOf(metaId);
            var info = entry.getValue();
            boolean isSelf = info.relation == 0;
            var vehicle = buildVehicleEntity(info.entityId, dbId, isSelf,
                damageByEntity, authoritativeSelfDamage, deathByVictim, fragsByKiller,
                parsedBattleResults);
            players.add(new Player(metaId, dbId, info.entityId, info.username, info.teamId, info.relation,
                isBot(metaId, info.entityId), vehicle));
        }

        // 5. frags 关联到 Player（用战舰实体 id 反查）
        Map<Long, List<DeathInfo>> frags = new LinkedHashMap<>();
        for (var p : players) {
            var veh = p.vehicleEntity();
            if (veh != null) {
                var f = fragsByKiller.get(veh.id());
                if (f != null && !f.isEmpty()) frags.put(p.metaId(), f);
            }
        }

        // 6. self_player
        Player selfPlayer = players.stream().filter(p -> p.relation() == 0).findFirst()
            .orElseThrow(() -> new IllegalStateException(
                "could not resolve the recording (self) player: replay carries no roster RPC (pre-0.9 format)"));

        // 7. 时钟与时长（§5.5）；0 作为"未设置"哨兵（战斗时钟实际都远大于 0）。
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

        // 8. 胜负判定（§5.4）
        MatchResult matchResult = null;
        if (world.matchFinished()) {
            int winning = world.winningTeam();
            if (winning == selfPlayer.teamId()) matchResult = MatchResult.WIN;
            else if (winning >= 0) matchResult = MatchResult.LOSS;
            else matchResult = MatchResult.DRAW;
        }

        // 9. 元数据（§5.7）
        Version version = Version.fromClientExe(meta.clientVersionFromExe());
        String mapName = meta.mapName();
        // 无本地化资源时回退到常量里的模式名（§12.4.3），再无则原始 scenario。
        String gameMode = world.constants().gameModeName(meta.gameMode()).orElse(meta.scenario());
        Recognized<BattleType> gameType = BattleType.fromValue(meta.gameType(), version);
        String matchGroup = meta.matchGroup() != null ? meta.matchGroup() : "";
        long maxDuration = world.maxDuration() != 0f
            ? (long) world.maxDuration() : meta.duration();
        long arenaId = parseArenaId(world.arenaId());

        // 10. 快照其余字段
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
            players, gameChat, world.battleResultsJson(), frags, matchResult, finishType,
            capturePoints, buffZones, capturedBuffs, teamScores, buildings, weatherZones,
            world.battleStartClock(), world.selfDamageStats(), activeConsumables,
            maxDuration, playedDuration, extraDuration);
    }

    // ── VehicleEntity 构建（§5.1 / §5.3）────────────────────────────────────

    private VehicleEntity buildVehicleEntity(int playerEntityId, long dbId, boolean isSelf,
                                             Map<EntityId, Double> damageByEntity,
                                             Optional<Double> authoritativeSelfDamage,
                                             Map<EntityId, DeathInfo> deathByVictim,
                                             Map<EntityId, List<DeathInfo>> fragsByKiller,
                                             JsonNode parsedBattleResults) {
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
        double damage = isSelf
            ? authoritativeSelfDamage.orElseGet(() -> damageByEntity.getOrDefault(id, 0.0))
            : damageByEntity.getOrDefault(id, 0.0);

        GameParamId captain = es.captainParamsId != null && es.captainParamsId != 0
            ? new GameParamId(es.captainParamsId) : null;

        JsonNode resultsInfo = null;
        if (parsedBattleResults != null) {
            var publicInfo = parsedBattleResults.get("playersPublicInfo");
            if (publicInfo != null) resultsInfo = publicInfo.get(String.valueOf(dbId));
        }

        com.wows.replay.data.ShipConfig shipConfig = null;
        if (es.shipConfig != null) {
            shipConfig = com.wows.replay.data.ShipConfig.parse(es.shipConfig,
                Version.fromClientExe(meta.clientVersionFromExe()));
        }

        return new VehicleEntity(
            id,
            0.0f,                                   // visibilityChangedAt 恒为 0.0（§5.3）
            toVehicleProps(es),
            captain,
            damage,
            deathByVictim.get(id),
            resultsInfo,
            fragsByKiller.getOrDefault(id, List.of()),
            shipConfig);
    }

    /** 反查 vehicleToOwner 得到玩家战舰的实体 id；玩家船复用 Avatar id 时就是它自己。 */
    private int resolveVehicleEid(int playerEntityId) {
        for (var e : world.vehicleToOwner().entrySet()) {
            if (e.getValue() == playerEntityId) return e.getKey();
        }
        return playerEntityId;
    }

    private VehicleProps toVehicleProps(EntityState es) {
        return new VehicleProps(
            es.health, es.maxHealth, es.isAlive, es.isInvisible, es.teamId,
            new Vec3(es.x, es.y, es.z), es.heading);
    }

    private boolean isBot(long metaId, int entityId) {
        var arena = world.arenaPlayers().get(metaId);
        if (arena != null) return arena.isBot();
        var es = world.entities().get(entityId);
        return es != null && es.isBot;
    }

    private CapturePointState toCapturePoint(com.wows.replay.ingest.CapturePointState cp) {
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
