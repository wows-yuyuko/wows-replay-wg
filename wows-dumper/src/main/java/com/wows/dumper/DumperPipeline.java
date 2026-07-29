package com.wows.dumper;

import com.wows.replay.analyzer.BattleReport;
import com.wows.replay.analyzer.ReplayAnalyzer;
import com.wows.replay.analyzer.ReplayAnalyzerConfig;
import com.wows.replay.core.*;
import com.wows.replay.spec.def.XmlEntitySpecProvider;
import com.wows.replay.spec.spi.DefFileLoader;
import com.wows.replay.spec.types.Version;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.zip.GZIPOutputStream;

/**
 * 回放 → JSON 管线，对标 Rust replay-dumper 的输出格式。
 *
 * <p>支持：丰富的 JSON 输出、小地图位置时间线、Brotli 压缩、
 * BattleResults 解析、时间线事件、团队分数、占领点状态等。</p>
 */
public final class DumperPipeline {

    private final Path gameDataBase;

    /** 全局 LRU 缓存，跨多次 dump 共享已加载数据。 */
    private static final GameDataCache CACHE = GameDataCache.withMaxSize(4);

    // Brotli 压缩在 brotli4j 依赖可用时启用；否则回退到 GZIP。
    private static final boolean BROTLI_AVAILABLE = checkBrotli();

    private static boolean checkBrotli() {
        try {
            Class.forName("com.aayushatharva.brotli4j.Brotli4jLoader");
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    public DumperPipeline(Path gameDataBase) {
        this.gameDataBase = gameDataBase;
    }

    // ── Options ───────────────────────────────────────────────────────────

    /** 解析选项，对标 Rust {@code ParseOptions}。 */
    public record Options(
            boolean minimap,
            int minimapStep,
            Path constantsFile,
            Path gameParamFile,
            boolean vehicleEvents,
            boolean selfDamageStats,
            Integer compressLevel,
            boolean battleResults
    ) {
        public static final Options DEFAULT = new Options(false, 7, null, null, false, false, null, false);

        /** Builder 风格：从默认值创建并覆盖指定字段。 */
        public Options withMinimap(boolean v, int step) {
            return new Options(v, step, constantsFile, gameParamFile, vehicleEvents, selfDamageStats, compressLevel, battleResults);
        }

        public Options withVehicleEvents(boolean v) {
            return new Options(minimap, minimapStep, constantsFile, gameParamFile, v, selfDamageStats, compressLevel, battleResults);
        }

        public Options withSelfDamageStats(boolean v) {
            return new Options(minimap, minimapStep, constantsFile, gameParamFile, vehicleEvents, v, compressLevel, battleResults);
        }

        public Options withCompress(Integer v) {
            return new Options(minimap, minimapStep, constantsFile, gameParamFile, vehicleEvents, selfDamageStats, v, battleResults);
        }

        public Options withBattleResults(boolean v) {
            return new Options(minimap, minimapStep, constantsFile, gameParamFile, vehicleEvents, selfDamageStats, compressLevel, v);
        }
    }

    // ── dump 入口 ─────────────────────────────────────────────────────────

    /** 从文件 dump。 */
    public String dump(Path replayPath, Options options) throws IOException, ReplayException {
        return dump(ReplayFile.fromFile(replayPath), options);
    }

    /** 从字节数组 dump。 */
    public String dump(byte[] replayBytes, Options options) throws ReplayException {
        return dump(ReplayFile.fromBytes(replayBytes), options);
    }

    private String dump(ReplayFile replay, Options options) {
        Path gameData = findGameData(replay);

        if (gameData == null) {
            System.err.println("[wows-dumper] 警告：未找到版本匹配的游戏数据目录 (base="
                + gameDataBase + ", version=" + replay.meta().clientVersionFromExe() + ")");
            System.err.println("[wows-dumper] 玩家/事件/占点等数据将为空。请确认 "
                + gameDataBase + "/data-{version}/live/ 目录存在且包含 scripts/entity_defs/");
        }

        // 加载 constants.json（显式路径优先）
        JsonConstantsProvider constants = null;
        if (options.constantsFile != null && Files.exists(options.constantsFile)) {
            constants = JsonConstantsProvider.fromFile(options.constantsFile);
        } else if (gameData != null) {
            var ver = GameDataCache.VersionKey.from(gameData);
            constants = CACHE.constants(ver, gameData);
        }

        // 创建基础分析器获取 BattleReport（向后兼容路径）
        var analyzerBuilder = ReplayAnalyzer.builder()
                .config(ReplayAnalyzerConfig.builder()
                        .minimap(options.minimap, options.minimapStep)
                        .vehicleEvents(options.vehicleEvents)
                        .selfDamageStats(options.selfDamageStats)
                        .decodePackets(gameData != null)
                        .build());
        if (constants != null) analyzerBuilder.constantsProvider(constants);

        // 如果有游戏数据，注入实体规范提供者以启用完整数据包解码
        if (gameData != null) {
            analyzerBuilder.specProvider(createSpecProvider(gameData));
        }

        var analyzer = analyzerBuilder.build();
        var report = analyzer.buildReport(replay);

        // 丰富提取：全量数据包遍历。优先使用完整实体规范，加载失败时回退到空规范
        RichExtractor extractor;
        try {
            var specProvider = gameData != null
                ? createSpecProvider(gameData)
                : com.wows.replay.spec.spi.EntitySpecProvider.empty();
            extractor = new RichExtractor(replay, specProvider);
            extractor.extract();
        } catch (Exception e) {
            System.err.println("[wows-dumper] 警告：实体规范加载失败，回退到空规范: " + e);
            extractor = new RichExtractor(replay, com.wows.replay.spec.spi.EntitySpecProvider.empty());
            extractor.extract();
        }

        // 小地图提取
        MinimapData.MinimapOutput minimapOutput = null;
        if (options.minimap && gameData != null) {
            minimapOutput = buildMinimapOutput(replay, report, options);
        }

        // space_size
        Integer spaceSize = null;
        if (gameData != null) {
            spaceSize = SpaceSettings.parse(gameData, replay.meta().mapName());
        }

        return buildJson(replay, report, extractor, minimapOutput, spaceSize, constants, options);
    }

    // ── 游戏数据目录发现 ────────────────────────────────────────────────────

    /** 查找与回放版本匹配的 data-{version}/live/ 目录。 */
    public Path findGameData(ReplayFile replay) {

        var version = Version.fromClientExe(replay.meta().clientVersionFromExe());
        //移除末尾的数字
        var ver = "data-" + version.major() + "." + version.minor() + ".";
        for (var f : Optional.ofNullable(gameDataBase.toFile().listFiles()).orElse(new File[]{})) {
            if (f.isDirectory() && f.getName().startsWith(ver)) {
                return f.toPath().resolve("live");
            }
        }
        return null;
    }

    /** 从游戏数据目录创建实体规范提供者。 */
    private static XmlEntitySpecProvider createSpecProvider(Path gameData) {
        return new XmlEntitySpecProvider(path -> {
            var file = gameData.resolve(path);
            if (!Files.exists(file)) throw new IOException("def file not found: " + path);
            return Files.readAllBytes(file);
        });
    }

    // ── JSON 构建 ────────────────────────────────────────────────────────────

    private String buildJson(ReplayFile replay, BattleReport report,
                             RichExtractor ext, MinimapData.MinimapOutput minimap,
                             Integer spaceSize, JsonConstantsProvider constants,
                             Options options) {
        var root = JsonMapper.createObject();
        var meta = replay.meta();

        // 基本信息
        root.put("arena_id", meta.mapId());
        root.put("date_time", meta.dateTime());
        root.put("version", meta.clientVersionFromExe().replace(',', '.'));
        root.put("map_id", meta.mapId());
        root.put("map_name", meta.mapDisplayName() != null ? meta.mapDisplayName() : meta.mapName());
        root.put("game_mode", meta.gameMode());
        root.put("game_type", meta.gameType());
        root.put("match_group", meta.matchGroup());

        if (spaceSize != null) root.put("space_size", spaceSize);

        // 使用 RichExtractor 数据填充丰富的 JSON
        if (ext != null) {
            buildRichJson(root, ext, constants, options);
        } else {
            // 回退：使用 BattleReport 的基本字段
            root.set("meta", JsonMapper.toTree(report.meta()));
            root.set("summary", JsonMapper.toTree(report.summary()));
            root.set("packets", JsonMapper.toTree(report.packets()));
            if (report.entities() != null) root.set("entities", JsonMapper.toTree(report.entities()));
            if (report.resolvedVehicles() != null) root.set("vehicles_resolved", JsonMapper.toTree(report.resolvedVehicles()));
            if (report.minimap() != null) root.set("minimap", JsonMapper.toTree(report.minimap()));
        }

        // 小地图输出
        if (minimap != null) {
            appendMinimapToJson(root, minimap, options.compressLevel);
        }

        return JsonMapper.toJson(root);
    }

    /** 使用 RichExtractor 数据构建丰富的 JSON 输出。 */
    private void buildRichJson(ObjectNode root, RichExtractor ext,
                               JsonConstantsProvider constants, Options options) {
        // 加载 constants JSON 树用于解析
        JsonNode constantsJson = null;
        if (constants != null) {
            var section = constants.section("");
            if (section == null && options.constantsFile != null && Files.exists(options.constantsFile)) {
                try {
                    constantsJson = JsonMapper.readTree(Files.readAllBytes(options.constantsFile));
                } catch (IOException ignored) {
                }
            }
        }
        if (constantsJson == null && options.constantsFile != null && Files.exists(options.constantsFile)) {
            try {
                constantsJson = JsonMapper.readTree(Files.readAllBytes(options.constantsFile));
            } catch (IOException ignored) {
            }
        }

        // match_result / finish_type
        if (ext.matchResult != null) root.put("match_result", ext.matchResult);
        if (ext.finishType != null) root.put("finish_type", ext.finishType);

        // durations
        if (ext.maxDuration != null) root.put("max_duration", ext.maxDuration);
        if (ext.playedDuration != null) root.put("played_duration", ext.playedDuration);
        if (ext.extraDuration != null) root.put("extra_duration", ext.extraDuration);

        // 玩家列表
        ArrayNode playersArr = root.putArray("players");
        for (var entry : ext.players.entrySet()) {
            long dbId = entry.getKey();
            var info = entry.getValue();
            int eid = info.entityId;

            ObjectNode player = playersArr.addObject();
            player.put("db_id", dbId);
            player.put("username", info.username);
            player.put("entity_id", eid);

            // 初始状态
            ObjectNode initial = player.putObject("initial_state");
            initial.put("entity_id", eid);
            initial.put("db_id", dbId);
            initial.put("username", info.username);
            if (info.teamId >= 0) initial.put("team_id", info.teamId);

            Float health = ext.entityHealth.get(eid);
            if (health != null) initial.put("health", health);
            Float maxHealth = ext.entityMaxHealth.get(eid);
            if (maxHealth != null) initial.put("max_health", maxHealth);
            Boolean alive = ext.entityAlive.get(eid);
            if (alive != null) initial.put("is_alive", alive);
            String type = ext.entityTypes.get(eid);
            if (type != null) initial.put("entity_type", type);

            // 载具信息
            Long vehicleId = ext.entityVehicle.get(eid);
            if (vehicleId != null) {
                ObjectNode vehicle = player.putObject("vehicle");
                vehicle.put("ship_id", vehicleId);
            }

            // 消费品 / 击杀时间线
            if (options.vehicleEvents) {
                addVehicleEvents(player, eid, dbId, ext);
            }
        }

        // game_events
        ArrayNode eventsArr = root.putArray("game_events");
        for (var ce : ext.consumableEvents) {
            ObjectNode evt = eventsArr.addObject();
            evt.put("type", "consumable");
            evt.put("clock", ce.clock());
            ObjectNode data = evt.putObject("data");
            data.put("entity_id", ce.entityId());
            data.put("db_id", ce.dbId());
            data.put("username", ce.username());
            data.put("consumable", ce.consumableId());
            data.put("activated_at", ce.clock());
            data.put("duration", ce.duration());
        }
        for (var ke : ext.killEvents) {
            ObjectNode evt = eventsArr.addObject();
            evt.put("type", "kill");
            evt.put("clock", ke.clock());
            ObjectNode data = evt.putObject("data");
            ObjectNode killer = data.putObject("killer");
            killer.put("db_id", ke.killerDbId());
            killer.put("entity_id", ke.killerEid());
            killer.put("username", ke.killerName());
            ObjectNode victim = data.putObject("victim");
            victim.put("db_id", ke.victimDbId());
            victim.put("entity_id", ke.victimEid());
            victim.put("username", ke.victimName());
            data.put("cause", ke.cause());
        }
        for (var chat : ext.chatEvents) {
            ObjectNode evt = eventsArr.addObject();
            evt.put("type", "chat");
            evt.put("clock", chat.clock());
            ObjectNode data = evt.putObject("data");
            data.put("entity_id", chat.entityId());
            data.put("db_id", chat.dbId());
            data.put("username", chat.senderName());
            data.put("channel", chat.channel());
            data.put("message", chat.message());
        }
        // 按 clock 排序
        sortEventsByClock(eventsArr);

        // 分数事件
        ArrayNode scoresArr = root.putArray("team_scores");
        for (var se : ext.teamScores.entrySet()) {
            ObjectNode score = scoresArr.addObject();
            score.put("team_index", se.getKey());
            score.put("score", se.getValue());
        }

        // 占领点
        ArrayNode cpArr = root.putArray("capture_points");
        for (var cp : ext.cpStates.entrySet()) {
            ObjectNode cpNode = cpArr.addObject();
            cpNode.put("index", cp.getKey());
            var s = cp.getValue();
            cpNode.put("team_id", s.teamId);
            cpNode.put("invader_team", s.invaderTeam);
            if (s.progress instanceof Double d) cpNode.put("progress", d);
            else if (s.progress instanceof List<?> l) {
                ArrayNode prog = cpNode.putArray("progress");
                for (var v : l) prog.add(((Number) v).doubleValue());
            }
            cpNode.put("has_invaders", s.hasInvaders);
            cpNode.put("both_inside", s.bothInside);
            cpNode.put("is_enabled", s.isEnabled);
        }

        // 建筑
        if (!ext.buildings.isEmpty()) {
            ArrayNode bArr = root.putArray("buildings");
            for (var b : ext.buildings) {
                ObjectNode bn = bArr.addObject();
                bn.put("entity_id", b.entityId());
                bn.put("x", b.x());
                bn.put("z", b.z());
                bn.put("team_id", b.teamId());
                bn.put("params_id", b.paramsId());
                bn.put("is_alive", b.isAlive());
            }
        }

        // 天气区域
        if (!ext.weatherZones.isEmpty()) {
            ArrayNode wArr = root.putArray("local_weather_zones");
            for (var wz : ext.weatherZones) {
                ObjectNode wn = wArr.addObject();
                wn.put("name", wz.name());
                wn.put("x", wz.x());
                wn.put("z", wz.z());
                wn.put("radius", wz.radius());
                wn.put("params_id", wz.paramsId());
            }
        }

        // 增益区域
        if (!ext.buffZones.isEmpty()) {
            ObjectNode bzObj = root.putObject("buff_zones");
            for (var bz : ext.buffZones) {
                ObjectNode bn = bzObj.putObject(String.valueOf(bz.entityId()));
                bn.put("x", bz.x());
                bn.put("z", bz.z());
                bn.put("radius", bz.radius());
                bn.put("team_id", bz.teamId());
                bn.put("is_active", bz.isActive());
                if (bz.dropParamsId() != null) bn.put("drop_params_id", bz.dropParamsId());
            }
        }

        // 已夺取的增益
        if (!ext.capturedBuffs.isEmpty()) {
            ArrayNode cbArr = root.putArray("captured_buffs");
            for (var cb : ext.capturedBuffs) {
                ObjectNode cn = cbArr.addObject();
                cn.put("entity_id", cb.entityId());
                cn.put("params_id", cb.paramsId());
                cn.put("captured_by", cb.capturedBy());
                cn.put("clock", cb.clock());
            }
        }

        // self_damage_stats
        if (options.selfDamageStats) {
            long totalDealt = 0;
            for (var de : ext.damageEvents) totalDealt += (long) de.amount();
            ObjectNode sds = root.putObject("self_damage_stats");
            sds.put("total_damage_dealt", totalDealt);
        }

        // battle_results
        if (options.battleResults && ext.battleResultsJson != null) {
            try {
                var raw = JsonMapper.readTree(ext.battleResultsJson);
                root.set("battle_results", constantsJson != null ? resolveBattleResults(raw, constantsJson) : raw);
            } catch (Exception ignored) {
            }
        }

        // playersPrivateInfo
        if (ext.battleResultsJson != null && constantsJson != null) {
            try {
                var raw = JsonMapper.readTree(ext.battleResultsJson);
                var privInfo = extractPrivateInfo(raw, constantsJson);
                if (!privInfo.isEmpty()) {
                    ObjectNode ppi = root.putObject("playersPrivateInfo");
                    privInfo.forEach(ppi::set);
                }
            } catch (Exception ignored) {
            }
        }
    }

    /** 按 clock 字段排序 JSON 数组中的事件。 */
    private static void sortEventsByClock(ArrayNode arr) {
        var list = new ArrayList<JsonNode>(arr.size());
        for (var n : arr) list.add(n);
        list.sort((a, b) -> Double.compare(
                a.get("clock").asDouble(0), b.get("clock").asDouble(0)));
        arr.removeAll();
        list.forEach(arr::add);
    }

    // results_info 需要在 BattleResults 解析阶段填充（TODO）

    /** 添加消费品 / 击杀时间线到玩家节点。 */
    private void addVehicleEvents(ObjectNode player, int eid, long dbId, RichExtractor ext) {
        ArrayNode consArr = null;
        for (var ce : ext.consumableEvents) {
            if (ce.entityId() != eid) continue;
            if (consArr == null) consArr = player.putArray("consumable_events");
            ObjectNode cn = consArr.addObject();
            cn.put("entity_id", ce.entityId());
            cn.put("db_id", ce.dbId());
            cn.put("username", ce.username());
            cn.put("consumable", ce.consumableId());
            cn.put("activated_at", ce.clock());
            cn.put("duration", ce.duration());
        }

        ArrayNode killArr = null;
        for (var ke : ext.killEvents) {
            if (ke.killerDbId() != dbId) continue;
            if (killArr == null) killArr = player.putArray("kill_events");
            ObjectNode kn = killArr.addObject();
            ObjectNode killer = kn.putObject("killer");
            killer.put("db_id", ke.killerDbId());
            killer.put("entity_id", ke.killerEid());
            killer.put("username", ke.killerName());
            ObjectNode victim = kn.putObject("victim");
            victim.put("db_id", ke.victimDbId());
            victim.put("entity_id", ke.victimEid());
            victim.put("username", ke.victimName());
            kn.put("cause", ke.cause());
        }
    }

    /** 从 BattleResults JSON 中提取 playersPrivateInfo。 */
    private static Map<String, JsonNode> extractPrivateInfo(JsonNode battleResults, JsonNode constantsJson) {
        Map<String, JsonNode> result = new LinkedHashMap<>();
        for (String key : List.of("playersPrivateInfo", "privateDataList")) {
            JsonNode node = battleResults.get(key);
            if (node != null && node.isObject()) {
                for (var entry : node.properties()) {
                    result.put(entry.getKey(), entry.getValue());
                }
                return result;
            }
            if (node != null && node.isArray()) {
                var indices = constantsJson.get("PLAYER_PRIVATE_RESULTS_INDICES");
                if (indices != null && indices.isObject()) {
                    ObjectNode obj = JsonMapper.createObject();
                    for (var fe : indices.properties()) {
                        int idx = fe.getValue().asInt(-1);
                        if (idx >= 0 && idx < node.size()) {
                            obj.set(fe.getKey(), node.get(idx));
                        }
                    }
                    // 单个播放器的私有信息：用 db_id 0 作为占位
                    result.put("0", obj);
                }
                return result;
            }
        }
        return result;
    }

    /** 使用 constants 解析 BattleResults 中的位置数组为命名对象。 */
    private static JsonNode resolveBattleResults(JsonNode raw, JsonNode constantsJson) {
        // 简单传递 — 完整的解析需要更深层的结构了解
        return raw;
    }

    // ── 小地图输出 ────────────────────────────────────────────────────────

    private MinimapData.MinimapOutput buildMinimapOutput(ReplayFile replay,
                                                         BattleReport report,
                                                         Options options) {
        var minimapSection = report.minimap();
        if (minimapSection == null || minimapSection.frames() == null) return null;

        List<MinimapData.Frame> frames = new ArrayList<>();
        for (var mf : minimapSection.frames()) {
            List<MinimapData.MinimapEntry> entries = new ArrayList<>();
            if (mf.entities() != null) {
                for (var me : mf.entities()) {
                    entries.add(new MinimapData.MinimapEntry(
                            me.entityId(), me.x(), me.y(), me.rotation(),
                            true, 0, false, mf.clock(), me.team(),
                            0f, 0f, true));
                }
            }
            frames.add(new MinimapData.Frame(mf.clock(), entries, List.of(), List.of(),
                    List.of(), List.of(), List.of(), List.of(), List.of(),
                    List.of(), List.of(), null));
        }

        return new MinimapData.MinimapOutput(
                String.valueOf(replay.meta().mapId()), frames,
                List.of(), List.of(), List.of(), List.of(),
                null, null, null, null, List.of());
    }

    /** 将小地图数据添加到 JSON 根节点。 */
    private void appendMinimapToJson(ObjectNode root, MinimapData.MinimapOutput mm,
                                     Integer compressLevel) {
        if (compressLevel != null && compressLevel >= 0 && compressLevel <= 11) {
            root.set("frames", compressField(mm.frames(), compressLevel));
            root.set("firing_events", compressField(mm.firingEvents(), compressLevel));
            root.set("damage_events", compressField(mm.damageEvents(), compressLevel));
            root.set("shot_hits", compressField(mm.shotHits(), compressLevel));
        } else {
            root.set("frames", JsonMapper.toTree(mm.frames()));
            if (mm.firingEvents() != null && !mm.firingEvents().isEmpty())
                root.set("firing_events", JsonMapper.toTree(mm.firingEvents()));
            if (mm.damageEvents() != null && !mm.damageEvents().isEmpty())
                root.set("damage_events", JsonMapper.toTree(mm.damageEvents()));
            if (mm.shotHits() != null && !mm.shotHits().isEmpty())
                root.set("shot_hits", JsonMapper.toTree(mm.shotHits()));
        }
        if (mm.deadShips() != null && !mm.deadShips().isEmpty())
            root.set("dead_ships", JsonMapper.toTree(mm.deadShips()));
        if (mm.battleStage() != null) root.put("battle_stage", mm.battleStage());
        if (mm.winningTeam() != null) root.put("winning_team", mm.winningTeam());
        if (mm.scoringRules() != null) root.set("scoring_rules", JsonMapper.toTree(mm.scoringRules()));
    }

    // ── Brotli 压缩 ───────────────────────────────────────────────────────

    /** 将字段序列化为 JSON，压缩（Brotli 或 GZIP），Base64 编码为 JSON 字符串。 */
    private static tools.jackson.databind.JsonNode compressField(Object value, int quality) {
        try {
            byte[] json = JsonMapper.getMapper().writeValueAsBytes(value);
            byte[] compressed = brotliCompress(json, quality);
            String b64 = Base64.getEncoder().encodeToString(compressed);
            return JsonMapper.createObject().textNode(b64);
        } catch (Exception e) {
            return JsonMapper.toTree(value);
        }
    }

    /** 压缩字节数组（优先 Brotli，不可用时回退 GZIP）。 */
    static byte[] brotliCompress(byte[] data, int quality) throws IOException {
        if (BROTLI_AVAILABLE) {
            return brotliViaReflection(data, quality);
        }
        // 回退：GZIP
        var baos = new ByteArrayOutputStream();
        try (var gos = new GZIPOutputStream(baos)) {
            gos.write(data);
        }
        return baos.toByteArray();
    }

    /** 通过反射调用 Brotli4j 压缩（避免编译时依赖）。 */
    private static byte[] brotliViaReflection(byte[] data, int quality) throws IOException {
        try {
            var loaderClass = Class.forName("com.aayushatharva.brotli4j.Brotli4jLoader");
            loaderClass.getMethod("ensureAvailability").invoke(null);

            var baos = new ByteArrayOutputStream();
            var bosClass = Class.forName("com.aayushatharva.brotli4j.encoder.BrotliOutputStream");
            var constructor = bosClass.getConstructor(java.io.OutputStream.class, int.class);
            try (var bos = (java.io.OutputStream) constructor.newInstance(baos, quality)) {
                bos.write(data);
            }
            return baos.toByteArray();
        } catch (Exception e) {
            // 回退到 GZIP
            var baos = new ByteArrayOutputStream();
            try (var gos = new GZIPOutputStream(baos)) {
                gos.write(data);
            }
            return baos.toByteArray();
        }
    }
}
