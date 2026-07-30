package com.wows.replay.analyzer;

import com.wows.replay.core.*;
import com.wows.replay.packets.*;
import com.wows.replay.core.rpc.ArgValue;
import com.wows.replay.core.spi.EntitySpecProvider;
import com.wows.replay.packets.NamedArgs;
import com.wows.replay.core.types.EntityId;
import com.wows.replay.core.types.GameClock;
import com.wows.replay.core.types.Version;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.*;
import java.util.function.Function;

/**
 * 回放数据包全量提取器，对标 Rust {@code parse_and_build_report} + 第二遍事件提取。
 *
 * <p>遍历所有已解码的数据包，维护实体状态并提取战斗数据：</p>
 * <ul>
 *   <li>玩家信息（entity_id → db_id, username）</li>
 *   <li>实体属性（health, maxHealth, teamId, isAlive, isInvisible）</li>
 *   <li>伤害事件（receiveDamagesOnShip）</li>
 *   <li>击杀事件（receiveVehicleDeath）</li>
 *   <li>聊天消息</li>
 *   <li>团队分数变化</li>
 *   <li>占领点状态变化</li>
 *   <li>消耗品使用</li>
 *   <li>建筑、天气、增益区域</li>
 *   <li>BattleResults JSON</li>
 *   <li>小地图位置帧</li>
 * </ul>
 */
@Slf4j
public final class RichExtractor {

    private final ReplayFile replay;
    private final PacketParser parser;
    private final Version version;
    private final GameClock battleStart;

    // ── 实体状态（聚合） ──────────────────────────────────────────────────
    /** entity_id → EntityState */
    public final Map<Integer, EntityState> entities = new HashMap<>();

    /** entity_id → (db_id, username) */
    public final Map<Integer, PlayerLink> entityToPlayer = new LinkedHashMap<>();

    // ── 提取的数据 ────────────────────────────────────────────────────────

    public String arenaId;
    public String mapName;
    public long mapArenaId; // from Map packet

    public final List<DamageEvent> damageEvents = new ArrayList<>();
    public final List<KillEvent> killEvents = new ArrayList<>();
    public final List<ChatEvent> chatEvents = new ArrayList<>();
    public final List<ConsumableEvent> consumableEvents = new ArrayList<>();
    public final List<ScoreEvent> scoreEvents = new ArrayList<>();
    public final List<CapturePointEvent> cpEvents = new ArrayList<>();
    public final List<MinimapFrame> minimapFrames = new ArrayList<>();

    public final Map<Integer, CpState> cpStates = new HashMap<>(); // cpIndex → state
    public final Map<Integer, Long> teamScores = new HashMap<>();   // teamIndex → score

    public final List<BuildingInfo> buildings = new ArrayList<>();
    public final List<WeatherZoneInfo> weatherZones = new ArrayList<>();
    public final List<BuffZoneInfo> buffZones = new ArrayList<>();
    public final List<CapturedBuffInfo> capturedBuffs = new ArrayList<>();

    public String battleResultsJson;
    public String finishType;
    public String matchResult;
    public Float maxDuration;
    public Float playedDuration;
    public Float extraDuration;

    // 玩家信息：db_id → (username, entity_id)
    public final Map<Long, PlayerInfo> players = new LinkedHashMap<>();

    // 实体规格名称 → 参数ID 映射（用于 resolve_ids）
    public final Map<Long, String> paramNames = new HashMap<>();

    // ── 小地图提取 ────────────────────────────────────────────────────────
    int minimapTickCounter = 0;
    final List<MinimapData.Frame> richMinimapFrames = new ArrayList<>();

    /** meta.vehicles[i] → (dbId, name, relation, shipId) */
    public record MetaPlayer(long dbId, String name, int relation, long shipId, long metaShipId) {}
    public final List<MetaPlayer> metaPlayers = new ArrayList<>();
    private int cellPlayerCreateCount = 0;

    public RichExtractor(ReplayFile replay, EntitySpecProvider specProvider) {
        this.replay = replay;
        this.version = replay.version();
        this.battleStart = replay.battleStartClock();
        this.parser = new PacketParser(specProvider, version);

        // 从回放元数据预加载所有玩家信息并预填充到 players
        var vehicles = replay.meta().vehicles();
        if (vehicles != null) {
            for (var v : vehicles) {
                long dbId = v.id().value();
                String name = v.name();
                metaPlayers.add(new MetaPlayer(dbId, name, v.relation(), v.shipId().value(), dbId));
                // 预填充所有玩家(实体ID暂时为0)
                players.put(dbId, new PlayerInfo(name, 0));
            }
        }
        log.info("元数据玩家: {}", metaPlayers.size());

        int specCount = this.parser.specs() != null ? this.parser.specs().size() : 0;
        if (specCount == 0) {
            log.warn("实体规范为空 — 玩家/事件/占点等数据将无法提取");
        } else {
            log.info("已加载 {} 个实体规范", specCount);
        }
    }

    /** 遍历所有数据包，提取全部战斗数据。 */
    public void extract() {
        int totalPackets = 0;
        int decodedPackets = 0;
        var iter = replay.packetIterator();
        while (iter.hasNext()) {
            var raw = iter.next();
            totalPackets++;
            if (processPacket(raw)) decodedPackets++;
        }
        finish();
        log.info("CellPlayerCreate={} VehicleCreate={} metaPlayers={} vehicleToOwner={}",
            cellPlayerCreateCount, vehicleCreateCount, metaPlayers.size(), vehicleToOwner.size());
        log.info("所有 EntityMethod({}): {}", seenMethods.size(), seenMethods);
        log.info("数据包: {} 总计, {} 已解码, {} 玩家, {} 击杀, {} 伤害, {} 消耗品, {} 聊天",
            totalPackets, decodedPackets, players.size(),
            killEvents.size(), damageEvents.size(), consumableEvents.size(), chatEvents.size());
    }

    /** @return true if packet was decoded (not unknown/invalid) */
    private boolean processPacket(RawPacket raw) {
        float clock = raw.clock().seconds();
        float elapsed = clock - battleStart.seconds();

        var packet = parser.parse(raw);
        if (packet == null) return false;

        Object payload = packet.payload();
        if (payload instanceof Packet.InvalidPayload) return false;
        if (packet.packetType() == null) return false; // unknown type

        switch (payload) {
            case BasePlayerCreatePacket bp -> handleBasePlayerCreate(bp);
            case CellPlayerCreatePacket cp -> handleCellPlayerCreate(cp);
            case EntityCreatePacket ec   -> handleEntityCreate(ec);
            case EntityPropertyPacket ep -> handleEntityProperty(ep, elapsed);
            case EntityMethodPacket em   -> handleEntityMethod(em, elapsed);
            case MapPacket mp            -> handleMap(mp);
            case BattleResultsPacket br  -> battleResultsJson = br.json();
            case PositionPacket pos      -> handlePosition(pos, elapsed, raw);
            case EntityEnterPacket ee    -> { /* entity_id → space → vehicle */ }
            case EntityLeavePacket el    -> getOrCreate(el.entityId().value(), null).isAlive = false;
            default -> { return false; }
        }
        return true;
    }

    // ── 数据包处理器 ─────────────────────────────────────────────────────

    private final Set<String> bpEntityTypes = new LinkedHashSet<>();
    private boolean componentDataDumped = false;

    private void handleBasePlayerCreate(BasePlayerCreatePacket bp) {
        int eid = bp.entityId().value();
        String entityType = bp.entityType();
        getOrCreate(eid, entityType);

        if (entityType != null) bpEntityTypes.add(entityType);

        var props = bp.props();
        if (props != null && !props.isEmpty()) {
            log.debug("BasePlayerCreate({}) eid={} 属性: {}", entityType, eid, props.keySet());
        }

        // dump componentData 前200字节用于分析 pickle 格式
        byte[] compData = bp.componentData();
        if (!componentDataDumped && compData != null && compData.length > 0) {
            componentDataDumped = true;
            int dumpLen = Math.min(compData.length, 200);
            var hex = new StringBuilder();
            for (int i = 0; i < dumpLen; i++) {
                hex.append(String.format("%02x ", compData[i] & 0xFF));
                if ((i + 1) % 32 == 0) hex.append('\n');
            }
            log.debug("BasePlayerCreate({}) componentData[{}] 前{}字节:\n{}",
                entityType, compData.length, dumpLen, hex);
        }

        // 尝试多种可能的 db_id 属性名
        if (props != null) {
            ArgValue dbIdVal = findProp(props, "db_id", "databaseID", "dbid", "playerID");
            if (dbIdVal instanceof ArgValue.IntVal iv) {
                long dbId = iv.value();
                ArgValue nameVal = findProp(props, "username", "name", "playerName");
                String username = nameVal instanceof ArgValue.StrVal sv ? sv.value() : "";

                entityToPlayer.put(eid, new PlayerLink(dbId, username));
                players.putIfAbsent(dbId, new PlayerInfo(username, eid));
            }
        }
    }

    /** 按优先级查找属性值 */
    private static ArgValue findProp(Map<String, ArgValue> props, String... names) {
        for (var name : names) {
            var v = props.get(name);
            if (v != null) return v;
        }
        return null;
    }

    private final Set<String> cpEntityTypes = new LinkedHashSet<>();

    private void handleCellPlayerCreate(CellPlayerCreatePacket cp) {
        int eid = cp.entityId().value();
        String entityType = cp.entityType();
        getOrCreate(eid, entityType);
        long vehicleId = cp.vehicleId().value();
        getOrCreate(eid, null).vehicleId = vehicleId;

        if (entityType != null) cpEntityTypes.add(entityType);

        // CellPlayerCreate(Avatar) 对应录制玩家 (relation=0)
        if ("Avatar".equals(entityType)) {
            cellPlayerCreateCount++;
            for (var mp : metaPlayers) {
                if (mp.relation == 0) {
                    var link = new PlayerLink(mp.dbId, mp.name);
                    entityToPlayer.put(eid, link);
                    players.put(mp.dbId, new PlayerInfo(mp.name, eid));
                    log.info("录制玩家: eid={} db_id={} name={}", eid, mp.dbId, mp.name);
                    break;
                }
            }
        }

        // 输出属性用于诊断
        var props = cp.props();
        if (props != null && !props.isEmpty() && cpEntityTypes.size() <= 3) {
            log.debug("CellPlayerCreate({}) eid={} vehicle={} 属性: {}",
                entityType, eid, vehicleId, props.keySet());
        }
    }

    private final Set<String> entityCreateTypes = new LinkedHashSet<>();
    private int vehicleCreateCount = 0;

    private void handleEntityCreate(EntityCreatePacket ec) {
        int eid = ec.entityId().value();
        String type = ec.entityType();
        getOrCreate(eid, type);
        getOrCreate(eid, null).vehicleId = ec.vehicleId().value();

        if (type != null) entityCreateTypes.add(type);

        // Vehicle owner → Avatar entity_id 映射
        if ("Vehicle".equals(type)) {
            vehicleCreateCount++;
            ArgValue owner = ec.props().get("owner");
            if (owner instanceof ArgValue.IntVal iv) {
                int ownerEid = (int) iv.value();
                vehicleToOwner.put(eid, ownerEid); // Vehicle eid → Avatar eid
                getOrCreate(ownerEid, "Avatar");
            }
        }

        // 记录位置
        if (ec.position() != null) {
            var es = getOrCreate(eid, null);
            es.x = ec.position().x();
            es.y = ec.position().y();
            es.z = ec.position().z();
        }

        var props = ec.props();
        if (props == null) return;

        // 输出前几个不同类型的属性
        if (!props.isEmpty() && entityCreateTypes.add("logged:" + type)) {
            log.debug("EntityCreate({}) 属性: {}", type, props.keySet());
        }

        // 提取 health / maxHealth / teamId / isAlive
        extractHealth(props, eid);
        extractTeam(props, eid);

        // 按实体类型分类
        switch (type) {
            case "Avatar" -> {
                // 玩家舰船 — 已在 BasePlayerCreate 中处理
            }
            case "BattleLogic" -> {
                // 提取初始团队分数
                ArgValue state = props.get("state");
                if (state instanceof ArgValue.DictVal sd) {
                    ArgValue missions = sd.entries().get("missions");
                    if (missions instanceof ArgValue.DictVal md) {
                        ArgValue ts = md.entries().get("teamsScore");
                        if (ts instanceof ArgValue.ArrayVal arr) {
                            for (int i = 0; i < arr.elements().size(); i++) {
                                ArgValue entry = arr.elements().get(i);
                                if (entry instanceof ArgValue.DictVal ed) {
                                    ArgValue score = ed.entries().get("score");
                                    if (score instanceof ArgValue.IntVal sv) {
                                        teamScores.put(i, sv.value());
                                        scoreEvents.add(new ScoreEvent(0f, i, sv.value()));
                                    }
                                }
                            }
                        }
                    }
                }
            }
            case "InteractiveZone" -> {
                // 占领点
                ArgValue cs = props.get("componentsState");
                if (cs instanceof ArgValue.DictVal csd) {
                    ArgValue cp = csd.entries().get("controlPoint");
                    if (cp instanceof ArgValue.DictVal cpd) {
                        ArgValue idxVal = cpd.entries().get("index");
                        if (idxVal instanceof ArgValue.IntVal iv) {
                            int cpIdx = (int) iv.value();
                            CpState state = new CpState();
                            ArgValue teamId = props.get("teamId");
                            if (teamId instanceof ArgValue.IntVal tv) state.teamId = tv.value();

                            ArgValue cl = csd.entries().get("captureLogic");
                            if (cl instanceof ArgValue.DictVal cld) {
                                applyCpDict(state, cld.entries());
                            }
                            cpStates.put(cpIdx, state);
                            cpEvents.add(stateToEvent(0f, cpIdx, state));
                        }
                    }
                }
            }
            case "Building" -> {
                float[] pos = ec.position() != null ? new float[]{ec.position().x(), ec.position().z()} : new float[]{0, 0};
                int teamId = getIntProp(props, "teamId");
                buildings.add(new BuildingInfo(eid, pos[0], pos[1], teamId, ec.vehicleId().value(), true));
            }
            case "WeatherZone", "LocalWeatherZone" -> {
                float[] pos = ec.position() != null ? new float[]{ec.position().x(), ec.position().z()} : new float[]{0, 0};
                float radius = getFloatProp(props, "radius");
                weatherZones.add(new WeatherZoneInfo(type, pos[0], pos[1], radius, ec.vehicleId().value()));
            }
            case "BuffZone" -> {
                float[] pos = ec.position() != null ? new float[]{ec.position().x(), ec.position().z()} : new float[]{0, 0};
                float radius = getFloatProp(props, "radius");
                int teamId = getIntProp(props, "teamId");
                boolean active = getBoolProp(props, "isActive");
                Long dropId = getOptionalLongProp(props, "dropParamsId");
                buffZones.add(new BuffZoneInfo(eid, pos[0], pos[1], radius, teamId, active, dropId));
            }
            default -> {}
        }
    }

    private void handleEntityProperty(EntityPropertyPacket ep, float elapsed) {
        int eid = ep.entityId().value();
        String prop = ep.property();
        ArgValue val = ep.value();

        switch (prop) {
            case "health" -> {
                if (val instanceof ArgValue.FloatVal fv) getOrCreate(eid, null).health = (float) fv.value();
                else if (val instanceof ArgValue.IntVal iv) getOrCreate(eid, null).health = (float) iv.value();
            }
            case "maxHealth" -> {
                if (val instanceof ArgValue.FloatVal fv) getOrCreate(eid, null).maxHealth = (float) fv.value();
                else if (val instanceof ArgValue.IntVal iv) getOrCreate(eid, null).maxHealth = (float) iv.value();
            }
            case "teamId" -> {
                int tid = intFromArg(val);
                getOrCreate(eid, null).teamId = tid;
                // 更新玩家信息中的 teamId
                var pl = entityToPlayer.get(eid);
                if (pl != null) {
                    var pi = players.get(pl.dbId);
                    if (pi != null) pi.teamId = tid;
                }
            }
            case "isAlive" -> getOrCreate(eid, null).isAlive = intFromArg(val) != 0;
            case "isInvisible" -> getOrCreate(eid, null).isInvisible = intFromArg(val) != 0;
            case "maxDuration" -> {
                if (val instanceof ArgValue.FloatVal fv) maxDuration = (float) fv.value();
            }
            case "playedDuration" -> {
                if (val instanceof ArgValue.FloatVal fv) playedDuration = (float) fv.value();
            }
            case "extraDuration" -> {
                if (val instanceof ArgValue.FloatVal fv) extraDuration = (float) fv.value();
            }
            case "finishType" -> {
                if (val instanceof ArgValue.StrVal sv) finishType = sv.value();
            }
            case "matchResult" -> {
                if (val instanceof ArgValue.StrVal sv) matchResult = sv.value();
            }
        }
    }

    private final Set<String> seenMethods = new LinkedHashSet<>();
    /** Vehicle entity_id → Avatar entity_id (owner) */
    private final Map<Integer, Integer> vehicleToOwner = new HashMap<>();

    /** Event → handler. 解码层 {@link DecodedEvent#decode} 已在调用前完成所有 arg 解析。 */
    @FunctionalInterface
    private interface EventHandler {
        void handle(int eid, DecodedEvent event, float elapsed);
    }

    private final Map<String, EventHandler> eventHandlers = new LinkedHashMap<>();
    {
        eventHandlers.put("receiveDamagesOnShip",      this::handleDamage);
        eventHandlers.put("receiveVehicleDeath",       this::handleKill);
        eventHandlers.put("onChatMessage",              this::handleChat);
        eventHandlers.put("onConsumableUsed",           this::handleConsumable);
        eventHandlers.put("onArenaStateReceived",       this::handleArenaState);
        eventHandlers.put("onNewPlayerSpawnedInBattle", this::handleNewPlayer);
    }

    private void handleEntityMethod(EntityMethodPacket em, float elapsed) {
        int eid = em.entityId().value();
        String method = em.method();
        seenMethods.add(method);

        var handler = eventHandlers.get(method);
        if (handler != null) {
            var event = DecodedEvent.decode(method, em.args());
            if (event != null) {
                handler.handle(eid, event, elapsed);
            }
        }
    }

    // ── 事件处理器（纯业务逻辑，已由 DecodedEvent.decode 完成参数解析） ──

    private void handleDamage(int eid, DecodedEvent event, float elapsed) {
        if (event instanceof DecodedEvent.DamageStat ds) {
            for (var e : ds.entries()) {
                int aggressorAv = vehicleToOwner.getOrDefault(e.aggressorEntityId(), e.aggressorEntityId());
                damageEvents.add(new DamageEvent(elapsed, aggressorAv, eid, e.amount()));
            }
        }
    }

    private void handleKill(int eid, DecodedEvent event, float elapsed) {
        if (event instanceof DecodedEvent.ShipDestroyed sd) {
            int victimAv = vehicleToOwner.getOrDefault(sd.victimEntityId(), sd.victimEntityId());
            int killerAv = vehicleToOwner.getOrDefault(sd.killerEntityId(), sd.killerEntityId());
            var kl = entityToPlayer.get(killerAv);
            var vl = entityToPlayer.get(victimAv);
            killEvents.add(new KillEvent(elapsed, killerAv, victimAv,
                kl != null ? kl.dbId : 0, kl != null ? kl.username : "",
                vl != null ? vl.dbId : 0, vl != null ? vl.username : "", sd.cause()));
            getOrCreate(sd.victimEntityId(), null).isAlive = false;
        }
    }

    private void handleChat(int eid, DecodedEvent event, float elapsed) {
        if (event instanceof DecodedEvent.ChatMessage cm) {
            var pl = entityToPlayer.get(eid);
            chatEvents.add(new ChatEvent(elapsed, eid,
                pl != null ? pl.dbId : 0,
                String.valueOf(cm.senderId()), cm.channel(), cm.message()));
        }
    }

    private void handleConsumable(int eid, DecodedEvent event, float elapsed) {
        if (event instanceof DecodedEvent.ConsumableUsed cu) {
            var pl = entityToPlayer.get(eid);
            consumableEvents.add(new ConsumableEvent(elapsed, eid,
                pl != null ? pl.dbId : 0, pl != null ? pl.username : "",
                cu.consumableId(), cu.duration()));
        }
    }

    private void handleArenaState(int eid, DecodedEvent event, float elapsed) {
        if (event instanceof DecodedEvent.ArenaState as) {
            applyArenaPlayers(as.playersBlob());
        }
    }

    private void handleNewPlayer(int eid, DecodedEvent event, float elapsed) {
        if (event instanceof DecodedEvent.PlayerSpawned ps) {
            applyArenaPlayers(ps.playersBlob());
        }
    }

    /** 应用 arena state pickle 数据更新玩家映射 */
    private void applyArenaPlayers(byte[] pickledBlob) {
        var arenaPlayers = PickleDecoder.parseArenaPlayers(pickledBlob);
        int updated = 0;
        for (var e : arenaPlayers.entityToDbId().entrySet()) {
            int entityId = e.getKey();
            long dbId = e.getValue();
            String name = arenaPlayers.dbIdToName().getOrDefault(dbId, "");
            int team = arenaPlayers.dbIdToTeam().getOrDefault(dbId, -1);

            entityToPlayer.put(entityId, new PlayerLink(dbId, name));
            var existing = players.get(dbId);
            if (existing != null) {
                existing.entityId = entityId;
                existing.teamId = team;
                updated++;
            } else {
                players.put(dbId, new PlayerInfo(name, entityId));
                updated++;
            }
        }
        log.info("ArenaState: 解析 {} 玩家, 更新 {} / 总玩家 {}",
            arenaPlayers.entityToDbId().size(), updated, players.size());
    }

    private void handleMap(MapPacket mp) {
        arenaId = String.valueOf(mp.arenaId());
        mapArenaId = mp.arenaId();
        mapName = mp.mapName();
    }

    /** 提取小地图位置帧（与现有 ReplayAnalyzer 兼容）。 */
    void extractMinimapFrame(RawPacket raw, float clock) {
        try {
            var buf = java.nio.ByteBuffer.wrap(raw.payload()).order(java.nio.ByteOrder.LITTLE_ENDIAN);
            if (buf.remaining() < 41) return;

            int entityId = buf.getInt();
            buf.getInt(); // spaceId
            float x = buf.getFloat();
            float y = buf.getFloat();
            buf.getFloat(); // z
            buf.getFloat(); buf.getFloat(); buf.getFloat(); // direction
            float yaw = buf.getFloat(); // rotation.yaw
            float z = buf.getFloat(); // rotation.z (next 4 bytes after yaw)

            var entry = new MinimapEntry(entityId, x, y, yaw, 0);
            minimapFrames.add(new MinimapFrame(clock, List.of(entry)));
        } catch (Exception ignored) {}
    }

    private void handlePosition(PositionPacket pos, float elapsed, RawPacket raw) {
        int eid = pos.entityId().value();
        var es = getOrCreate(eid, null);
        es.x = pos.position().x();
        es.y = pos.position().y();
        es.z = pos.position().z();
        es.heading = pos.rotation().yaw();
    }

    // ── 终态处理 ──────────────────────────────────────────────────────────

    private void finish() {
        // 输出诊断摘要
        log.info("EntityTypes: BasePlayerCreate={}, CellPlayerCreate={}, EntityCreate={}",
            bpEntityTypes, cpEntityTypes, entityCreateTypes);

        // 从 BattleResults 中提取 matchResult / finishType（如果尚未设置）
        if (battleResultsJson != null && (matchResult == null || finishType == null)) {
            try {
                var node = JsonMapper.readTree(battleResultsJson);
                if (matchResult == null && node.has("matchResult")) {
                    matchResult = node.get("matchResult").asText();
                }
                if (finishType == null && node.has("finishReason")) {
                    finishType = node.get("finishReason").asText();
                }
            } catch (Exception ignored) {}
        }
    }

    // ── 帮助方法 ──────────────────────────────────────────────────────────

    /** Get or create entity state, optionally setting its type. */
    private EntityState getOrCreate(int eid, String type) {
        var e = entities.get(eid);
        if (e == null) {
            e = new EntityState(eid, type != null ? type : "Unknown");
            entities.put(eid, e);
        } else if (type != null && "Unknown".equals(e.type)) {
            e.type = type;
        }
        return e;
    }

    private void extractHealth(Map<String, ArgValue> props, int eid) {
        ArgValue h = props.get("health");
        if (h instanceof ArgValue.FloatVal fv) getOrCreate(eid, null).health = (float) fv.value();
        else if (h instanceof ArgValue.IntVal iv) getOrCreate(eid, null).health = (float) iv.value();

        ArgValue mh = props.get("maxHealth");
        if (mh instanceof ArgValue.FloatVal fv) getOrCreate(eid, null).maxHealth = (float) fv.value();
        else if (mh instanceof ArgValue.IntVal iv) getOrCreate(eid, null).maxHealth = (float) iv.value();

        ArgValue alive = props.get("isAlive");
        if (alive instanceof ArgValue.IntVal iv) getOrCreate(eid, null).isAlive = iv.value() != 0;
        else if (alive instanceof ArgValue.BoolVal bv) getOrCreate(eid, null).isAlive = bv.value();
    }

    private void extractTeam(Map<String, ArgValue> props, int eid) {
        ArgValue t = props.get("teamId");
        if (t instanceof ArgValue.IntVal iv) {
            getOrCreate(eid, null).teamId = (int) iv.value();
        }
    }

    private void applyCpDict(CpState state, Map<String, ArgValue> dict) {
        ArgValue v;
        v = dict.get("hasInvaders");
        if (v instanceof ArgValue.IntVal iv) state.hasInvaders = iv.value() != 0;
        v = dict.get("invaderTeam");
        if (v instanceof ArgValue.IntVal iv) state.invaderTeam = iv.value();
        v = dict.get("progress");
        if (v instanceof ArgValue.FloatVal fv) state.progress = fv.value();
        else if (v instanceof ArgValue.ArrayVal av && av.elements().size() >= 2) {
            state.progress = List.of(floatFromArg(av.elements().get(0)), floatFromArg(av.elements().get(1)));
        }
        v = dict.get("bothInside");
        if (v instanceof ArgValue.IntVal iv) state.bothInside = iv.value() != 0;
        v = dict.get("isEnabled");
        if (v instanceof ArgValue.IntVal iv) state.isEnabled = iv.value() != 0;
    }

    private CapturePointEvent stateToEvent(float clock, int idx, CpState s) {
        return new CapturePointEvent(clock, idx, s.teamId, s.invaderTeam,
            s.progress, s.hasInvaders, s.bothInside, s.isEnabled);
    }

    static int intFromArg(ArgValue v) {
        return switch (v) {
            case ArgValue.IntVal iv -> (int) iv.value();
            case ArgValue.FloatVal fv -> (int) fv.value();
            case ArgValue.BoolVal bv -> bv.value() ? 1 : 0;
            default -> 0;
        };
    }

    static long longFromArg(ArgValue v) {
        return switch (v) {
            case ArgValue.IntVal iv -> iv.value();
            case ArgValue.FloatVal fv -> (long) fv.value();
            default -> 0;
        };
    }

    static float floatFromArg(ArgValue v) {
        return switch (v) {
            case ArgValue.FloatVal fv -> (float) fv.value();
            case ArgValue.IntVal iv -> (float) iv.value();
            default -> 0f;
        };
    }

    static int getIntProp(Map<String, ArgValue> props, String key) {
        ArgValue v = props.get(key);
        return v != null ? intFromArg(v) : 0;
    }

    static float getFloatProp(Map<String, ArgValue> props, String key) {
        ArgValue v = props.get(key);
        return v != null ? floatFromArg(v) : 0f;
    }

    static boolean getBoolProp(Map<String, ArgValue> props, String key) {
        ArgValue v = props.get(key);
        if (v instanceof ArgValue.BoolVal bv) return bv.value();
        if (v instanceof ArgValue.IntVal iv) return iv.value() != 0;
        return false;
    }

    static Long getOptionalLongProp(Map<String, ArgValue> props, String key) {
        ArgValue v = props.get(key);
        if (v instanceof ArgValue.IntVal iv) return iv.value();
        return null;
    }

    // ── 内部类型 ──────────────────────────────────────────────────────────

    public record PlayerLink(long dbId, String username) {}
    public record DamageEvent(float clock, int aggressorId, int victimId, float amount) {}
    public record KillEvent(float clock, int killerEid, int victimEid,
                     long killerDbId, String killerName,
                     long victimDbId, String victimName, int cause) {}
    public record ChatEvent(float clock, int entityId, long dbId,
                     String senderName, String channel, String message) {}
    public record ConsumableEvent(float clock, int entityId, long dbId,
                           String username, long consumableId, float duration) {}
    public record MinimapFrame(float clock, List<MinimapEntry> entities) {}
    public record MinimapEntry(int entityId, float x, float y, float rotation, int team) {}

    public static class PlayerInfo {
        public String username;
        public int entityId;
        public int teamId = -1;
        public PlayerInfo(String u, int e) { username = u; entityId = e; }
    }

    public static class CpState {
        public long teamId = -1, invaderTeam = -1;
        public Object progress = 0.0;
        public boolean hasInvaders, bothInside, isEnabled = true;
    }

    public record ScoreEvent(float clock, int teamIndex, long score) {}
    public record CapturePointEvent(float clock, int index, long teamId, long invaderTeam,
                             Object progress, boolean hasInvaders,
                             boolean bothInside, boolean isEnabled) {}

    public record BuildingInfo(int entityId, float x, float z, int teamId, long paramsId, boolean isAlive) {}
    public record WeatherZoneInfo(String name, float x, float z, float radius, long paramsId) {}
    public record BuffZoneInfo(int entityId, float x, float z, float radius, int teamId, boolean isActive, Long dropParamsId) {}
    public record CapturedBuffInfo(int entityId, long paramsId, int capturedBy, float clock) {}
}
