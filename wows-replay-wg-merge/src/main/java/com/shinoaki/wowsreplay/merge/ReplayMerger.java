package com.shinoaki.wowsreplay.merge;

import com.shinoaki.wowsreplay.core.JsonConstantsProvider;
import com.shinoaki.wowsreplay.core.JsonMapper;
import com.shinoaki.wowsreplay.core.ReplayFile;
import com.shinoaki.wowsreplay.core.data.BattleResultsResolver;
import com.shinoaki.wowsreplay.core.decode.PacketDecoder;
import com.shinoaki.wowsreplay.ingest.*;
import com.shinoaki.wowsreplay.ingest.mapped.*;
import com.wows.replay.ingest.*;
import com.wows.replay.ingest.mapped.*;
import com.shinoaki.wowsreplay.ingest.report.BattleReportBuilder;
import com.shinoaki.wowsreplay.core.packet.Packet;
import com.shinoaki.wowsreplay.core.packet.Parser;
import com.shinoaki.wowsreplay.core.spi.EntitySpecProvider;
import com.shinoaki.wowsreplay.core.spi.GameConstantsProvider;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.JsonNode;

import java.util.*;

/**
 * 同场次多视角回放的结果级合并去重器（docs/replay-parser-call-chain.md §10 的结果级部分）。
 *
 * <p>流程：每份 replay 各自走完整管线（{@link Parser} → {@link PacketDecoder} →
 * {@link BattleWorld} → {@link BattleReportBuilder}）解析成 {@link ParsedReplay}，
 * 再经映射层 {@link ReplayMapper} 归一为 {@link NormalizedReplay}（实体 id → 全局一致的
 * {@code metaId}），最后跨视角合并。</p>
 *
 * <p><b>合并策略</b>：<b>广播状态直接取主视角</b>（各视角一致，主视角为权威，不做并集/去重）：
 * 玩家、击杀、队伍比分、控制点、buff 掉落区、天气区域。其余事件流（聊天/伤害/消耗品/沉船/
 * 语音/勋带/齐射/鱼雷/命中/已捕获 Buff）<b>跨视角并集 + 按事件身份去重</b>。</p>
 *
 * <p><b>去重键（全部基于全局一致 id）</b>：击杀=victimMetaId；聊天=clock+metaId+channel+message；
 * 伤害=aggressorMetaId+victimMetaId+clock+amount；消耗品=clock+metaId+consumableId；
 * 沉船=victimMetaId（首条）；语音=clock+senderId+message；勋带=clock+ribbonId；
 * 齐射=salvoId；鱼雷=shotId；命中=shotId；已捕获 Buff=clock+entityId+capturedBy。</p>
 *
 * <p>校验：所有回放必须同版本（{@link MergeException#versionMismatch}）且同竞技场
 * （{@link MergeException#arenaMismatch}），否则无法合并。</p>
 *
 * <p>流式合并（{@link MergedSession}）为后续预留；本类的归一与去重逻辑复用于流式收尾。</p>
 */
@Slf4j
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
     * 核心入口：解析级合并。先把各 {@link ParsedReplay} 经映射层归一，再调用
     * {@link #mergeNormalized} 做合并去重，并汇总各回放的私有战报（playersPrivateInfo）。
     *
     * @param replays 同场次回放，非空；第 0 项为主视角
     * @throws MergeException 版本或竞技场不一致
     */
    public MergedResult merge(List<ParsedReplay> replays) {
        if (replays == null || replays.isEmpty()) {
            throw new IllegalArgumentException("至少需要一份回放");
        }
        // 映射层：每个 ParsedReplay → NormalizedReplay（实体 id → 全局一致 metaId）
        var views = new ArrayList<NormalizedReplay>(replays.size());
        for (var p : replays) views.add(ReplayMapper.map(p.world(), p.report()));
        var base = mergeNormalized(views);

        // 私有战报汇总：每个回放 battle_result.playersPrivateInfo/privateDataList 按 db_id 并集
        // （每个玩家的私有数据在其自身回放里最完整）。
        var privateInfo = new LinkedHashMap<String, JsonNode>();
        var constantsRoot = constantsRoot();
        for (var p : replays) {
            String raw = p.report().battleResults();
            if (raw == null) continue;
            try {
                JsonNode resolved = BattleResultsResolver.resolve(JsonMapper.readTree(raw), constantsRoot);
                long selfDbId = p.report().selfPlayer() != null ? p.report().selfPlayer().dbId() : 0;
                privateInfo.putAll(BattleResultsResolver.resolvePrivatePlayers(resolved, constantsRoot, selfDbId));
            } catch (Exception e) {
                log.warn("battle_results 私有数据解析失败: {}", e.toString());
            }
        }
        if (privateInfo.isEmpty()) return base;
        return new MergedResult(base.replayCount(), base.replay(), privateInfo, base.dedupStats());
    }

    /** constants.json 根节点（JsonConstantsProvider 时可用，用于私有战报扁平数组解析）。 */
    private JsonNode constantsRoot() {
        if (constants instanceof JsonConstantsProvider jcp) return jcp.root();
        return null;
    }

    /**
     * 对已归一（映射层 {@link ReplayMapper}）的多视角数据做结果级合并 + 去重。
     *
     * <p>{@code views.get(0)} 为主视角：广播状态（玩家/击杀/比分/控制点/buff 区/天气区）与
     * 顶层元数据（胜负/结束方式/战报 JSON）直接取主视角；其余事件流跨视角并集 + 去重。
     * 流式合并（{@link MergedSession}）收尾同样复用本入口。</p>
     *
     * @throws MergeException 版本或竞技场不一致（同场次校验）
     */
    public MergedResult mergeNormalized(List<NormalizedReplay> views) {
        if (views == null || views.isEmpty()) {
            throw new IllegalArgumentException("至少需要一份已归一回放");
        }
        var primary = views.get(0);
        validateSameBattle(views);

        var dedup = new LinkedHashMap<String, Integer>();

        // ── 广播状态：直接取主视角（各视角一致，主视角为权威）──
        // 玩家（名册）、击杀（广播）、队伍比分、控制点、buff 掉落区、天气区域
        var players = primary.players();
        dedup.put("players", 0);
        var killLog = new ArrayList<>(primary.killLog());
        dedup.put("kills", 0);

        // ── 事件流（跨视角并集 + 去重，键全为全局一致 id）──
        var chatLog = new ArrayList<NormalizedChat>();
        var chatSeen = new HashSet<String>();
        for (var v : views) {
            for (var c : v.chatLog()) {
                if (chatSeen.add(c.clock() + "|" + c.metaId() + "|" + c.channel() + "|" + c.message())) chatLog.add(c);
            }
        }
        dedup.put("chat", totalChat(views) - chatLog.size());

        var damageEvents = new ArrayList<NormalizedDamage>();
        var dmgSeen = new HashSet<String>();
        for (var v : views) {
            for (var d : v.damageEvents()) {
                // 同一 (aggr,victim,clock) 可能对应同一视角内的多发并发伤害（amount 不同），
                // 键必须含 amount，避免误并。
                if (dmgSeen.add(d.clock() + "|" + d.aggressorMetaId() + "|" + d.victimMetaId() + "|" + d.amount())) {
                    damageEvents.add(d);
                }
            }
        }
        dedup.put("damage", totalDamage(views) - damageEvents.size());

        var consumableLog = new ArrayList<NormalizedConsumable>();
        var consSeen = new HashSet<String>();
        for (var v : views) {
            for (var c : v.consumableLog()) {
                if (consSeen.add(c.clock() + "|" + c.metaId() + "|" + c.consumableId())) consumableLog.add(c);
            }
        }
        dedup.put("consumables", totalConsumables(views) - consumableLog.size());

        var deadShips = new ArrayList<NormalizedDeadShip>();
        var deadByVictim = new LinkedHashMap<Long, NormalizedDeadShip>();
        for (var v : views) {
            for (var d : v.deadShips()) {
                if (deadByVictim.putIfAbsent(d.victimMetaId(), d) == null) deadShips.add(d);
            }
        }
        dedup.put("deadShips", totalDeadShips(views) - deadShips.size());

        var voiceLineLog = new ArrayList<VoiceLineEvent>();
        var vlSeen = new HashSet<String>();
        for (var v : views) {
            for (var vl : v.voiceLineLog()) {
                if (vlSeen.add(vl.clock() + "|" + vl.senderId().value() + "|" + vl.message())) voiceLineLog.add(vl);
            }
        }
        dedup.put("voiceLines", total(views, v -> v.voiceLineLog().size()) - voiceLineLog.size());

        var ribbonLog = new ArrayList<RibbonEvent>();
        var rbSeen = new HashSet<String>();
        for (var v : views) {
            for (var rb : v.ribbonLog()) {
                if (rbSeen.add(rb.clock() + "|" + rb.ribbonId())) ribbonLog.add(rb);
            }
        }
        dedup.put("ribbons", total(views, v -> v.ribbonLog().size()) - ribbonLog.size());

        // 齐射/鱼雷/命中：身份用全局唯一 salvoId/shotId（不依赖视角相关实体 id）
        var salvos = new ArrayList<ArtillerySalvo>();
        var salvoSeen = new HashSet<Integer>();
        for (var v : views) {
            for (var s : v.firedSalvos()) {
                if (salvoSeen.add(s.salvo().salvoId())) salvos.add(s);
            }
        }
        dedup.put("salvos", total(views, v -> v.firedSalvos().size()) - salvos.size());

        var torpedoes = new ArrayList<TorpedoRecord>();
        var torpSeen = new HashSet<Integer>();
        for (var v : views) {
            for (var t : v.torpedoes()) {
                if (torpSeen.add(t.data().shotId())) torpedoes.add(t);
            }
        }
        dedup.put("torpedoes", total(views, v -> v.torpedoes().size()) - torpedoes.size());

        var shotHits = new ArrayList<ShotHitRecord>();
        var hitSeen = new HashSet<Integer>();
        for (var v : views) {
            for (var h : v.shotHits()) {
                if (hitSeen.add(h.hit().shotId())) shotHits.add(h);
            }
        }
        dedup.put("shotHits", total(views, v -> v.shotHits().size()) - shotHits.size());

        // ── 广播状态：直接取主视角 ──
        var teamScores = new ArrayList<>(primary.teamScores());
        dedup.put("teamScores", 0);
        var capturePoints = new ArrayList<>(primary.capturePoints());
        dedup.put("capturePoints", 0);
        var buffZones = new ArrayList<>(primary.buffZones());
        dedup.put("buffZones", 0);
        var weatherZones = new ArrayList<>(primary.weatherZones());
        dedup.put("weatherZones", 0);

        var capturedBuffs = new ArrayList<CapturedBuff>();
        var cbSeen = new HashSet<String>();
        for (var v : views) {
            for (var c : v.capturedBuffs()) {
                if (cbSeen.add(c.clock() + "|" + c.entityId() + "|" + c.capturedBy())) capturedBuffs.add(c);
            }
        }
        dedup.put("capturedBuffs", total(views, v -> v.capturedBuffs().size()) - capturedBuffs.size());

        var buildings = new ArrayList<BuildingState>();
        var bdById = new LinkedHashMap<Integer, BuildingState>();
        for (var v : views) for (var b : v.buildings()) bdById.putIfAbsent(b.entityId(), b);
        buildings.addAll(bdById.values());
        dedup.put("buildings", total(views, v -> v.buildings().size()) - buildings.size());

        // ── 排序（时间线确定性：clock 升序；状态集按索引/队伍序）──
        killLog.sort(Comparator.comparingDouble(NormalizedKill::clock));
        chatLog.sort(Comparator.comparingDouble(NormalizedChat::clock));
        damageEvents.sort(Comparator.comparingDouble(NormalizedDamage::clock));
        consumableLog.sort(Comparator.comparingDouble(NormalizedConsumable::clock));
        deadShips.sort(Comparator.comparingDouble(NormalizedDeadShip::clock));
        voiceLineLog.sort(Comparator.comparingDouble(VoiceLineEvent::clock));
        ribbonLog.sort(Comparator.comparingDouble(RibbonEvent::clock));
        salvos.sort(Comparator.comparingDouble(ArtillerySalvo::clock));
        torpedoes.sort(Comparator.comparingDouble(TorpedoRecord::clock));
        shotHits.sort(Comparator.comparingDouble(ShotHitRecord::clock));
        teamScores.sort(Comparator.comparingInt(TeamScore::teamIndex));
        capturePoints.sort(Comparator.comparingInt(c -> c.index));

        var merged = new NormalizedReplay(
            primary.arenaId(), primary.version(), primary.mapName(), primary.gameMode(), primary.gameType(),
            primary.matchGroup(), primary.matchResult(), primary.finishType(), primary.winningTeam(),
            primary.battleStartClock(), primary.battleResultClock(), primary.battleEndClock(),
            primary.battleResultsJson(),
            new ArrayList<>(players), killLog, damageEvents, chatLog, consumableLog, deadShips,
            voiceLineLog, ribbonLog, salvos, torpedoes, shotHits,
            teamScores, capturePoints, buffZones, capturedBuffs, weatherZones, buildings);

        return new MergedResult(views.size(), merged, java.util.Map.of(), dedup);
    }

    // ── 校验 ─────────────────────────────────────────────────────────────

    /** 同场次校验（基于已归一数据的 version/arenaId）：版本一致 + 竞技场一致（双方非 0 才比较）。 */
    private static void validateSameBattle(List<NormalizedReplay> views) {
        var primary = views.get(0);
        for (int i = 1; i < views.size(); i++) {
            var v = views.get(i);
            var pv = primary.version();
            var rv = v.version();
            if (pv != null && rv != null && !pv.equals(rv)) {
                throw MergeException.versionMismatch(pv.toString(), rv.toString());
            }
            long pa = primary.arenaId();
            long ra = v.arenaId();
            if (pa != ra && pa != 0 && ra != 0) {
                throw MergeException.arenaMismatch(String.valueOf(pa), String.valueOf(ra));
            }
        }
    }

    // ── 统计辅助 ─────────────────────────────────────────────────────────

    private static int total(List<NormalizedReplay> views, java.util.function.ToIntFunction<NormalizedReplay> fn) {
        int n = 0;
        for (var v : views) n += fn.applyAsInt(v);
        return n;
    }

    private static int totalChat(List<NormalizedReplay> views) { return total(views, v -> v.chatLog().size()); }
    private static int totalDamage(List<NormalizedReplay> views) { return total(views, v -> v.damageEvents().size()); }
    private static int totalConsumables(List<NormalizedReplay> views) { return total(views, v -> v.consumableLog().size()); }
    private static int totalDeadShips(List<NormalizedReplay> views) { return total(views, v -> v.deadShips().size()); }
}
