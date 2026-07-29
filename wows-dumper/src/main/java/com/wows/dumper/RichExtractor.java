package com.wows.dumper;

import com.wows.replay.core.*;
import com.wows.replay.packets.*;
import com.wows.replay.spec.rpc.ArgValue;
import com.wows.replay.spec.spi.EntitySpecProvider;
import com.wows.replay.spec.types.EntityId;
import com.wows.replay.spec.types.GameClock;
import com.wows.replay.spec.types.Version;
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
final class RichExtractor {

    private final ReplayFile replay;
    private final PacketParser parser;
    private final Version version;
    private final GameClock battleStart;

    // ── 实体→玩家映射 ────────────────────────────────────────────────────
    /** entity_id → (db_id, username) */
    final Map<Integer, PlayerLink> entityToPlayer = new LinkedHashMap<>();

    // ── 实体状态 ──────────────────────────────────────────────────────────
    /** entity_id → entity_type */
    final Map<Integer, String> entityTypes = new HashMap<>();
    /** entity_id → health */
    final Map<Integer, Float> entityHealth = new HashMap<>();
    /** entity_id → maxHealth */
    final Map<Integer, Float> entityMaxHealth = new HashMap<>();
    /** entity_id → teamId */
    final Map<Integer, Integer> entityTeam = new HashMap<>();
    /** entity_id → isAlive */
    final Map<Integer, Boolean> entityAlive = new HashMap<>();
    /** entity_id → isInvisible */
    final Map<Integer, Boolean> entityInvisible = new HashMap<>();
    /** entity_id → position (x, y, z) */
    final Map<Integer, float[]> entityPositions = new HashMap<>();
    /** entity_id → heading (yaw) */
    final Map<Integer, Float> entityHeadings = new HashMap<>();
    /** entity_id → vehicleId */
    final Map<Integer, Long> entityVehicle = new HashMap<>();

    // ── 提取的数据 ────────────────────────────────────────────────────────

    String arenaId;
    String mapName;
    long mapArenaId; // from Map packet

    final List<DamageEvent> damageEvents = new ArrayList<>();
    final List<KillEvent> killEvents = new ArrayList<>();
    final List<ChatEvent> chatEvents = new ArrayList<>();
    final List<ConsumableEvent> consumableEvents = new ArrayList<>();
    final List<ScoreEvent> scoreEvents = new ArrayList<>();
    final List<CapturePointEvent> cpEvents = new ArrayList<>();
    final List<MinimapFrame> minimapFrames = new ArrayList<>();

    final Map<Integer, CpState> cpStates = new HashMap<>(); // cpIndex → state
    final Map<Integer, Long> teamScores = new HashMap<>();   // teamIndex → score

    final List<BuildingInfo> buildings = new ArrayList<>();
    final List<WeatherZoneInfo> weatherZones = new ArrayList<>();
    final List<BuffZoneInfo> buffZones = new ArrayList<>();
    final List<CapturedBuffInfo> capturedBuffs = new ArrayList<>();

    String battleResultsJson;
    String finishType;
    String matchResult;
    Float maxDuration;
    Float playedDuration;
    Float extraDuration;

    // 玩家信息：db_id → (username, entity_id)
    final Map<Long, PlayerInfo> players = new LinkedHashMap<>();

    // 实体规格名称 → 参数ID 映射（用于 resolve_ids）
    final Map<Long, String> paramNames = new HashMap<>();

    // ── 小地图提取 ────────────────────────────────────────────────────────
    int minimapTickCounter = 0;
    final List<MinimapData.Frame> richMinimapFrames = new ArrayList<>();

    RichExtractor(ReplayFile replay, EntitySpecProvider specProvider) {
        this.replay = replay;
        this.version = replay.version();
        this.battleStart = replay.battleStartClock();
        this.parser = new PacketParser(specProvider, version);
    }

    /** 遍历所有数据包，提取全部战斗数据。 */
    void extract() {
        var iter = replay.packetIterator();
        while (iter.hasNext()) {
            var raw = iter.next();
            processPacket(raw);
        }
        // 最后一次遍历后填充终态
        finish();
    }

    private void processPacket(RawPacket raw) {
        float clock = raw.clock().seconds();
        float elapsed = clock - battleStart.seconds();

        var packet = parser.parse(raw);
        if (packet == null) return;

        Object payload = packet.payload();
        if (payload instanceof Packet.InvalidPayload) return;

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
            case EntityLeavePacket el    -> entityAlive.put(el.entityId().value(), false);
            default -> {}
        }
    }

    // ── 数据包处理器 ─────────────────────────────────────────────────────

    private void handleBasePlayerCreate(BasePlayerCreatePacket bp) {
        int eid = bp.entityId().value();
        entityTypes.put(eid, bp.entityType());

        var props = bp.props();
        if (props == null) return;

        // 提取 db_id
        ArgValue dbIdVal = props.get("db_id");
        if (dbIdVal instanceof ArgValue.IntVal iv) {
            long dbId = iv.value();
            String username = "";
            ArgValue nameVal = props.get("username");
            if (nameVal instanceof ArgValue.StrVal sv) username = sv.value();

            entityToPlayer.put(eid, new PlayerLink(dbId, username));
            players.putIfAbsent(dbId, new PlayerInfo(username, eid));
        }
    }

    private void handleCellPlayerCreate(CellPlayerCreatePacket cp) {
        int eid = cp.entityId().value();
        entityTypes.putIfAbsent(eid, cp.entityType());
        entityVehicle.put(eid, cp.vehicleId().value());
    }

    private void handleEntityCreate(EntityCreatePacket ec) {
        int eid = ec.entityId().value();
        String type = ec.entityType();
        entityTypes.put(eid, type);
        entityVehicle.put(eid, ec.vehicleId().value());

        // 记录位置
        if (ec.position() != null) {
            entityPositions.put(eid, new float[]{ec.position().x(), ec.position().y(), ec.position().z()});
        }

        var props = ec.props();
        if (props == null) return;

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
                if (val instanceof ArgValue.FloatVal fv) entityHealth.put(eid, (float) fv.value());
                else if (val instanceof ArgValue.IntVal iv) entityHealth.put(eid, (float) iv.value());
            }
            case "maxHealth" -> {
                if (val instanceof ArgValue.FloatVal fv) entityMaxHealth.put(eid, (float) fv.value());
                else if (val instanceof ArgValue.IntVal iv) entityMaxHealth.put(eid, (float) iv.value());
            }
            case "teamId" -> {
                int tid = intFromArg(val);
                entityTeam.put(eid, tid);
                // 更新玩家信息中的 teamId
                var pl = entityToPlayer.get(eid);
                if (pl != null) {
                    var pi = players.get(pl.dbId);
                    if (pi != null) pi.teamId = tid;
                }
            }
            case "isAlive" -> entityAlive.put(eid, intFromArg(val) != 0);
            case "isInvisible" -> entityInvisible.put(eid, intFromArg(val) != 0);
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

    private void handleEntityMethod(EntityMethodPacket em, float elapsed) {
        int eid = em.entityId().value();
        String method = em.method();
        List<ArgValue> args = em.args();

        switch (method) {
            case "receiveDamagesOnShip" -> {
                if (!args.isEmpty() && args.getFirst() instanceof ArgValue.ArrayVal arr) {
                    for (ArgValue elem : arr.elements()) {
                        if (elem instanceof ArgValue.DictVal dict) {
                            var d = dict.entries();
                            int aggressor = intFromArg(d.get("vehicleID"));
                            float amount = floatFromArg(d.get("damage"));
                            damageEvents.add(new DamageEvent(elapsed, aggressor, eid, amount));
                        }
                    }
                }
            }
            case "receiveVehicleDeath" -> {
                if (args.size() >= 2) {
                    int victim = intFromArg(args.get(0));
                    int killer = intFromArg(args.get(1));
                    int cause = args.size() >= 3 ? intFromArg(args.get(2)) : 0;
                    var kl = entityToPlayer.get(killer);
                    var vl = entityToPlayer.get(victim);
                    killEvents.add(new KillEvent(elapsed, killer, victim,
                        kl != null ? kl.dbId : 0, kl != null ? kl.username : "",
                        vl != null ? vl.dbId : 0, vl != null ? vl.username : "",
                        cause));
                    entityAlive.put(victim, false);
                }
            }
            case "receiveBattleChatMessage" -> {
                if (args.size() >= 4) {
                    String channel = args.get(0) instanceof ArgValue.StrVal sv ? sv.value() : "";
                    String sender = args.get(1) instanceof ArgValue.StrVal sv ? sv.value() : "";
                    String message = args.get(2) instanceof ArgValue.StrVal sv ? sv.value() : "";
                    var pl = entityToPlayer.get(eid);
                    chatEvents.add(new ChatEvent(elapsed, eid,
                        pl != null ? pl.dbId : 0, sender, channel, message));
                }
            }
            case "onConsumableActivated" -> {
                if (!args.isEmpty() && args.getFirst() instanceof ArgValue.DictVal dict) {
                    var d = dict.entries();
                    long consumableId = longFromArg(d.get("consumableId"));
                    float duration = floatFromArg(d.get("duration"));
                    var pl = entityToPlayer.get(eid);
                    consumableEvents.add(new ConsumableEvent(elapsed, eid,
                        pl != null ? pl.dbId : 0, pl != null ? pl.username : "",
                        consumableId, duration));
                }
            }
            case "onBuffCaptured" -> {
                if (args.size() >= 2) {
                    int buffEid = intFromArg(args.get(0));
                    long paramsId = longFromArg(args.get(1));
                    var pl = entityToPlayer.get(eid);
                    capturedBuffs.add(new CapturedBuffInfo(buffEid, paramsId,
                        pl != null ? (int) pl.dbId : 0, elapsed));
                }
            }
            case "receiveTeamScore" -> {
                if (args.size() >= 2) {
                    int teamIdx = intFromArg(args.get(0));
                    long score = longFromArg(args.get(1));
                    teamScores.put(teamIdx, score);
                    scoreEvents.add(new ScoreEvent(elapsed, teamIdx, score));
                }
            }
        }
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
        entityPositions.put(eid, new float[]{pos.position().x(), pos.position().y(), pos.position().z()});
        entityHeadings.put(eid, pos.rotation().yaw());
    }

    // ── 终态处理 ──────────────────────────────────────────────────────────

    private void finish() {
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

    private void extractHealth(Map<String, ArgValue> props, int eid) {
        ArgValue h = props.get("health");
        if (h instanceof ArgValue.FloatVal fv) entityHealth.put(eid, (float) fv.value());
        else if (h instanceof ArgValue.IntVal iv) entityHealth.put(eid, (float) iv.value());

        ArgValue mh = props.get("maxHealth");
        if (mh instanceof ArgValue.FloatVal fv) entityMaxHealth.put(eid, (float) fv.value());
        else if (mh instanceof ArgValue.IntVal iv) entityMaxHealth.put(eid, (float) iv.value());

        ArgValue alive = props.get("isAlive");
        if (alive instanceof ArgValue.IntVal iv) entityAlive.put(eid, iv.value() != 0);
        else if (alive instanceof ArgValue.BoolVal bv) entityAlive.put(eid, bv.value());
    }

    private void extractTeam(Map<String, ArgValue> props, int eid) {
        ArgValue t = props.get("teamId");
        if (t instanceof ArgValue.IntVal iv) {
            entityTeam.put(eid, (int) iv.value());
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

    record PlayerLink(long dbId, String username) {}
    record DamageEvent(float clock, int aggressorId, int victimId, float amount) {}
    record KillEvent(float clock, int killerEid, int victimEid,
                     long killerDbId, String killerName,
                     long victimDbId, String victimName, int cause) {}
    record ChatEvent(float clock, int entityId, long dbId,
                     String senderName, String channel, String message) {}
    record ConsumableEvent(float clock, int entityId, long dbId,
                           String username, long consumableId, float duration) {}
    record MinimapFrame(float clock, List<MinimapEntry> entities) {}
    record MinimapEntry(int entityId, float x, float y, float rotation, int team) {}

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

    record ScoreEvent(float clock, int teamIndex, long score) {}
    record CapturePointEvent(float clock, int index, long teamId, long invaderTeam,
                             Object progress, boolean hasInvaders,
                             boolean bothInside, boolean isEnabled) {}

    record BuildingInfo(int entityId, float x, float z, int teamId, long paramsId, boolean isAlive) {}
    record WeatherZoneInfo(String name, float x, float z, float radius, long paramsId) {}
    record BuffZoneInfo(int entityId, float x, float z, float radius, int teamId, boolean isActive, Long dropParamsId) {}
    record CapturedBuffInfo(int entityId, long paramsId, int capturedBy, float clock) {}
}
