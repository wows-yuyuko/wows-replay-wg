package com.shinoaki.wowsreplay.dumper;

import com.shinoaki.wowsreplay.core.JsonMapper;
import com.shinoaki.wowsreplay.core.ReplayFile;
import com.shinoaki.wowsreplay.core.ReplayVersionMismatchException;
import com.shinoaki.wowsreplay.core.data.LangProvider;
import com.shinoaki.wowsreplay.core.data.ShipConfig;
import com.shinoaki.wowsreplay.core.data.WowsInfo;
import com.shinoaki.wowsreplay.core.decode.PacketDecoder;
import com.shinoaki.wowsreplay.core.decode.PlayerStateData;
import com.shinoaki.wowsreplay.core.model.Version;
import com.shinoaki.wowsreplay.core.model.VoiceLine;
import com.shinoaki.wowsreplay.core.packet.Packet;
import com.shinoaki.wowsreplay.core.packet.Parser;
import com.shinoaki.wowsreplay.core.spec.GameDataCache;
import com.shinoaki.wowsreplay.core.spi.EntitySpecProvider;
import com.shinoaki.wowsreplay.core.spi.GameConstantsProvider;
import com.shinoaki.wowsreplay.dumper.minimap.MinimapExtractor;
import com.shinoaki.wowsreplay.dumper.minimap.MinimapMerger;
import com.shinoaki.wowsreplay.dumper.minimap.MinimapOutput;
import com.shinoaki.wowsreplay.ingest.BattleWorld;
import com.shinoaki.wowsreplay.ingest.EntityState;
import com.shinoaki.wowsreplay.ingest.mapped.NormalizedKill;
import com.shinoaki.wowsreplay.ingest.mapped.NormalizedReplay;
import com.shinoaki.wowsreplay.ingest.mapped.ReplayMapper;
import com.shinoaki.wowsreplay.ingest.report.BattleReport;
import com.shinoaki.wowsreplay.ingest.report.BattleReportBuilder;
import com.shinoaki.wowsreplay.merge.ParsedReplay;
import com.shinoaki.wowsreplay.merge.ReplayMerger;
import lombok.extern.slf4j.Slf4j;
import org.w3c.dom.Document;
import tools.jackson.databind.JsonNode;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * replay-dumper 管线（对标 Rust {@code replay-dumper} crate 的 Single 模式，
 * 忽略多 rep 合并），产出**单一 JSON**（Rust {@code pipeline::build_json_output}）。
 *
 * <p>装配：meta + {@link BattleReport}（含 players/capture_points/team_scores/…）+
 * {@code game_events} 时间线（consumable/kill/chat 按 clock 排序）+ 可选 minimap
 * （{@link MinimapOutput}）+ {@code space_size}（space.settings 解析）。</p>
 *
 * <p>game_params 名称解析不做（ship/装备等用原始 id，见评估）。</p>
 */
@Slf4j
public final class ReplayDumper {

    /** 管线选项（对标 Rust {@code ParseOptions} 的 Single 子集）。 */
    public record Options(
            LangProvider.Lang lang,
            boolean minimap,
            boolean selfDamageStats,
            /* minimap 字段 brotli 压缩等级 0-11，0 表示不压缩。 */
            int compressLevel
    ) {
        public Options {
            if (lang == null) lang = LangProvider.DEFAULT_LANG;
        }

        public static final Options DEFAULT = new Options(LangProvider.DEFAULT_LANG, false, false, 0);
    }

    /** 全部视角回放（第 0 项为主视角）。 */
    private final List<ReplayFile> replays;
    /** 主视角（= replays 第 0 项）：spec/constants 解析、dumpJson 与合并广播状态的权威。 */
    private final ReplayFile primary;
    private final Options options;
    private final EntitySpecProvider specProvider;
    private final GameConstantsProvider constants;
    private final GameDataCache cache;
    /** 匹配版本的游戏数据 live 目录（base 缺失/未匹配时为 null，版本门禁与 space_size 兜底）。 */
    private final Path gameDataDir;

    /**
     * @param replay  主视角回放（需带 {@link ReplayFile#gameDataBase()}，内部按版本自动生成
     *                {@link EntitySpecProvider} / {@link GameConstantsProvider}）
     * @param options 管线选项（minimap / 压缩 / selfDamageStats 等）
     */
    public ReplayDumper(ReplayFile replay, Options options) {
        this(List.of(replay), options, GameDataCache.withMaxSize(4));
    }

    /**
     * @param replays 同场次多视角回放（第 0 项为主视角）；{@link #dumpMergedJson()} 合并全部，
     *                {@link #dumpJson()} / {@link #dumpPrettyJson()} 只处理主视角
     * @param options 管线选项
     */
    public ReplayDumper(List<ReplayFile> replays, Options options) {
        this(replays, options, GameDataCache.withMaxSize(4));
    }

    /** 提供共享 {@link GameDataCache} 的构造（多实例复用缓存，避免重复加载游戏数据）。 */
    public ReplayDumper(List<ReplayFile> replays, Options options, GameDataCache cache) {
        if (replays == null || replays.isEmpty()) {
            throw new IllegalArgumentException("至少需要一份回放");
        }
        this.replays = List.copyOf(replays);
        this.primary = this.replays.getFirst();
        this.options = options;
        this.cache = cache;
        this.specProvider = cache.entitySpecs(primary);
        this.constants = cache.constants(primary);
        // 预热语言表（getLangProvider 依赖 lang 已加载），并缓存 wowsinfo。
        cache.wowsInfo(primary);
        this.gameDataDir = resolveGameDataDir(primary);
    }

    /** 解析主视角 → 单一 JSON（对标 Rust {@code parse_replay_with_options}）。 */
    public String dumpJson() throws ReplayVersionMismatchException {
        return JsonMapper.toJson(dump());
    }

    public Map<String, Object> dump() throws ReplayVersionMismatchException {
        verifyVersion(primary);
        MinimapOutput mm = null;
        MinimapExtractor.Collector collector = null;
        List<MinimapOutput.MinimapFrame> mmFrames = new ArrayList<>();;
        if (options.minimap()) {
            var extractor = new MinimapExtractor(specProvider, constants, primary);
            collector = extractor.newCollector((world, clock) -> mmFrames.add(MinimapExtractor.snapshot(world, clock)));
        }
        // 单遍解析：战报装配与 minimap 提取共享同一 BattleWorld。
        var world = parseWorld(primary, collector);
        var report = new BattleReportBuilder(world, primary.meta()).build();
        if (options.minimap()) {
            var core = collector.end(world);
            var frames = MinimapExtractor.enrichBuffZones(mmFrames, core.dropEvents());
            mm = new MinimapOutput(core.arenaId(), frames, core.firingEvents(), core.damageEvents(),
                core.shotHits(), core.deadShips(), core.battleStage(), core.winningTeam(),
                core.finishType(), core.scoringRules(), core.capturedBuffs(), core.dropEvents());
        }
        return assemble(primary, world, report, mm);
    }

    public String dumpPrettyJson() throws ReplayVersionMismatchException {
        return JsonMapper.toPrettyJson(dump());
    }

    /**
     * 多视角合并解析：主视角（replays 第 0 项）+ 其余视角 → 最终 JSON（形状与单 replay 一致）。
     *
     * @return 合并后的单一 JSON
     * @throws ReplayVersionMismatchException 主视角 build 与 game-data 不匹配
     */
    public Map<String, Object> dumpMerged() throws ReplayVersionMismatchException {
        verifyVersion(primary);
        var merger = new ReplayMerger(specProvider, constants);
        var parsed = new ArrayList<ParsedReplay>(replays.size());
        for (var r : replays) parsed.add(merger.parse(r));
        var merged = merger.merge(parsed);
        var report = parsed.getFirst().report();

        JsonNode battleResults = report.battleResults();
        MinimapOutput mm = options.minimap()
                ? new MinimapMerger(specProvider, constants, replays).merge()
                : null;
        return assembleFinal(primary, report, merged.replay(), mm, battleResults);
    }

    /**
     * 多视角合并解析：主视角（replays 第 0 项）+ 其余视角 → 最终 JSON（形状与单 replay 一致）。
     *
     * @return 合并后的单一 JSON
     * @throws ReplayVersionMismatchException 主视角 build 与 game-data 不匹配
     */
    public String dumpMergedJson() throws ReplayVersionMismatchException {
        return JsonMapper.toJson(dumpMerged());
    }

    /**
     * 逆向诊断：dump 所有玩家战舰的 shipConfig 原始 blob（hex + 字段级分解 + 未识别尾部）。
     * 输出给外部逆向项目对照 ship_params_id 对应的游戏脚本 XML 还原 blob 布局。
     * 返回的 Map 可直接 {@link JsonMapper#toPrettyJson} 写为文件。
     */
    public Map<String, Object> dumpShipConfigs() throws ReplayVersionMismatchException {
        verifyVersion(primary);
        var world = parseWorld(primary, null);
        return buildShipConfigDump(primary, world);
    }

    /**
     * 装配所有玩家 shipConfig blob dump。每艘车一条：entity_id/owner_entity_id/meta_id/username/
     * account_id + {@link ShipConfig#hexDump} 完整字节分解。
     *
     * <p>玩家→战舰关联对标 {@code BattleReportBuilder#buildVehicleEntity}（players 表 Avatar 实体 id →
     * vehicleToOwner 反查；复用 Avatar id 时即自身）。未关联到玩家（无 roster）的 Vehicle 也照常输出，
     * 身份字段缺省，保证不丢任何 blob。</p>
     */
    static Map<String, Object> buildShipConfigDump(ReplayFile replay, BattleWorld world) {
        var out = new LinkedHashMap<String, Object>();
        out.put("version", replay.version().toString());
        out.put("client_version_from_exe", replay.meta().clientVersionFromExe());
        out.put("date_time", replay.meta().dateTime());
        out.put("map_name", replay.meta().mapName());
        out.put("arena_id", world.arenaId());

        Version version = Version.fromClientExe(replay.meta().clientVersionFromExe());
        var ships = new ArrayList<Map<String, Object>>();
        Set<Integer> dumped = new HashSet<>();

        // owner 实体 id → 战舰实体 id（对标 resolveVehicleEid 的反向索引）
        var ownerToVehicle = new HashMap<Integer, Integer>();
        for (var e : world.vehicleToOwner().entrySet()) ownerToVehicle.put(e.getValue(), e.getKey());

        for (var pe : world.players().entrySet()) {
            long metaId = pe.getKey();
            var pi = pe.getValue();
            int avatarEid = pi.entityId;
            int vehicleEid = ownerToVehicle.getOrDefault(avatarEid, avatarEid);
            EntityState es = world.entities().get(vehicleEid);
            if (es == null || es.shipConfig == null) continue;
            dumped.add(vehicleEid);

            var sm = new LinkedHashMap<String, Object>();
            sm.put("meta_id", metaId);
            sm.put("username", pi.username);
            sm.put("account_id", world.accountIdOf(metaId));
            sm.put("entity_id", vehicleEid);
            sm.put("owner_entity_id", avatarEid);
            sm.put("blob", ShipConfig.hexDump(es.shipConfig, version));
            ships.add(sm);
        }

        // 兜底：有 blob 但未被任何玩家关联的 Vehicle（旧格式无 roster / 关联缺失），身份字段缺省
        for (var e : world.entities().entrySet()) {
            EntityState es = e.getValue();
            if (es.shipConfig == null || dumped.contains(e.getKey())) continue;
            if (es.kind != null && !"Vehicle".equals(es.kind)) continue;
            var sm = new LinkedHashMap<String, Object>();
            sm.put("entity_id", e.getKey());
            sm.put("owner_entity_id", world.vehicleToOwner().get(e.getKey()));
            sm.put("blob", ShipConfig.hexDump(es.shipConfig, version));
            ships.add(sm);
        }

        out.put("count", ships.size());
        out.put("ships", ships);
        return out;
    }

    private static Path resolveGameDataDir(ReplayFile replay) {
        try {
            return GameDataCache.resolveGameDataDir(replay);
        } catch (IOException e) {
            log.warn("定位游戏数据目录失败: {}", e.toString());
            return null;
        }
    }

    /**
     * 版本门禁：回放 client build 必须与加载的 game-data build 一致，否则拒绝解析
     * （防止用错版本的游戏数据解码，对标 ReplayVersionMismatchException）。
     * 任一 build 未知时跳过。
     */
    private void verifyVersion(ReplayFile replay) throws ReplayVersionMismatchException {
        if (gameDataDir == null) return;
        Path dataDir = gameDataDir.getParent();
        if (dataDir == null) return;
        String dataName = dataDir.getFileName().toString();
        long dataBuild = parseBuildNumber(dataName);
        if (dataBuild <= 0) return;

        String clientVersion = replay.meta().clientVersionFromExe();
        String[] parts = clientVersion != null ? clientVersion.split(",") : new String[0];
        long replayBuild = parts.length >= 4 ? parseBuildNumber(parts[3]) : 0;
        if (replayBuild > 0 && dataBuild != replayBuild) {
            throw new ReplayVersionMismatchException(
                    "回放 build 与游戏数据不匹配：回放 " + replay.version() + "，数据目录 " + dataName
                    + "（拒绝解析，防止 schema 错配）");
        }
    }

    /** 从 "data-M.m.p.b" 目录名或纯数字串解析 build 号，无法解析返回 0。 */
    private static long parseBuildNumber(String s) {
        if (s == null) return 0;
        int idx = s.lastIndexOf('.');
        String build = idx >= 0 ? s.substring(idx + 1) : s;
        try {
            return Long.parseLong(build);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    // ── 解析 ──────────────────────────────────────────────────────────────

    private BattleWorld parseWorld(ReplayFile replay, MinimapExtractor.Collector collector) {
        var parser = new Parser(specProvider, replay.version());
        var world = new BattleWorld(replay.meta(), replay.version(), constants);
        var decoder = new PacketDecoder(replay.version());

        var iter = replay.packetIterator();
        while (iter.hasNext()) {
            var raw = iter.next();
            var packet = parser.parse(raw);
            if (packet == null || packet.payload() instanceof Packet.InvalidPayload) continue;
            if (packet.packetType() == null) continue;
            world.process(decoder.decode(packet), raw.clock());
            if (collector != null) collector.onClockBoundary(world, world.currentClock().seconds());
        }
        world.finish();
        return world;
    }

    // ── 装配（对标 Rust build_json_output）────────────────────────────────

    private Map<String, Object> assemble(ReplayFile replay, BattleWorld world, BattleReport report, MinimapOutput mm) {
        // battle_results 已由 BattleReportBuilder 用 constants.json 解析为具名对象，原样输出。
        JsonNode battleResults = report.battleResults();
        NormalizedReplay normalized = ReplayMapper.map(world, report);
        return assembleFinal(replay, report, normalized, mm, battleResults);
    }

    /** 公共最终输出装配：单 replay 与多视角合并共用（形状一致）。 */
    private Map<String, Object> assembleFinal(ReplayFile replay, BattleReport report,
                                              NormalizedReplay normalized, MinimapOutput mm,
                                              JsonNode battleResults) {
        var out = new LinkedHashMap<String, Object>();
        out.put("battle_results", battleResults);
        out.put("arena_id", report.arenaId());
        // 主视角用户：单 replay 为录制者(self)玩家的 meta_id
        out.put("master_meta_id", report.selfPlayer().metaId());
        out.put("date_time", replay.meta().dateTime());
        out.put("version", report.version() != null ? report.version().toString() : null);
        out.put("map_id", replay.meta().mapId());
        out.put("map_name", report.mapName());
        out.put("space_size", parseSpaceSize(gameDataDir, replay.meta().mapName()));
        out.put("game_mode", report.gameMode());
        out.put("game_type", replay.meta().gameType());
        out.put("match_group", report.matchGroup());
        out.put("match_result", report.matchResult());
        out.put("finish_type", report.finishType());
        // 映射层：实体 id → 全局一致 metaId，事件流输出只带 metaId
        WowsInfo wowsInfo = cache.wowsInfo(primary);
        List<Map<String, Object>> players = buildPlayers(report, wowsInfo);
        out.put("players", JsonMapper.toTree(players));
        out.put("game_events", buildGameEvents(report, normalized, wowsInfo, constants));
        out.put("capture_points", report.capturePoints());
        out.put("captured_buffs", report.capturedBuffs());
        out.put("team_scores", report.teamScores());
        out.put("buildings", report.buildings());
        out.put("local_weather_zones", report.localWeatherZones());
        out.put("max_duration", report.maxDuration());
        out.put("played_duration", report.playedDuration());
        out.put("extra_duration", report.extraDuration());
        if (options.selfDamageStats()) {
            out.put("self_damage_stats", report.selfDamageStats());
        }
        var minimap = new LinkedHashMap<String, Object>();
        if (mm != null) {
            minimap.put("frames", mm.frames());
            minimap.put("firing_events", mm.firingEvents());
            minimap.put("damage_events", mm.damageEvents());
            minimap.put("shot_hits", mm.shotHits());
            minimap.put("dead_ships", mm.deadShips());
            if (options.compressLevel() > 0) {
                out.put("minimap", compressMinimapField(minimap, Math.clamp(options.compressLevel(), 0, 11)));
            } else {
                out.put("minimap", minimap);
            }
            out.put("battle_stage", mm.battleStage());
            out.put("scoring_rules", mm.scoringRules());
            out.put("drop_events", mm.dropEvents());
        }

        return out;
    }

    /**
     * minimap 字段压缩（对标 Rust {@code compress_minimap_field}）：JSON → Brotli → Base64 字符串。
     */
    static String compressMinimapField(Object value, int quality) {
        byte[] json = JsonMapper.toJson(value).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        var params = new com.aayushatharva.brotli4j.encoder.Encoder.Parameters().setQuality(quality);
        byte[] compressed;
        try {
            compressed = com.aayushatharva.brotli4j.encoder.Encoder.compress(json, params);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("brotli 压缩失败", e);
        }
        return java.util.Base64.getEncoder().encodeToString(compressed);
    }

    static {
        com.aayushatharva.brotli4j.Brotli4jLoader.ensureAvailability();
    }

    /**
     * players 装配（对标 Rust pipeline.rs player_json）：玩家字段（metaId + accountId，去掉
     * 视角相关 entity_id）+ vehicle{ship_id + modernizations/consumables/exteriors 名称 +
     * commander_skills 技能名 + commander_skills_id 舰长原始 id}。名称来自 wowsinfo.json
     * 映射（未知 id → null）；无 wowsinfo.json 时名称为 null。
     */
    List<Map<String, Object>> buildPlayers(BattleReport report, WowsInfo wowsInfo) {
        var players = new ArrayList<Map<String, Object>>();
        for (var p : report.players()) {
            var pm = new LinkedHashMap<String, Object>();
            pm.put("meta_id", p.metaId());
            pm.put("account_id", p.dbId());
            pm.put("username", p.username());
            pm.put("team_id", p.teamId());
            pm.put("relation", p.relation());
            pm.put("is_bot", p.isBot());
            if (p.initialState() != null) {
                pm.put("initial_state", buildInitialState(p.initialState()));
            }
            var veh = p.vehicleEntity();
            if (veh != null) {
                var v = new LinkedHashMap<String, Object>();
                if (veh.shipConfig() != null) {
                    var sc = veh.shipConfig();
                    v.put("ship_id", sc.shipParamsId());
                    // wowsinfo.json 名称映射（ship_id/commander_skills_id 保留原始 id，名称未知 → null）
                    v.put("modernizations", mapModernizations(sc.modernization(), wowsInfo));
                    v.put("consumables", mapConsumable(sc.consumables(), wowsInfo));
                    v.put("exteriors", mapExteriors(sc.exteriors(), wowsInfo));
                    v.put("ensigns", sc.ensigns());
                    v.put("ecoboosts", sc.ecoboosts());
                    // 舰长信息（原始 id，不做名称/传奇舰长解析）：按战舰类型取对应舰种技能名数组
                    if (sc.commanderSkills() != null) {
                        v.put("commander_skills", mapShipTypeSkills(sc, wowsInfo));
                    }
                    if (sc.commanderSkillsId() != null) v.put("commander_skills_id", sc.commanderSkillsId());
                }
                if (!v.isEmpty()) pm.put("vehicle", v);
            }
            players.add(pm);
        }
        return players;
    }

    private List<Map<String, String>> mapModernizations(List<Long> ids, WowsInfo wowsInfo) {
        var out = new ArrayList<Map<String, String>>(ids.size());
        for (var id : ids) {
            var m = new LinkedHashMap<String, String>();
            m.put("id", id.toString());
            var info = wowsInfo.modernization(id);
            if (info != null) {
                m.put("icon", info.icon());
                m.put("name", this.cache.getLangProvider(this.options.lang(), info.name()));
            } else {
                m.put("icon", null);
                m.put("name", null);
            }
            out.add(m);
        }
        return out;
    }

    private List<Map<String, String>> mapConsumable(List<Long> ids, WowsInfo wowsInfo) {
        var out = new ArrayList<Map<String, String>>(ids.size());
        for (var id : ids) {
            var m = new LinkedHashMap<String, String>();
            m.put("id", id.toString());
            var info = wowsInfo.consumable(id);
            if (info != null) {
                m.put("type", info.type());
                m.put("icon", info.icon());
                m.put("name", this.cache.getLangProvider(this.options.lang(), info.name()));
            } else {
                m.put("type", null);
                m.put("icon", null);
                m.put("name", null);
            }
            out.add(m);
        }
        return out;
    }

    /** exteriors：id 数组 → {type, icon} 对象数组（未知 id → 字段为 null，保持与原始数组同序）。 */
    private List<Map<String, String>> mapExteriors(List<Long> ids, WowsInfo wowsInfo) {
        var out = new ArrayList<Map<String, String>>(ids.size());
        for (var id : ids) {
            var m = new LinkedHashMap<String, String>();
            m.put("id", id.toString());
            var info = wowsInfo.exterior(id);
            if (info != null) {
                m.put("type", info.type());
                m.put("icon", info.icon());
                m.put("name", this.cache.getLangProvider(this.options.lang(), info.name()));
            } else {
                m.put("type", null);
                m.put("icon", null);
                m.put("name", null);
            }
            out.add(m);
        }
        return out;
    }

    /**
     * 按战舰类型（wowsinfo shipType）只取对应舰种的技能，返回对象数组（{skillType, icon, name}，同序）。
     * 未知舰种返回空数组。
     */
    private List<Map<String, String>> mapShipTypeSkills(ShipConfig sc, WowsInfo wowsInfo) {
        var skills = sc.commanderSkills();
        if (skills == null) return List.of();
        String type = wowsInfo.shipType(sc.shipParamsId());
        List<Integer> ids = switch (type == null ? "" : type) {
            case "AirCarrier" -> skills.aircraftCarrier();
            case "Battleship" -> skills.battleship();
            case "Cruiser" -> skills.cruiser();
            case "Destroyer" -> skills.destroyer();
            case "Auxiliary" -> skills.auxiliary();
            case "Submarine" -> skills.submarine();
            default -> List.of();
        };
        return skillNames(ids, wowsInfo);
    }


    /** 6 舰种技能 id 数组 → 名称数组（同序，未知 → null）。 */
    private List<Map<String, String>> skillNames(List<Integer> ids, WowsInfo wowsInfo) {
        var out = new ArrayList<Map<String, String>>(ids.size());
        for (var id : ids) {
            var m = new LinkedHashMap<String, String>();
            var info = wowsInfo.skill(id);
            m.put("skillType", id.toString());
            if (info != null) {
                m.put("icon", info.icon());
                m.put("name", this.cache.getLangProvider(this.options.lang(), info.name()));
            } else {
                m.put("icon", null);
                m.put("name", null);
            }
            out.add(m);
        }
        return out;
    }


    /**
     * initial_state 装配（对标 Rust pipeline.rs player_json：保留具名字段 + human_properties +
     * raw_with_names，去掉原始 raw 索引映射）。null 时只输出原始字段名映射。
     */
    static Map<String, Object> buildInitialState(PlayerStateData psd) {
        var m = new LinkedHashMap<String, Object>();
        m.put("username", psd.username());
        m.put("clan", psd.clan());
        m.put("clan_id", psd.clanId());
        m.put("clan_color", psd.clanColor());
        m.put("realm", psd.realm());
        m.put("db_id", psd.dbId());
        m.put("meta_ship_id", psd.metaShipId());
        m.put("entity_id", psd.entityId());
        m.put("team_id", psd.teamId());
        m.put("max_health", psd.maxHealth());
        m.put("is_abuser", psd.isAbuser());
        m.put("is_hidden", psd.isHidden());
        m.put("is_bot", psd.isBot());
        if (psd.avatarId() != null) {
            var hp = new LinkedHashMap<String, Object>();
            hp.put("avatar_id", psd.avatarId());
            hp.put("prebattle_id", psd.prebattleId());
            hp.put("is_client_loaded", psd.isClientLoaded());
            hp.put("is_connected", psd.isConnected());
            m.put("human_properties", hp);
        }
        m.put("raw_with_names", psd.rawWithNames());
        return m;
    }

    /** game_events 时间线（对标 Rust pipeline.rs）：consumable/kill/chat/voiceline 按 clock 排序，玩家身份只用 metaId。
     *  consumable 事件的 consumable 是 consumableType 数值（onConsumableUsed b[1]，非槽位下标），经
     *  constants.json CONSUMABLE_IDS 翻译成名字，再按该玩家 shipConfig.abilities 各槽能力的
     *  params.consumableType（wowsinfo abilities.consumableType）匹配出真实能力 GameParams id
     *  （consumable_id）与名称（consumable_name）；匹配不到时 consumable_id 直接返回原始数值。
     *  voiceline 事件的 sender 是 receive_CommonCMD args[0]（同 chat 语义 = 战斗内 meta id 空间），
     *  line 是经 GameConstants.voiceLineName 解析好的指令名（如 QuickTactic/AttentionToSquare/Wilco…）。 */
    List<Map<String, Object>> buildGameEvents(BattleReport report, NormalizedReplay replay,
                                              WowsInfo wowsInfo, GameConstantsProvider constants) {
        var events = new ArrayList<Map<String, Object>>();
        Map<Long, String> metaToUser = new HashMap<>();
        // 实体 id → metaId 索引（Avatar 实体 id + Vehicle 实体 id，对标 ReplayMapper.metaIdOf）
        Map<Long, Long> entityToMeta = new HashMap<>();
        for (var p : report.players()) {
            metaToUser.put(p.metaId(), p.username());
            entityToMeta.put((long) p.entityId(), p.metaId());
            if (p.vehicleEntity() != null) {
                entityToMeta.put((long) p.vehicleEntity().id().value(), p.metaId());
            }
        }
        // 飞机的消耗品事件在 minimap 里；先在本体消耗品里按 filter 匹配（优先用玩家自己的），
        // 匹配不到再退回全局 consumableFindFilter。空槽(id=0)/无 vehicle 的玩家跳过。
        Map<Long, List<WowsInfo.Abilities>> metaMap = new HashMap<>();
        for (var r : report.players()) {
            if (r.vehicleEntity() == null || r.vehicleEntity().shipConfig() == null) continue;
            List<WowsInfo.Abilities> list = new ArrayList<>();
            for (var consumableId : r.vehicleEntity().shipConfig().consumables()) {
                list.add(wowsInfo.consumable(consumableId));
            }
            metaMap.put(r.metaId(), list);
        }
        for (var e : replay.consumableLog()) {
            var data = new LinkedHashMap<String, Object>();
            data.put("meta_id", e.metaId());
            data.put("username", e.username());
            data.put("entity_id", e.entityId());
            data.put("type", e.type());
            data.put("consumable", e.consumableId());
            var ctName = constants.consumableName((int) e.consumableId()).orElse(null);
            var abilities = metaMap.getOrDefault(e.metaId(), java.util.List.of());
            WowsInfo.Abilities optional = abilities.stream()
                    .filter(Objects::nonNull) // 空槽(id=0)映射为 null，跳过
                    .filter(f -> ctName != null && f.filter() != null && ctName.equalsIgnoreCase(f.filter()))
                    .findFirst()
                    .orElseGet(() -> wowsInfo.consumableFindFilter(ctName));
            if (optional != null) {
                data.put("consumable_icon", optional.icon());
                data.put("consumable_name", this.cache.getLangProvider(this.options.lang(), optional.name()));
            } else {
                data.put("consumable_icon", null);
                data.put("consumable_name", null);
            }

            data.put("activated_at", e.clock());
            data.put("duration", e.duration());
            events.add(event("consumable", e.clock(), data));
        }

        for (var k : replay.killLog()) {
            var data = getData(k, constants);
            events.add(event("kill", k.clock(), data));
        }

        for (var c : replay.chatLog()) {
            var data = new LinkedHashMap<String, Object>();
            data.put("meta_id", c.metaId());
            data.put("username", c.username());
            data.put("channel", c.channel());
            data.put("message", c.message());
            events.add(event("chat", c.clock(), data));
        }

        for (var v : replay.voiceLineLog()) {
            long metaId = Integer.toUnsignedLong(v.senderId().value());
            var data = new LinkedHashMap<String, Object>();
            data.put("meta_id", metaId);
            data.put("username", metaToUser.getOrDefault(metaId, "account " + metaId));
            data.put("global", v.isGlobal());
            VoiceLine vl = v.voiceLine();
            data.put("type", vl.type());
            // 指令附加参数（按命令类：rect→row/column、mapPoint→x/y、target→targetType/targetId）
            VoiceLine.VoiceLineData pd = vl.data();
            if (pd != null) {
                var params = new LinkedHashMap<String, Object>();
                if (pd.row() != null) {
                    params.put("row", pd.row());
                    params.put("column", pd.column());
                }
                if (pd.x() != null) {
                    params.put("x", pd.x());
                    params.put("y", pd.y());
                }
                if (pd.targetType() != null) {
                    params.put("target_type", pd.targetType());
                    params.put("target_type_name", VoiceLine.entityTypeName(pd.targetType()));
                }
                if (pd.targetId() != null) {
                    params.put("target_id", pd.targetId());
                    // 仅 SHIP(0)/PLAYER(12) 类型目标才是玩家船，可映射 metaId
                    if (pd.targetType() != null && (pd.targetType() == 0 || pd.targetType() == 12)) {
                        params.put("target_meta_id", entityToMeta.getOrDefault(pd.targetId(), 0L));
                    }
                }
                if (!params.isEmpty()) data.put("data", params);
            }
            events.add(event("voiceline", v.clock(), data));
        }

        events.sort(Comparator.comparingDouble(e -> ((Number) e.get("clock")).doubleValue()));
        return events;
    }

    private static LinkedHashMap<String, Object> getData(NormalizedKill k, GameConstantsProvider constants) {
        var killer = new LinkedHashMap<String, Object>();
        killer.put("meta_id", k.killerMetaId());
        killer.put("username", k.killerName());
        var victim = new LinkedHashMap<String, Object>();
        victim.put("meta_id", k.victimMetaId());
        victim.put("username", k.victimName());
        var data = new LinkedHashMap<String, Object>();
        data.put("killer", killer);
        data.put("victim", victim);
        data.put("cause", k.cause());
        // 死亡原因名（constants.json DEATH_REASONS id→name；未知 → null）
        data.put("cause_icon", constants.deathReasonName(k.cause()).orElse(null));
        return data;
    }

    private static Map<String, Object> event(String type, float clock, Map<String, Object> data) {
        var ev = new LinkedHashMap<String, Object>();
        ev.put("type", type);
        ev.put("clock", clock);
        ev.put("data", data);
        return ev;
    }

    // ── space_size（对标 Rust position::parse_space_settings）────────────

    public static Integer parseSpaceSize(Path gameDataBase, String mapName) {
        if (mapName == null || mapName.isBlank()) return null;
        Path[] candidates = {
                gameDataBase.resolve(mapName).resolve("space.settings"),
                gameDataBase.resolve("spaces").resolve(mapName).resolve("space.settings"),
        };
        for (var p : candidates) {
            if (Files.exists(p)) {
                try {
                    return parseSpaceSettings(p);
                } catch (Exception e) {
                    log.warn("解析 space.settings 失败 {}: {}", p, e.toString());
                    return null;
                }
            }
        }
        log.debug("未找到 space.settings（{} 或 spaces/{}）", mapName, mapName);
        return null;
    }

    private static Integer parseSpaceSettings(Path file) throws IOException {
        var dbf = DocumentBuilderFactory.newInstance();
        dbf.setIgnoringComments(true);
        dbf.setIgnoringElementContentWhitespace(true);
        Document doc;
        try {
            doc = dbf.newDocumentBuilder().parse(file.toFile());
        } catch (Exception e) {
            throw new IOException(e);
        }
        var bounds = findTag(doc, "bounds");
        if (bounds == null) return null;
        int minX = intAttrOrChild(bounds, "minX");
        int maxX = intAttrOrChild(bounds, "maxX");
        int minY = intAttrOrChild(bounds, "minY");
        int maxY = intAttrOrChild(bounds, "maxY");

        double chunkSize = 100.0;
        var chunkNode = findTag(doc, "chunkSize");
        if (chunkNode != null) {
            try {
                chunkSize = Double.parseDouble(chunkNode.getTextContent().trim());
            } catch (NumberFormatException ignored) {
            }
        }

        double chunksX = maxX - minX + 1;
        double chunksY = maxY - minY + 1;
        int spaceW = (int) Math.round((chunksX - 4.0) * chunkSize);
        int spaceH = (int) Math.round((chunksY - 4.0) * chunkSize);
        int spaceSize = Math.max(spaceW, spaceH);

        log.debug("Map {}: bounds=({},{})..({},{}) chunk_size={} space_size={}",
                file.getParent() != null ? file.getParent().getFileName() : "", minX, minY, maxX, maxY, chunkSize, spaceSize);
        return spaceSize;
    }

    private static org.w3c.dom.Element findTag(Document doc, String name) {
        var nl = doc.getElementsByTagName(name);
        return nl.getLength() > 0 ? (org.w3c.dom.Element) nl.item(0) : null;
    }

    private static int intAttrOrChild(org.w3c.dom.Element parent, String name) {
        if (parent.hasAttribute(name)) {
            try {
                return Integer.parseInt(parent.getAttribute(name));
            } catch (NumberFormatException ignored) {
            }
        }
        var nl = parent.getElementsByTagName(name);
        if (nl.getLength() > 0) {
            try {
                return Integer.parseInt(nl.item(0).getTextContent().trim());
            } catch (NumberFormatException ignored) {
            }
        }
        return 0;
    }
}
