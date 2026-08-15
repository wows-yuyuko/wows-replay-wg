package com.shinoaki.wowsreplay.dumper.minimap;

import com.shinoaki.wowsreplay.core.ReplayFile;
import com.shinoaki.wowsreplay.core.constant.GameConstants;
import com.shinoaki.wowsreplay.core.decode.PacketDecoder;
import com.shinoaki.wowsreplay.core.model.Version;
import com.shinoaki.wowsreplay.core.packet.Packet;
import com.shinoaki.wowsreplay.core.packet.Parser;
import com.shinoaki.wowsreplay.core.packet.RawPacket;
import com.shinoaki.wowsreplay.core.spi.EntitySpecProvider;
import com.shinoaki.wowsreplay.core.spi.GameConstantsProvider;
import com.shinoaki.wowsreplay.ingest.ArtillerySalvo;
import com.shinoaki.wowsreplay.ingest.BattleWorld;
import com.shinoaki.wowsreplay.ingest.DropEvent;
import com.shinoaki.wowsreplay.ingest.ShotHitRecord;
import com.shinoaki.wowsreplay.ingest.mapped.NormalizedReplay;
import com.shinoaki.wowsreplay.ingest.mapped.ReplayMapper;
import com.shinoaki.wowsreplay.merge.ParsedReplay;
import com.shinoaki.wowsreplay.merge.ReplayMerger;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 双回放 minimap 移动数据合并（docs/id-normalization-and-multiview-merge.md）。
 *
 * <p><b>移动坐标（frames）只用主/副两份 replay</b>：主 replay（本方）+ 敌对玩家副 replay
 * （其本方 = 主方敌方），每份只提取<b>本方</b>移动坐标（去掉敌方），按 safe_clock 锁步
 * 合并成完整双方移动数据。其余流（firing/damage/shot_hits/dead_ships/captured_buffs）由
 * <b>全部 replay 去重</b>提取（复用 {@link ReplayMerger#mergeNormalized}）。输出形状与单
 * replay 的 {@link MinimapOutput} 一致（数据更全面）。</p>
 *
 * <p><b>敌我分类</b>：两视角的录制者 relation 都是 0，故用额外字段 {@code side} 以
 * <b>主视角</b>为基准描述敌我：0=主视角自己, 1=主视角友军, 2=敌方。分类表优先从主 replay
 * 提取（metaId → side），副 replay 的实体按此表划分（查不到再回退其自身 relation）。
 * 合并后的帧里，主视角自己的船 side=0、主方友军 side=1、敌方 side=2，避免歧义。</p>
 *
 * <p><b>主/副选择与 battle_result</b>：主 replay 为候选第一个，缺失 battle_result（0x22
 * 战报 JSON）时在候选里找有战报的补位；副 replay 为敌对队伍、优先有战报的。若全部候选都
 * 无 battle_result 或无法配对敌对副 replay，仅输出警告（数据不完整），不中断。</p>
 */
@Slf4j
public final class MinimapMerger {

    private final EntitySpecProvider specProvider;
    private final GameConstantsProvider constants;
    private final List<ReplayFile> replays;

    /**
     * @param replays 同场次候选回放（第 0 项默认主视角；须含敌对队伍成员才能凑齐双方移动）
     */
    public MinimapMerger(EntitySpecProvider specProvider, GameConstantsProvider constants,
                         List<ReplayFile> replays) {
        this.specProvider = specProvider;
        this.constants = constants;
        this.replays = replays;
    }

    /** 主/副两份回放的合并 minimap 输出（形状与单 replay 一致）。 */
    public MinimapOutput merge() {
        var merger = new ReplayMerger(specProvider, constants);
        var parsed = new ArrayList<ParsedReplay>(replays.size());
        for (var r : replays) parsed.add(merger.parse(r));
        if (parsed.isEmpty()) throw new IllegalArgumentException("至少需要一份回放");

        var selection = selectPrimarySecondary(parsed);
        var primary = selection.primary();
        var secondary = selection.secondary();
        int primaryTeam = primary.report().selfPlayer() != null ? primary.report().selfPlayer().teamId() : -1;
        var sideById = buildSideTable(primary, primaryTeam);

        // 移动坐标：主/副锁步推进，各提本方，合并
        var frames = mergeFrames(primary, secondary, sideById, primaryTeam);

        // 其余流：全部用户去重（复用 ReplayMerger.mergeNormalized）
        var normalized = new ArrayList<NormalizedReplay>(parsed.size());
        for (var p : parsed) normalized.add(ReplayMapper.map(p.world(), p.report()));
        var mm = merger.mergeNormalized(normalized).replay();

        var firedAtByShot = firedAtByShot(mm);
        var firing = toShotEntries(mm.firedSalvos(), primary.world());
        var damage = mm.damageEvents().stream()
            .map(d -> new MinimapOutput.DamageEntry(d.clock(), d.aggressorMetaId(), d.victimMetaId(), d.amount()))
            .toList();
        var shotHits = toShotHits(mm.shotHits(), primary.world(), firedAtByShot);
        var deadShips = mm.deadShips().stream()
            .map(d -> new MinimapOutput.DeadShip(d.clock(), d.victimMetaId(), d.x(), d.z()))
            .toList();
        var capturedBuffs = mm.capturedBuffs().stream()
            .map(cb -> new MinimapOutput.CapturedBuff(cb.paramsId(), cb.teamId(), cb.clock()))
            .toList();

        return new MinimapOutput(
            parseArenaId(primary.world()), frames, firing, damage, shotHits, deadShips,
            battleStageName(primary.world().battleStageId(), primary.replay().version()),
            primary.world().winningTeam(), finishType(primary.world()),
            scoringRules(primary.world()), capturedBuffs,
            primary.world().dropEvents().stream()
                .map(d -> {
                    var pos = primary.world().dropZonePositions().get(d.zoneId());
                    return new MinimapOutput.DropEventEntry(d.id(), d.zoneId(), d.paramsId(), d.startTime(),
                        pos != null ? pos[0] : null, pos != null ? pos[1] : null, d.clock());
                })
                .toList());
    }

    // ── 主/副选择 + battle_result 检测 ───────────────────────────────────

    private record Selection(ParsedReplay primary, ParsedReplay secondary) {}

    private static Selection selectPrimarySecondary(List<ParsedReplay> parsed) {
        ParsedReplay primary = parsed.get(0);
        if (primary.report().battleResults() == null) {
            var withResults = parsed.stream().filter(p -> p.report().battleResults() != null).findFirst().orElse(null);
            if (withResults != null) {
                log.warn("主 replay 缺少 battle_result 节点，改用候选 {}", withResults.replay().meta().playerName());
                primary = withResults;
            } else {
                log.warn("数据不完整：候选 replay 均无 battle_result 节点（继续处理）");
            }
        }
        int primaryTeam = primary.report().selfPlayer() != null ? primary.report().selfPlayer().teamId() : -1;
        ParsedReplay chosenPrimary = primary;

        ParsedReplay secondary = parsed.stream()
            .filter(p -> p != chosenPrimary)
            .filter(p -> p.report().selfPlayer() != null && p.report().selfPlayer().teamId() != primaryTeam)
            .filter(p -> p.report().battleResults() != null)
            .findFirst().orElse(null);
        if (secondary == null) {
            secondary = parsed.stream()
                .filter(p -> p != chosenPrimary)
                .filter(p -> p.report().selfPlayer() != null && p.report().selfPlayer().teamId() != primaryTeam)
                .findFirst().orElse(null);
            if (secondary == null) {
                log.warn("无法配对敌对队伍的副 replay（候选均为主视角队伍，移动数据只有本方）");
            } else {
                log.warn("副 replay 缺少 battle_result 节点（无其他带战报的敌对候选）");
            }
        }
        return new Selection(primary, secondary);
    }

    // ── 敌我分类（以主视角为基准，避免两视角主视角都是 0）──────────────────

    /** metaId → side：0=主视角自己, 1=主视角友军, 2=敌方。优先从主 replay 提取。 */
    private static Map<Long, Integer> buildSideTable(ParsedReplay primary, int primaryTeam) {
        var side = new HashMap<Long, Integer>();
        long selfMetaId = primary.report().selfPlayer() != null ? primary.report().selfPlayer().metaId() : -1;
        for (var p : primary.report().players()) {
            int s = p.metaId() == selfMetaId ? 0 : (p.teamId() == primaryTeam ? 1 : 2);
            side.put(p.metaId(), s);
        }
        return side;
    }

    // ── 移动坐标 frames：主/副锁步 + 各提本方 ──────────────────────────────

    /**
     * 主 replay 只保留 side∈{0,1}（本方含自己）的船位；副 replay 只保留 side==2（其本方 = 主方敌方）。
     * 双流按 safe_clock 锁步推进，每时钟边界抽一帧并集（全量帧）。
     */
    private List<MinimapOutput.MinimapFrame> mergeFrames(ParsedReplay primary, ParsedReplay secondary,
                                                         Map<Long, Integer> sideById, int primaryTeam) {
        var frames = new ArrayList<MinimapOutput.MinimapFrame>();
        var worldP = new BattleWorld(primary.replay().meta(), primary.replay().version(), constants);
        var parserP = new Parser(specProvider, primary.replay().version());
        var decoderP = new PacketDecoder(primary.replay().version());
        var iterP = primary.replay().packetIterator();
        RawPacket nextP = iterP.hasNext() ? iterP.next() : null;

        BattleWorld worldS = null;
        Parser parserS = null;
        PacketDecoder decoderS = null;
        java.util.Iterator<RawPacket> iterS = null;
        RawPacket nextS = null;
        if (secondary != null) {
            worldS = new BattleWorld(secondary.replay().meta(), secondary.replay().version(), constants);
            parserS = new Parser(specProvider, secondary.replay().version());
            decoderS = new PacketDecoder(secondary.replay().version());
            iterS = secondary.replay().packetIterator();
            nextS = iterS.hasNext() ? iterS.next() : null;
        }

        float lastClock = Float.NaN;
        while (nextP != null || nextS != null) {
            float safe = Float.MAX_VALUE;
            if (nextP != null) safe = Math.min(safe, nextP.clock().seconds());
            if (nextS != null) safe = Math.min(safe, nextS.clock().seconds());

            while (nextP != null && nextP.clock().seconds() <= safe) {
                feed(worldP, parserP, decoderP, nextP);
                nextP = iterP.hasNext() ? iterP.next() : null;
            }
            while (nextS != null && nextS.clock().seconds() <= safe) {
                feed(worldS, parserS, decoderS, nextS);
                nextS = iterS.hasNext() ? iterS.next() : null;
            }

            // 时钟边界：每边界抽一帧（全量帧）
            if (safe != lastClock) {
                var entities = new ArrayList<MinimapOutput.MinimapEntity>();
                snapshotSide(worldP, sideById, true, entities);
                if (worldS != null) snapshotSide(worldS, sideById, false, entities);
                frames.add(MinimapExtractor.frame(worldP, safe, entities));
                lastClock = safe;
            }
        }
        return frames;
    }

    private static void feed(BattleWorld world, Parser parser, PacketDecoder decoder, RawPacket raw) {
        var packet = parser.parse(raw);
        if (packet == null || packet.payload() instanceof Packet.InvalidPayload) return;
        if (packet.packetType() == null) return;
        world.process(decoder.decode(packet), raw.clock());
    }

    /** 按 side 过滤：primarySide=true 保留 0/1（本方），false 保留 2（敌方）。 */
    private static void snapshotSide(BattleWorld world, Map<Long, Integer> sideById,
                                     boolean primarySide, List<MinimapOutput.MinimapEntity> out) {
        for (var es : world.entities().values()) {
            if (Float.isNaN(es.minimapX) || Float.isNaN(es.minimapZ)) continue;
            long metaId = ReplayMapper.metaIdOf(world, es.id.value());
            int side = sideById.getOrDefault(metaId, 2);
            boolean keep = primarySide ? (side == 0 || side == 1) : (side == 2);
            if (!keep) continue;
            float heading = Float.isNaN(es.minimapHeading) ? 0f : es.minimapHeading;
            out.add(new MinimapOutput.MinimapEntity(metaId, es.minimapX, es.minimapZ,
                heading, es.visible, es.visibilityFlags, es.isInvisible,
                es.teamId, es.health, es.maxHealth, es.isAlive, side));
        }
    }

    // ── 其余流（全量用户去重）→ MinimapOutput 记录 ─────────────────────────

    private static Map<Long, Float> firedAtByShot(NormalizedReplay mm) {
        var map = new HashMap<Long, Float>();
        for (var s : mm.firedSalvos()) {
            int owner = s.salvo().ownerId().value();
            for (var sh : s.salvo().shots()) {
                map.put(((long) owner << 32) | (sh.shotId() & 0xFFFFFFFFL), s.clock());
            }
        }
        return map;
    }

    private static List<MinimapOutput.ShotEntry> toShotEntries(List<ArtillerySalvo> salvos, BattleWorld world) {
        return salvos.stream()
            .map(s -> {
                var salvo = s.salvo();
                var shots = salvo.shots().stream()
                    .map(sh -> new MinimapOutput.ShotDetail(sh.shotId(), sh.origin(), sh.pitch(), sh.speed(), sh.target(),
                        sh.gunBarrelId(), sh.serverTimeLeft(), sh.shooterHeight(), sh.hitDistance()))
                    .toList();
                return new MinimapOutput.ShotEntry(s.clock(),
                    ReplayMapper.metaIdOf(world, s.avatarId()),
                    ReplayMapper.metaIdOf(world, salvo.ownerId().value()),
                    salvo.paramsId().value(), salvo.salvoId(), s.clock(), shots);
            })
            .toList();
    }

    private static List<MinimapOutput.ShotHitEntry> toShotHits(List<ShotHitRecord> hits, BattleWorld world,
                                                               Map<Long, Float> firedAtByShot) {
        return hits.stream()
            .map(r -> {
                var hit = r.hit();
                Float firedAt = firedAtByShot.get(((long) hit.ownerId().value() << 32) | (hit.shotId() & 0xFFFFFFFFL));
                return new MinimapOutput.ShotHitEntry(r.clock(),
                    ReplayMapper.metaIdOf(world, hit.ownerId().value()),
                    ReplayMapper.metaIdOf(world, r.avatarId().value()),
                    hit.shotId(), hit.hitType().raw(), hit.position(), hit.terminalBallistics(),
                    firedAt, r.victimPosition());
            })
            .toList();
    }

    // ── 终局状态（主视角）────────────────────────────────────────────────

    private String battleStageName(int id, Version version) {
        return new GameConstants(constants).battleStageName(id, version);
    }

    private static String finishType(BattleWorld world) {
        return world.finishType() != null ? world.finishType()
            : (world.finishTypeId() != 0 ? String.valueOf(world.finishTypeId()) : null);
    }

    private static MinimapOutput.ScoringRules scoringRules(BattleWorld world) {
        return new MinimapOutput.ScoringRules(world.teamWinScore(), world.holdReward(),
            world.holdPeriod(), world.holdCpIndices());
    }

    private static Long parseArenaId(BattleWorld world) {
        if (world.arenaId() == null) return null;
        try {
            return Long.parseLong(world.arenaId());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
