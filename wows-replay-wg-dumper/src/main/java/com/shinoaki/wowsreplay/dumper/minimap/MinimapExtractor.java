package com.shinoaki.wowsreplay.dumper.minimap;

import com.shinoaki.wowsreplay.core.ReplayFile;
import com.shinoaki.wowsreplay.core.constant.GameConstants;
import com.shinoaki.wowsreplay.core.decode.PacketDecoder;
import com.shinoaki.wowsreplay.core.model.Vec3;
import com.shinoaki.wowsreplay.core.packet.NamedArgs;
import com.shinoaki.wowsreplay.core.packet.Packet;
import com.shinoaki.wowsreplay.core.packet.Parser;
import com.shinoaki.wowsreplay.core.spi.EntitySpecProvider;
import com.shinoaki.wowsreplay.core.spi.GameConstantsProvider;
import com.shinoaki.wowsreplay.ingest.ArtillerySalvo;
import com.shinoaki.wowsreplay.ingest.BattleWorld;
import com.shinoaki.wowsreplay.ingest.ShotHitRecord;
import com.shinoaki.wowsreplay.ingest.mapped.ReplayMapper;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;

/**
 * Minimap 数据提取器（对标 Rust {@code replay-dumper::position::extract_minimap_data}，
 * docs/replay-dumper-minimap.md §4）。
 *
 * <p><b>Single 模式</b>：仅主回放驱动一个 {@link BattleWorld}，逐时钟边界冲刷事件流
 * （damage 条数 diff / 齐射去重 / shot_hits diff），每时钟边界抽一帧全量快照，
 * 循环结束后装配终局状态。多视角合并（Full/Fast）暂不实现。</p>
 *
 * <p>坐标解码复用 {@link PacketDecoder#decodeMinimapVision(NamedArgs)}（packedData 位布局见
 * docs §3），位置直接读 {@link EntityState} 的 minimap 状态。</p>
 */
public final class MinimapExtractor {

    private final EntitySpecProvider specProvider;
    private final GameConstantsProvider constants;
    private final GameConstants gameConstants;
    private final ReplayFile replay;

    public MinimapExtractor(EntitySpecProvider specProvider, ReplayFile replay) {
        this(specProvider, null, replay);
    }

    public MinimapExtractor(EntitySpecProvider specProvider, GameConstantsProvider constants,
                            ReplayFile replay) {
        this.specProvider = specProvider;
        this.constants = constants;
        this.gameConstants = new GameConstants(constants);
        this.replay = replay;
    }

    /** 提取 minimap 数据（Single 模式）。 */
    public MinimapOutput extract() {
        var frames = new ArrayList<MinimapOutput.MinimapFrame>();
        var core = runCore((world, clock) -> frames.add(snapshot(world, clock)));
        return new MinimapOutput(core.arenaId(), frames, core.firingEvents(), core.damageEvents(),
            core.shotHits(), core.deadShips(), core.battleStage(), core.winningTeam(),
            core.finishType(), core.scoringRules(), core.capturedBuffs());
    }

    /**
     * 压缩输出（外部程序分析用）：与 {@link #extract()} 同结构，保留每帧
     * planes/torpedoes/smoke_screens/buildings/wards/buff_zones/weather_zones/team_scores/
     * capture_points/time_left 及全部事件流（含可见性 spotting），仅将 frames 的
     * {@code entities} 改为<b>移动增量</b>：每帧只列出自上次输出以来位置（归一化坐标移动
     * ≥ {@value #MOVE_EPSILON}）或航向（≥ {@value #HEADING_EPSILON}°）或可见/血量/存活等
     * 发生显著变化、或首次出现的船；静止/匀速直行的船不再重复输出，显著减小移动数据体积。
     * 消费方需与上一帧状态合并（{@code movement_delta: true}）。
     */
    public MinimapOutput.Compressed extractCompressed() {
        var frames = new ArrayList<MinimapOutput.MinimapFrame>();
        var lastState = new HashMap<Long, MinimapOutput.MinimapEntity>();
        var core = runCore((world, clock) -> frames.add(snapshotDelta(world, clock, lastState)));
        return new MinimapOutput.Compressed(true, core.arenaId(), frames, core.firingEvents(),
            core.damageEvents(), core.shotHits(), core.deadShips(), core.battleStage(),
            core.winningTeam(), core.finishType(), core.scoringRules(), core.capturedBuffs());
    }

    /** 移动显著变化阈值（归一化坐标，地图范围 ±1.5）：超过才输出增量，压缩静止/匀速段。 */
    private static final float MOVE_EPSILON = 0.01f;
    /** 航向显著变化阈值（度）。 */
    private static final float HEADING_EPSILON = 1.0f;

    /** 两条船位状态是否算“显著变化”（位置/航向/可见/探测/隐身/血量/存活/敌我任一变）。 */
    private static boolean entityChanged(MinimapOutput.MinimapEntity a, MinimapOutput.MinimapEntity b) {
        if (Math.abs(a.x() - b.x()) >= MOVE_EPSILON) return true;
        if (Math.abs(a.y() - b.y()) >= MOVE_EPSILON) return true;
        if (Math.abs(a.heading() - b.heading()) >= HEADING_EPSILON) return true;
        return a.visible() != b.visible()
            || a.visibilityFlags() != b.visibilityFlags()
            || a.isInvisible() != b.isInvisible()
            || a.teamId() != b.teamId()
            || a.health() != b.health()
            || a.maxHealth() != b.maxHealth()
            || a.isAlive() != b.isAlive()
            || a.side() != b.side();
    }

    /** 帧快照（压缩模式）：entities 只含显著变化/首次出现的船，其余帧内状态同全量。 */
    private static MinimapOutput.MinimapFrame snapshotDelta(BattleWorld world, float clock,
                                                            Map<Long, MinimapOutput.MinimapEntity> lastState) {
        var entities = new ArrayList<MinimapOutput.MinimapEntity>();
        for (var es : world.entities().values()) {
            // 只有收到过 minimap 更新（有归一化坐标）的实体才输出；玩家身份归一为 metaId
            if (Float.isNaN(es.minimapX) || Float.isNaN(es.minimapZ)) continue;
            float heading = Float.isNaN(es.minimapHeading) ? 0f : es.minimapHeading;
            int side = es.relation >= 0 ? es.relation : 2;
            long metaId = ReplayMapper.metaIdOf(world, es.id.value());
            var e = new MinimapOutput.MinimapEntity(metaId, es.minimapX, es.minimapZ,
                heading, es.visible, es.visibilityFlags, es.isInvisible,
                es.teamId, es.health, es.maxHealth, es.isAlive, side);
            var prev = lastState.get(metaId);
            if (prev == null || entityChanged(prev, e)) {
                entities.add(e);
                lastState.put(metaId, e);
            }
        }
        return frame(world, clock, entities);
    }

    /** 共享解析循环的事件流/终局结果（帧与移动增量由回调按各自策略装配）。 */
    private record Core(Long arenaId, List<MinimapOutput.ShotEntry> firingEvents,
                        List<MinimapOutput.DamageEntry> damageEvents,
                        List<MinimapOutput.ShotHitEntry> shotHits, List<MinimapOutput.DeadShip> deadShips,
                        String battleStage, Integer winningTeam, String finishType,
                        MinimapOutput.ScoringRules scoringRules, List<MinimapOutput.CapturedBuff> capturedBuffs) {
    }

    private Core runCore(BiConsumer<BattleWorld, Float> boundary) {
        var world = new BattleWorld(replay.meta(), replay.version(), constants);
        var parser = new Parser(specProvider, replay.version());
        var decoder = new PacketDecoder(replay.version());

        var firingEvents = new ArrayList<MinimapOutput.ShotEntry>();
        var damageEvents = new ArrayList<MinimapOutput.DamageEntry>();
        var shotHits = new ArrayList<MinimapOutput.ShotHitEntry>();
        var seenSalvos = new HashSet<Long>();
        int lastDamageCount = 0;
        int lastHitCount = 0;
        float lastClock = Float.NaN;

        var iter = replay.packetIterator();
        while (iter.hasNext()) {
            var raw = iter.next();
            var packet = parser.parse(raw);
            if (packet == null || packet.payload() instanceof Packet.InvalidPayload) continue;
            if (packet.packetType() == null) continue;
            world.process(decoder.decode(packet), raw.clock());

            // 时钟边界：冲刷事件流 + 回调装配（帧抽稀或移动增量）
            float clock = world.currentClock().seconds();
            if (clock != lastClock) {
                // 1. damage：damageEvents 条数 diff，只推新事件（玩家身份归一为 metaId）
                var dmg = world.damageEvents();
                for (int i = lastDamageCount; i < dmg.size(); i++) {
                    var d = dmg.get(i);
                    damageEvents.add(new MinimapOutput.DamageEntry(d.clock(),
                        ReplayMapper.metaIdOf(world, d.aggressorId()),
                        ReplayMapper.metaIdOf(world, d.victimId()), d.amount()));
                }
                lastDamageCount = dmg.size();

                // 2. firing：(avatar_id, salvo_id) 去重，新齐射才输出（玩家身份归一为 metaId）
                for (var s : world.firedSalvos()) {
                    long key = ((long) s.avatarId() << 32) | (s.salvo().salvoId() & 0xFFFFFFFFL);
                    if (seenSalvos.add(key)) firingEvents.add(toShotEntry(s, world));
                }

                // 2b. shot_id → fired_at 映射（shot_hits 关联起源齐射；用 (owner,shot) 组合 key
                //     避免不同齐射 shot_id 复用导致的覆盖误配）
                var firedAtByShot = new HashMap<Long, Float>();
                for (var s : world.firedSalvos()) {
                    int owner = s.salvo().ownerId().value();
                    for (var sh : s.salvo().shots()) {
                        firedAtByShot.put(((long) owner << 32) | (sh.shotId() & 0xFFFFFFFFL), s.clock());
                    }
                }

                // 3. shot_hits：条数 diff
                var hits = world.shotHits();
                for (int i = lastHitCount; i < hits.size(); i++) {
                    shotHits.add(toShotHitEntry(hits.get(i), firedAtByShot, world));
                }
                lastHitCount = hits.size();

                boundary.accept(world, clock);
                lastClock = clock;
            }
        }
        world.finish();

        Long arenaId = null;
        if (world.arenaId() != null) {
            try {
                arenaId = Long.parseLong(world.arenaId());
            } catch (NumberFormatException ignored) {}
        }
        String finishType = world.finishType() != null ? world.finishType()
            : (world.finishTypeId() != 0 ? String.valueOf(world.finishTypeId()) : null);

        return new Core(arenaId, firingEvents, damageEvents, shotHits,
            world.deadShips().stream()
                .map(ds -> new MinimapOutput.DeadShip(ds.clock(),
                    ReplayMapper.metaIdOf(world, ds.victimId()), ds.x(), ds.z()))
                .toList(),
            battleStageName(world.battleStageId()), world.winningTeam(), finishType,
            new MinimapOutput.ScoringRules(world.teamWinScore(), world.holdReward(),
                world.holdPeriod(), world.holdCpIndices()),
            world.capturedBuffs().stream()
                .map(cb -> new MinimapOutput.CapturedBuff(cb.paramsId(), cb.teamId(), cb.clock()))
                .toList());
    }

    /**
     * 战斗阶段 id → 阶段名（对齐 Rust BattleStage Debug，0=Waiting..4=Ended）。
     * 委托统一布局管理器 {@link GameConstants#battleStageName}。
     */
    private String battleStageName(int id) {
        return gameConstants.battleStageName(id, replay.version());
    }

    // ── 事件装配 ──────────────────────────────────────────────────────

    private static MinimapOutput.ShotEntry toShotEntry(ArtillerySalvo s, BattleWorld world) {
        var salvo = s.salvo();
        var shots = salvo.shots().stream()
            .map(sh -> new MinimapOutput.ShotDetail(sh.shotId(), sh.origin(), sh.pitch(), sh.speed(), sh.target(),
                sh.gunBarrelId(), sh.serverTimeLeft(), sh.shooterHeight(), sh.hitDistance()))
            .toList();
        return new MinimapOutput.ShotEntry(s.clock(),
            ReplayMapper.metaIdOf(world, s.avatarId()),
            ReplayMapper.metaIdOf(world, salvo.ownerId().value()),
            salvo.paramsId().value(), salvo.salvoId(), s.clock(), shots);
    }

    /** 命中事件：victim_id（接收 receiveShotKills 的实体）+ fired_at（关联齐射）+ victim_position（hit 到达瞬间快照）。 */
    private static MinimapOutput.ShotHitEntry toShotHitEntry(ShotHitRecord r,
                                                             java.util.Map<Long, Float> firedAtByShot,
                                                             BattleWorld world) {
        var hit = r.hit();
        int victimEid = r.avatarId().value();
        Float firedAt = firedAtByShot.get(((long) hit.ownerId().value() << 32) | (hit.shotId() & 0xFFFFFFFFL));
        var victimPos = r.victimPosition();
        if (victimPos == null) {
            var es = world.entities().get(victimEid);
            victimPos = es != null ? new Vec3(es.x, es.y, es.z) : null;
        }
        return new MinimapOutput.ShotHitEntry(r.clock(),
            ReplayMapper.metaIdOf(world, hit.ownerId().value()),
            ReplayMapper.metaIdOf(world, victimEid),
            hit.shotId(), hit.hitType().raw(), hit.position(), hit.terminalBallistics(), firedAt, victimPos);
    }

    // ── 帧快照（docs §4.2）────────────────────────────────────────────

    private static MinimapOutput.MinimapFrame snapshot(BattleWorld world, float clock) {
        var entities = new ArrayList<MinimapOutput.MinimapEntity>();
        for (var es : world.entities().values()) {
            // 只有收到过 minimap 更新（有归一化坐标）的实体才输出；玩家身份归一为 metaId
            if (Float.isNaN(es.minimapX) || Float.isNaN(es.minimapZ)) continue;
            float heading = Float.isNaN(es.minimapHeading) ? 0f : es.minimapHeading;
            int side = es.relation >= 0 ? es.relation : 2;
            entities.add(new MinimapOutput.MinimapEntity(ReplayMapper.metaIdOf(world, es.id.value()),
                es.minimapX, es.minimapZ, heading, es.visible, es.visibilityFlags, es.isInvisible,
                es.teamId, es.health, es.maxHealth, es.isAlive, side));
        }
        return frame(world, clock, entities);
    }

    /**
     * 帧补充数据（planes/torpedoes/smoke/buildings/wards/buffZones/weather/teamScores/capturePoints/timeLeft
     * 取自 {@code world}）。供单视角快照与 {@link MinimapMerger} 合并复用。
     *
     * @param entities 已装配好的船位（合并场景下为敌我并集）
     */
    public static MinimapOutput.MinimapFrame frame(BattleWorld world, float clock,
                                                   List<MinimapOutput.MinimapEntity> entities) {
        var planes = world.activePlanes().values().stream()
            .map(p -> new MinimapOutput.PlaneEntry(p.planeId(), ReplayMapper.metaIdOf(world, p.ownerEntityId()),
                p.teamId(), p.paramsId().value(), p.x(), p.z(), p.lastUpdateAt()))
            .toList();

        var torpedoes = world.activeTorpedoes().values().stream()
            .map(t -> {
                var d = t.data();
                return new MinimapOutput.TorpedoEntry(d.shotId(), ReplayMapper.metaIdOf(world, d.ownerId().value()),
                    d.paramsId().value(), d.salvoId(), d.origin(), d.direction(), d.armed(), t.clock(), t.clock(),
                    t.hasManeuver(), false);
            })
            .toList();

        var smoke = world.smokeScreens().values().stream()
            .map(e -> new MinimapOutput.SmokeEntry(e.id.value(), e.x, e.z, e.smokeRadius, e.smokePoints))
            .toList();

        var buildings = world.buildings().stream()
            .map(b -> new MinimapOutput.BuildingEntry(b.entityId(), b.x(), b.z(),
                b.teamId(), b.paramsId(), b.isAlive()))
            .toList();

        var wards = world.activeWards().values().stream()
            .map(w -> {
                var p = w.position();
                return new MinimapOutput.WardEntry(w.wardId(), w.entityId().value(),
                    ReplayMapper.metaIdOf(world, w.ownerId().value()),
                    p != null ? p.x() : 0f, p != null ? p.y() : 0f, p != null ? p.z() : 0f, w.radius());
            })
            .toList();

        var buffZones = world.buffZones().values().stream()
            .map(b -> new MinimapOutput.BuffZoneEntry(b.entityId(), b.x(), b.z(),
                b.radius(), b.teamId(), b.isActive(), b.dropParamsId()))
            .toList();

        var weather = world.weatherZones().stream()
            .map(w -> new MinimapOutput.WeatherZoneEntry(w.name(), w.x(), w.z(),
                w.radius(), w.paramsId(), w.entityId()))
            .toList();

        var teamScores = world.teamScores().stream()
            .map(t -> new MinimapOutput.TeamScoreEntry(t.teamIndex(), t.score()))
            .toList();

        var cps = world.capturePoints().stream()
            .map(cp -> new MinimapOutput.CapturePointEntry(cp.index, cp.teamId, cp.invaderTeam,
                cp.progress, cp.isEnabled,
                cp.position != null && cp.position.length >= 2 ? cp.position[0] : 0f,
                cp.position != null && cp.position.length >= 2 ? cp.position[1] : 0f))
            .toList();

        return new MinimapOutput.MinimapFrame(clock, entities, planes, torpedoes, smoke, buildings,
            wards, buffZones, weather, teamScores, cps, world.timeLeft());
    }
}
