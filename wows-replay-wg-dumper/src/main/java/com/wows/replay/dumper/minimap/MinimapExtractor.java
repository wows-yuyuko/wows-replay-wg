package com.wows.replay.dumper.minimap;

import com.wows.replay.ReplayFile;
import com.wows.replay.decode.PacketDecoder;
import com.wows.replay.ingest.BattleWorld;
import com.wows.replay.packet.NamedArgs;
import com.wows.replay.packet.Parser;
import com.wows.replay.spi.EntitySpecProvider;
import com.wows.replay.spi.GameConstantsProvider;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

/**
 * Minimap 数据提取器（对标 Rust {@code replay-dumper::position::extract_minimap_data}，
 * docs/replay-dumper-minimap.md §4）。
 *
 * <p><b>Single 模式</b>：仅主回放驱动一个 {@link BattleWorld}，逐时钟边界冲刷事件流
 * （damage 条数 diff / 齐射去重 / shot_hits diff），每 {@code step} 个时钟边界抽一帧快照，
 * 循环结束后装配终局状态。多视角合并（Full/Fast）暂不实现。</p>
 *
 * <p>坐标解码复用 {@link PacketDecoder#decodeMinimapVision(NamedArgs)}（packedData 位布局见
 * docs §3），位置直接读 {@link EntityState} 的 minimap 状态。</p>
 */
public final class MinimapExtractor {

    private final EntitySpecProvider specProvider;
    private final GameConstantsProvider constants;
    private final ReplayFile replay;
    private final int step;

    public MinimapExtractor(EntitySpecProvider specProvider, ReplayFile replay, int step) {
        this(specProvider, null, replay, step);
    }

    public MinimapExtractor(EntitySpecProvider specProvider, GameConstantsProvider constants,
                            ReplayFile replay, int step) {
        this.specProvider = specProvider;
        this.constants = constants;
        this.replay = replay;
        this.step = Math.max(1, step);
    }

    /** 提取 minimap 数据（Single 模式）。 */
    public MinimapOutput extract() {
        var world = new BattleWorld(replay.meta(), replay.version(), constants);
        var parser = new Parser(specProvider, replay.version());
        var decoder = new PacketDecoder(replay.version());

        var frames = new ArrayList<MinimapOutput.MinimapFrame>();
        var firingEvents = new ArrayList<MinimapOutput.ShotEntry>();
        var damageEvents = new ArrayList<MinimapOutput.DamageEntry>();
        var shotHits = new ArrayList<MinimapOutput.ShotHitEntry>();
        var seenSalvos = new HashSet<Long>();
        int lastDamageCount = 0;
        int lastHitCount = 0;
        float lastClock = Float.NaN;
        int tick = 0;

        var iter = replay.packetIterator();
        while (iter.hasNext()) {
            var raw = iter.next();
            var packet = parser.parse(raw);
            if (packet == null || packet.payload() instanceof com.wows.replay.packet.Packet.InvalidPayload) continue;
            if (packet.packetType() == null) continue;
            world.process(decoder.decode(packet), raw.clock());

            // 时钟边界：冲刷事件流 + step 抽帧（docs §4.1）
            float clock = world.currentClock().seconds();
            if (clock != lastClock) {
                // 1. damage：damageEvents 条数 diff，只推新事件
                var dmg = world.damageEvents();
                for (int i = lastDamageCount; i < dmg.size(); i++) {
                    var d = dmg.get(i);
                    damageEvents.add(new MinimapOutput.DamageEntry(d.clock(), d.aggressorId(), d.victimId(), d.amount()));
                }
                lastDamageCount = dmg.size();

                // 2. firing：(avatar_id, salvo_id) 去重，新齐射才输出
                for (var s : world.firedSalvos()) {
                    long key = ((long) s.avatarId() << 32) | (s.salvo().salvoId() & 0xFFFFFFFFL);
                    if (seenSalvos.add(key)) firingEvents.add(toShotEntry(s));
                }

                // 2b. shot_id → fired_at 映射（shot_hits 关联起源齐射；用 (owner,shot) 组合 key
                //     避免不同齐射 shot_id 复用导致的覆盖误配）
                var firedAtByShot = new java.util.HashMap<Long, Float>();
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

                // 4. 帧快照（抽稀）
                if (tick % step == 0) {
                    frames.add(snapshot(world, clock));
                }
                tick++;
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

        return new MinimapOutput(
            arenaId, frames, firingEvents, damageEvents, shotHits,
            world.deadShips().stream()
                .map(ds -> new MinimapOutput.DeadShip(ds.clock(), ds.victimId(), ds.x(), ds.z()))
                .toList(),
            battleStageName(world.battleStageId()), world.winningTeam(), finishType,
            new MinimapOutput.ScoringRules(world.teamWinScore(), world.holdReward(),
                world.holdPeriod(), world.holdCpIndices()),
            world.capturedBuffs().stream()
                .map(cb -> new MinimapOutput.CapturedBuff(cb.entityId(), cb.paramsId(), cb.capturedBy(), cb.clock()))
                .toList());
    }

    /** 战斗阶段 id → 阶段名（对齐 Rust BattleStage Debug，0=Waiting..4=Ended）。 */
    private static String battleStageName(Integer id) {
        if (id == null) return null;
        return switch (id) {
            case 0 -> "Waiting";
            case 1 -> "Battle";
            case 2 -> "Results";
            case 3 -> "Finishing";
            case 4 -> "Ended";
            default -> "Stage(" + id + ")";
        };
    }

    // ── 事件装配 ──────────────────────────────────────────────────────

    private static MinimapOutput.ShotEntry toShotEntry(BattleWorld.ArtillerySalvo s) {
        var salvo = s.salvo();
        var shots = salvo.shots().stream()
            .map(sh -> new MinimapOutput.ShotDetail(sh.shotId(), sh.origin(), sh.pitch(), sh.speed(), sh.target(),
                sh.gunBarrelId(), sh.serverTimeLeft(), sh.shooterHeight(), sh.hitDistance()))
            .toList();
        return new MinimapOutput.ShotEntry(s.clock(), s.avatarId(), salvo.ownerId().value(),
            salvo.paramsId().value(), salvo.salvoId(), s.clock(), shots);
    }

    /** 命中事件：victim_id（接收 receiveShotKills 的实体）+ fired_at（关联齐射）+ victim_position（hit 到达瞬间快照）。 */
    private static MinimapOutput.ShotHitEntry toShotHitEntry(BattleWorld.ShotHitRecord r,
                                                             java.util.Map<Long, Float> firedAtByShot,
                                                             BattleWorld world) {
        var hit = r.hit();
        int victimId = r.avatarId().value();
        Float firedAt = firedAtByShot.get(((long) hit.ownerId().value() << 32) | (hit.shotId() & 0xFFFFFFFFL));
        var victimPos = r.victimPosition();
        if (victimPos == null) {
            var es = world.entities().get(victimId);
            victimPos = es != null ? new com.wows.replay.model.Vec3(es.x, es.y, es.z) : null;
        }
        return new MinimapOutput.ShotHitEntry(r.clock(), hit.ownerId().value(), victimId, hit.shotId(),
            hit.hitType().raw(), hit.position(), hit.terminalBallistics(), firedAt, victimPos);
    }

    // ── 帧快照（docs §4.2）────────────────────────────────────────────

    private static MinimapOutput.MinimapFrame snapshot(BattleWorld world, float clock) {
        var entities = new ArrayList<MinimapOutput.MinimapEntity>();
        for (var es : world.entities().values()) {
            // 只有收到过 minimap 更新（有归一化坐标）的实体才输出
            if (Float.isNaN(es.minimapX) || Float.isNaN(es.minimapZ)) continue;
            float heading = Float.isNaN(es.minimapHeading) ? 0f : es.minimapHeading;
            entities.add(new MinimapOutput.MinimapEntity(es.id.value(), es.minimapX, es.minimapZ,
                heading, es.visible, es.teamId, es.health, es.maxHealth, es.isAlive));
        }

        var planes = world.activePlanes().values().stream()
            .map(p -> new MinimapOutput.PlaneEntry(p.planeId(), p.ownerEntityId(), p.teamId(),
                p.paramsId().value(), p.x(), p.z()))
            .toList();

        var torpedoes = world.activeTorpedoes().values().stream()
            .map(t -> {
                var d = t.data();
                return new MinimapOutput.TorpedoEntry(d.shotId(), d.ownerId().value(), d.paramsId().value(),
                    d.salvoId(), d.origin(), d.direction(), d.armed(), t.clock(), t.clock(),
                    t.hasManeuver(), false);
            })
            .toList();

        var smoke = world.smokeScreens().values().stream()
            .map(e -> new MinimapOutput.SmokeEntry(e.id.value(), e.x, e.z, e.smokeRadius))
            .toList();

        var buildings = world.buildings().stream()
            .map(b -> new MinimapOutput.BuildingEntry(b.entityId(), b.x(), b.z(),
                b.teamId(), b.paramsId(), b.isAlive()))
            .toList();

        var wards = world.activeWards().values().stream()
            .map(w -> {
                var p = w.position();
                return new MinimapOutput.WardEntry(w.wardId(), w.entityId().value(), w.ownerId().value(),
                    p != null ? p.x() : 0f, p != null ? p.y() : 0f, p != null ? p.z() : 0f, w.radius());
            })
            .toList();

        var buffZones = world.buffZones().values().stream()
            .map(b -> new MinimapOutput.BuffZoneEntry(b.entityId(), b.x(), b.z(),
                b.radius(), b.teamId(), b.isActive()))
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
