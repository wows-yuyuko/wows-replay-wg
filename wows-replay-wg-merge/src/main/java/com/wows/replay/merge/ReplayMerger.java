package com.wows.replay.merge;

import com.wows.replay.ReplayFile;
import com.wows.replay.decode.PacketDecoder;
import com.wows.replay.ingest.*;
import com.wows.replay.ingest.report.BattleReportBuilder;
import com.wows.replay.packet.Packet;
import com.wows.replay.packet.Parser;
import com.wows.replay.spi.EntitySpecProvider;
import com.wows.replay.spi.GameConstantsProvider;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 同场次多视角回放的结果级合并去重器（docs/replay-parser-call-chain.md §10 的结果级部分）。
 *
 * <p>流程：每份 replay 各自走完整管线（{@link Parser} → {@link PacketDecoder} →
 * {@link BattleWorld} → {@link BattleReportBuilder}）解析成 {@link ParsedReplay}，再跨视角合并。
 * <b>广播状态直接取主视角</b>（各视角一致，主视角为权威，不做并集/去重）：玩家、击杀、
 * 队伍比分、控制点、buff 掉落区、天气区域。其余事件流（聊天/伤害/消耗品/齐射/鱼雷/命中/
 * 语音/沉船/已捕获 Buff/勋带/建筑）<b>跨视角并集 + 按事件身份去重</b>。</p>
 *
 * <p>去重键（文档化）：聊天=clock+sender+channel+message；伤害=aggressor+victim+clock+amount
 * （docs §10.5 gather_damage_events，+amount 防同视角并发误并）；消耗品=clock+entity+consumable；
 * 齐射=avatar+salvoId；鱼雷=owner+shotId；命中=shotId；语音=clock+sender+message；
 * 沉船=victimId（首条）；已捕获 Buff=clock+entity+capturedBy；勋带=clock+ribbonId；建筑=entityId。</p>
 *
 * <p>校验：所有回放必须同版本（{@link MergeException#versionMismatch}）且同竞技场
 * （{@link MergeException#arenaMismatch}），否则无法合并。</p>
 *
 * <p>流式合并（{@link MergedSession}）为后续预留，本类不涉及包级步进。</p>
 */
public final class ReplayMerger {

    private final EntitySpecProvider specProvider;
    private final GameConstantsProvider constants;

    /** @param specProvider 实体 schema（解析所需，见 {@link #parse}） */
    public ReplayMerger(EntitySpecProvider specProvider, GameConstantsProvider constants) {
        this.specProvider = specProvider;
        this.constants = constants;
    }

    /** 便捷工厂。 */
    public static ReplayMerger of(EntitySpecProvider specProvider, GameConstantsProvider constants) {
        return new ReplayMerger(specProvider, constants);
    }

    // ── 解析 ─────────────────────────────────────────────────────────────

    /**
     * 解析单份回放为 {@link ParsedReplay}（等价 {@code ReplayAnalyzer.buildBattleReport}）。
     *
     * @throws IllegalArgumentException 未注入 {@link EntitySpecProvider}
     */
    public ParsedReplay parse(ReplayFile replay) {
        if (replay == null) throw new IllegalArgumentException("replay 不能为 null");
        if (specProvider == null) {
            throw new IllegalArgumentException("解析需要 EntitySpecProvider（先经 ReplayMerger(specProvider, ...) 构造）");
        }
        var version = replay.version();
        var parser = new Parser(specProvider, version);
        var decoder = new PacketDecoder(version);
        var world = new BattleWorld(replay.meta(), version, constants);
        var iter = replay.packetIterator();
        while (iter.hasNext()) {
            var raw = iter.next();
            var packet = parser.parse(raw);
            if (packet == null || packet.payload() instanceof Packet.InvalidPayload) continue;
            if (packet.packetType() == null) continue;
            world.process(decoder.decode(packet), raw.clock());
        }
        world.finish();
        var report = new BattleReportBuilder(world, replay.meta()).build();
        return new ParsedReplay(replay, world, report);
    }

    // ── 合并入口 ─────────────────────────────────────────────────────────

    /** 便捷：解析主视角 + alt 视角并合并（主视角在前）。 */
    public MergedResult merge(ReplayFile primary, List<ReplayFile> alts) {
        var parsed = new ArrayList<ParsedReplay>(1 + (alts == null ? 0 : alts.size()));
        parsed.add(parse(primary));
        if (alts != null) {
            for (var a : alts) parsed.add(parse(a));
        }
        return merge(parsed);
    }

    /**
     * 核心：结果级合并。
     *
     * <p>{@code replays.get(0)} 为主视角：广播状态（玩家/击杀/比分/控制点/buff 区/天气区）与
     * 顶层元数据（胜负/结束方式/战报 JSON）直接取主视角；其余事件流跨视角并集 + 去重。</p>
     *
     * @param replays 同场次回放，非空；第 0 项为主视角
     * @throws MergeException 版本或竞技场不一致
     */
    public MergedResult merge(List<ParsedReplay> replays) {
        if (replays == null || replays.isEmpty()) {
            throw new IllegalArgumentException("至少需要一份回放");
        }
        var primary = replays.get(0);
        validateSameBattle(replays);

        var dedup = new LinkedHashMap<String, Integer>();

        // ── 广播状态：直接取主视角（各视角一致，主视角为权威）──
        // 玩家（广播名册，主视角已含全部）、击杀（广播事件）、队伍比分、控制点、
        // buff 掉落区、天气区域 —— 不做并集/去重。
        var players = primary.report().players();
        dedup.put("players", 0);
        var killLog = new ArrayList<>(primary.world().killLog());
        dedup.put("kills", 0);

        // ── 事件流（跨视角并集 + 去重）──
        var chatLog = new ArrayList<ChatEvent>();
        var chatSeen = new HashSet<String>();
        for (var r : replays) {
            for (var c : r.world().chatLog()) {
                if (chatSeen.add(c.clock() + "|" + c.metaId() + "|" + c.channel() + "|" + c.message())) chatLog.add(c);
            }
        }
        dedup.put("chat", total(replays, w -> w.chatLog().size()) - chatLog.size());

        var damageEvents = new ArrayList<DamageEvent>();
        var dmgSeen = new HashSet<String>();
        for (var r : replays) {
            for (var d : r.world().damageEvents()) {
                // 同一 (aggressor, victim, clock) 可能对应同一视角内的多发并发伤害（amount 不同），
                // 键必须含 amount，避免误并（docs §10.5 gather_damage_events 的 (aggr,victim,clock) + amount）。
                if (dmgSeen.add(d.clock() + "|" + d.aggressorId() + "|" + d.victimId() + "|" + d.amount())) damageEvents.add(d);
            }
        }
        dedup.put("damage", total(replays, w -> w.damageEvents().size()) - damageEvents.size());

        var consumableLog = new ArrayList<ConsumableEvent>();
        var consSeen = new HashSet<String>();
        for (var r : replays) {
            for (var c : r.world().consumableLog()) {
                if (consSeen.add(key3(c.clock(), c.entityId(), c.consumableId()))) consumableLog.add(c);
            }
        }
        dedup.put("consumables", total(replays, w -> w.consumableLog().size()) - consumableLog.size());

        var salvos = new ArrayList<ArtillerySalvo>();
        var salvoSeen = new HashSet<String>();
        for (var r : replays) {
            for (var s : r.world().firedSalvos()) {
                if (salvoSeen.add(key3(0, s.avatarId(), s.salvo().salvoId()))) salvos.add(s);
            }
        }
        dedup.put("salvos", total(replays, w -> w.firedSalvos().size()) - salvos.size());

        var torpedoes = new ArrayList<TorpedoRecord>();
        var torpSeen = new HashSet<String>();
        for (var r : replays) {
            for (var t : r.world().torpedoes()) {
                if (torpSeen.add(key2(t.data().ownerId().value(), t.data().shotId()))) torpedoes.add(t);
            }
        }
        dedup.put("torpedoes", total(replays, w -> w.torpedoes().size()) - torpedoes.size());

        var shotHits = new ArrayList<ShotHitRecord>();
        var hitSeen = new HashSet<String>();
        for (var r : replays) {
            for (var h : r.world().shotHits()) {
                if (hitSeen.add(String.valueOf(h.hit().shotId()))) shotHits.add(h);
            }
        }
        dedup.put("shotHits", total(replays, w -> w.shotHits().size()) - shotHits.size());

        var voiceLineLog = new ArrayList<VoiceLineEvent>();
        var vlSeen = new HashSet<String>();
        for (var r : replays) {
            for (var v : r.world().voiceLineLog()) {
                if (vlSeen.add(v.clock() + "|" + v.senderId().value() + "|" + v.message())) voiceLineLog.add(v);
            }
        }
        dedup.put("voiceLines", total(replays, w -> w.voiceLineLog().size()) - voiceLineLog.size());

        // 勋带：各视角各自记录（本人视角的勋带），跨视角按 (clock, ribbonId) 去重
        var ribbonLog = new ArrayList<RibbonEvent>();
        var rbSeen = new HashSet<String>();
        for (var r : replays) {
            for (var rb : r.world().ribbonLog()) {
                if (rbSeen.add(rb.clock() + "|" + rb.ribbonId())) ribbonLog.add(rb);
            }
        }
        dedup.put("ribbons", total(replays, w -> w.ribbonLog().size()) - ribbonLog.size());

        var deadShips = new ArrayList<DeadShipRecord>();
        var deadByVictim = new LinkedHashMap<Integer, DeadShipRecord>();
        for (var r : replays) {
            for (var d : r.world().deadShips()) {
                if (deadByVictim.putIfAbsent(d.victimId(), d) == null) deadShips.add(d);
            }
        }
        dedup.put("deadShips", total(replays, w -> w.deadShips().size()) - deadShips.size());

        // ── 广播状态：直接取主视角（队伍比分 / 控制点 / buff 掉落区 / 天气区域）──
        var teamScores = new ArrayList<>(primary.world().teamScores());
        dedup.put("teamScores", 0);

        var capturePoints = new ArrayList<>(primary.world().capturePoints());
        dedup.put("capturePoints", 0);

        var buffZones = new ArrayList<>(primary.world().buffZones().values());
        dedup.put("buffZones", 0);

        var capturedBuffs = new ArrayList<CapturedBuff>();
        var cbSeen = new HashSet<String>();
        for (var r : replays) {
            for (var c : r.world().capturedBuffs()) {
                if (cbSeen.add(key3(c.clock(), c.entityId(), c.capturedBy()))) capturedBuffs.add(c);
            }
        }
        dedup.put("capturedBuffs", total(replays, w -> w.capturedBuffs().size()) - capturedBuffs.size());

        var weatherZones = new ArrayList<>(primary.world().weatherZones());
        dedup.put("weatherZones", 0);

        var buildings = new ArrayList<BuildingState>();
        var bdById = new LinkedHashMap<Integer, BuildingState>();
        for (var r : replays) for (var b : r.world().buildings()) bdById.putIfAbsent(b.entityId(), b);
        buildings.addAll(bdById.values());
        dedup.put("buildings", total(replays, w -> w.buildings().size()) - buildings.size());

        // ── 排序（时间线确定性：clock 升序；状态集按索引/队伍序）──
        killLog.sort(Comparator.comparingDouble(KillRecord::clock));
        chatLog.sort(Comparator.comparingDouble(ChatEvent::clock));
        damageEvents.sort(Comparator.comparingDouble(DamageEvent::clock));
        consumableLog.sort(Comparator.comparingDouble(ConsumableEvent::clock));
        salvos.sort(Comparator.comparingDouble(ArtillerySalvo::clock));
        torpedoes.sort(Comparator.comparingDouble(TorpedoRecord::clock));
        shotHits.sort(Comparator.comparingDouble(ShotHitRecord::clock));
        voiceLineLog.sort(Comparator.comparingDouble(VoiceLineEvent::clock));
        ribbonLog.sort(Comparator.comparingDouble(RibbonEvent::clock));
        deadShips.sort(Comparator.comparingDouble(DeadShipRecord::clock));
        teamScores.sort(Comparator.comparingInt(TeamScore::teamIndex));
        capturePoints.sort(Comparator.comparingInt(c -> c.index));

        var primaryReport = primary.report();
        var primaryWorld = primary.world();
        return new MergedResult(
            replays.size(),
            primaryReport.arenaId(),
            primaryReport.version(),
            primaryReport.mapName(),
            primaryReport.gameMode(),
            primaryReport.gameType(),
            primaryReport.matchGroup(),
            primaryReport.matchResult(),
            primaryReport.finishType(),
            primaryWorld.winningTeam(),
            primaryReport.battleStartClock(),
            primaryWorld.battleResultClock(),
            primaryWorld.battleEndClock(),
            new ArrayList<>(players),
            killLog, chatLog, damageEvents, consumableLog,
            salvos, torpedoes, shotHits, voiceLineLog, ribbonLog, deadShips,
            teamScores, capturePoints, buffZones, capturedBuffs, weatherZones, buildings,
            primaryReport.battleResults(),
            dedup);
    }

    // ── 校验 ─────────────────────────────────────────────────────────────

    /** 同场次校验：版本一致 + 竞技场一致（双方非 0 才比较）。 */
    private static void validateSameBattle(List<ParsedReplay> replays) {
        var primary = replays.get(0);
        for (int i = 1; i < replays.size(); i++) {
            var r = replays.get(i);
            var pv = primary.report().version();
            var rv = r.report().version();
            if (pv != null && rv != null && !pv.equals(rv)) {
                throw MergeException.versionMismatch(pv.toString(), rv.toString());
            }
            long pa = primary.report().arenaId();
            long ra = r.report().arenaId();
            if (pa != ra && pa != 0 && ra != 0) {
                throw MergeException.arenaMismatch(String.valueOf(pa), String.valueOf(ra));
            }
        }
    }

    // ── 统计 / 去重键辅助 ────────────────────────────────────────────────

    private static int total(List<ParsedReplay> replays, java.util.function.ToIntFunction<BattleWorld> fn) {
        int n = 0;
        for (var r : replays) n += fn.applyAsInt(r.world());
        return n;
    }

    private static String key2(long a, long b) {
        return a + "|" + b;
    }

    private static String key3(float a, long b, long c) {
        return a + "|" + b + "|" + c;
    }
}
