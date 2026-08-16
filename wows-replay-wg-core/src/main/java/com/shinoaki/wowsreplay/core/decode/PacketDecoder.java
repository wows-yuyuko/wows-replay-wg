package com.shinoaki.wowsreplay.core.decode;

import com.shinoaki.wowsreplay.core.constant.GameConstants;
import com.shinoaki.wowsreplay.core.model.*;
import com.shinoaki.wowsreplay.core.packet.*;
import com.shinoaki.wowsreplay.core.pickle.PickleReader;
import com.shinoaki.wowsreplay.core.types.ArgValue;
import lombok.extern.slf4j.Slf4j;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Layer-2 packet decoder — converts {@link Parser} output into
 * semantically meaningful {@link DecodedPayload} variants.
 *
 * <p>Mirrors Rust {@code PacketDecoder}. Handles EntityMethod → domain event
 * conversion, pickle blob parsing, and version-dependent field layouts.</p>
 */
@Slf4j
public class PacketDecoder {

    private final Version version;
    /** 统一常量布局管理器（VoiceLine id→名称等），见 {@link GameConstants}。 */
    private final GameConstants gameConstants;

    public PacketDecoder(Version version) {
        this.version = version;
        this.gameConstants = GameConstants.empty();
    }

    /**
     * Decode a parsed {@link Packet} into a {@link DecodedPayload}.
     */
    public DecodedPayload decode(Packet packet) {
        if (packet == null) {
            return new DecodedPayload.InvalidPayload("null or invalid packet");
        }
        Object payload = packet.payload();
        return switch (payload) {
            case EntityMethodPacket em -> decodeEntityMethod(em);
            case EntityCreatePacket ec -> new DecodedPayload.EntityCreatePayload(ec);
            case BasePlayerCreatePacket bp -> new DecodedPayload.BasePlayerCreatePayload(bp);
            case CellPlayerCreatePacket cp -> new DecodedPayload.CellPlayerCreatePayload(cp);
            case EntityEnterPacket ee -> new DecodedPayload.EntityEnterPayload(ee);
            case EntityLeavePacket el -> new DecodedPayload.EntityLeavePayload(el);
            case EntityPropertyPacket ep -> PropertyDecoder.decode(ep)
                    .<DecodedPayload>map(DecodedPayload.PropertyChangePayload::new)
                    .orElseGet(() -> new DecodedPayload.InvalidPayload("unrecognized property: " + ep.property()));
            case EntityControlPacket ec2 -> new DecodedPayload.EntityControlPayload(ec2);
            case PositionPacket pos -> new DecodedPayload.PositionPayload(pos);
            case PlayerOrientationPacket po -> new DecodedPayload.PlayerOrientationPayload(po);
            case NonVolatilePositionPacket nvp -> new DecodedPayload.NonVolatilePositionPayload(nvp);
            case PropertyUpdatePacket pu -> new DecodedPayload.PropertyUpdatePayload(pu);
            case MapPacket mp -> new DecodedPayload.MapPayload(mp);
            case VersionPacket vp -> new DecodedPayload.VersionPayload(vp.version());
            case CameraPacket cp2 -> new DecodedPayload.CameraPayload(cp2);
            case CameraModePacket cm -> new DecodedPayload.CameraModePayload(cm.mode());
            case CameraFreeLookPacket cfl -> new DecodedPayload.CameraFreeLookPayload(cfl.freeLook() != 0);
            case CruiseStatePacket cs -> new DecodedPayload.CruiseStatePayload(cs.key(), cs.value());
            case OwnShipPacket os -> new DecodedPayload.OwnShipPayload(os);
            case SetWeaponLockPacket swl -> new DecodedPayload.SetWeaponLockPayload(swl);
            case ServerTimestampPacket st -> new DecodedPayload.ServerTimestampPayload(st.timestamp());
            case ServerTickPacket st2 -> new DecodedPayload.ServerTickPayload(st2.tickRate());
            case SubControllerPacket sc -> new DecodedPayload.SubControllerPayload(sc);
            case ShotTrackingPacket st3 -> new DecodedPayload.ShotTrackingPayload(st3);
            case GunMarkerPacket gm -> new DecodedPayload.GunMarkerPayload(gm);
            case PlayerNetStatsPacket pns -> new DecodedPayload.PlayerNetStatsPayload(pns);
            case InitFlagPacket iff -> new DecodedPayload.InitFlagPayload(iff.flag());
            case BattleResultsPacket br -> new DecodedPayload.BattleResultsPayload(br.json());
            case Packet.InvalidPayload inv -> new DecodedPayload.InvalidPayload(inv.error());
            case null, default -> {
                if (payload instanceof String s && s.equals("init_marker"))
                    yield new DecodedPayload.InitMarkerPayload();
                yield new DecodedPayload.UnknownPayload(new byte[0]);
            }
        };
    }

    // ── EntityMethod decoding ──────────────────────────────────────────

    private DecodedPayload decodeEntityMethod(EntityMethodPacket em) {
        String method = em.method();
        NamedArgs args = em.args();

        return switch (method) {
            case "onChatMessage" -> decodeChat(em.entityId(), args);
            case "receive_CommonCMD" -> decodeVoiceLine(args);
            case "onArenaStateReceived",
                 "onWorldStateReceived" -> decodeArenaState(args);
            case "onGameRoomStateChanged" -> decodeGameRoomStateChanged(args);
            case "onNewPlayerSpawnedInBattle" -> decodeNewPlayerSpawned(args);
            case "receiveDamagesOnShip" -> decodeDamageReceived(em.entityId(), args);
            case "receiveDamageReport" -> decodeDamageReceived(em.entityId(), args);
            case "receiveVehicleDeath" -> decodeShipDestroyed(args);
            case "onConsumableUsed" -> decodeConsumable(em.entityId(), args);
            case "squadronConsumableUsed" -> decodeSquadronConsumable(em.entityId(), args);
            case "receiveDamageStat" -> decodeDamageStat(args);
            case "onBattleEnd" -> decodeBattleEnd(args);
            case "onShotFired",
                 "receiveArtilleryShots" -> decodeArtilleryShots(em.entityId(), args);
            case "receiveTorpedoes" -> decodeTorpedoes(em.entityId(), args);
            case "receiveShotKills" -> decodeShotKills(em.entityId(), args);
            case "receive_wardAdded" -> decodeWardAdded(em.entityId(), args);
            case "receive_wardRemoved" -> decodeWardRemoved(em.entityId(), args);
            case "onPlaneAdded",
                 "receive_addMinimapSquadron" -> decodePlaneAdded(em.entityId(), args);
            case "onPlaneRemoved",
                 "receive_removeMinimapSquadron" -> decodePlaneRemoved(em.entityId(), args);
            case "onPlanePosition",
                 "receive_updateMinimapSquadron" -> decodePlanePosition(em.entityId(), args);
            case "receive_addSquadron" -> decodeSquadronAdded(args);
            case "receive_squadronHealth" -> decodeSquadronHealth(args);
            case "receive_squadronPlanesHealth" -> decodeSquadronPlanesHealth(args);
            case "onGunSync", "syncGun" -> decodeGunSync(em.entityId(), args);
            case "onSetAmmoForWeapon",
                 "setAmmoForWeapon" -> decodeSetAmmo(em.entityId(), args);
            case "receiveTorpedoDirection" -> decodeTorpedoDirection(args);
            case "onRibbon" -> decodeRibbon(args);
            case "updateMinimapVisionInfo" -> decodeMinimapVision(args);
            case "syncShipCracks" -> new DecodedPayload.EntityMethodPayload(em);
            default -> new DecodedPayload.EntityMethodPayload(em);
        };
    }

    // ── Chat ───────────────────────────────────────────────────────────

    private DecodedPayload decodeChat(EntityId entityId, NamedArgs args) {
        int senderId = (int) Integer.toUnsignedLong(intFromArg(args.get(0)));
        String audience = strFromArg(args.get(1));
        String message = strFromArg(args.get(2));
        DecodedPayload.ChatExtra extra = null;
        if (args.size() >= 4) { // Parse chat extras whenever available (>= 13.0)
            try {
                byte[] pickleBytes = blobFromArg(args.get(3));
                Object extraObj = PickleReader.decode(pickleBytes);
                if (extraObj instanceof Map<?, ?> dict) {
                    extra = new DecodedPayload.ChatExtra(
                            longFromPickle(dict.get("preBattleSign")),
                            longFromPickle(dict.get("prebattleId")),
                            strFromPickle(dict.get("playerClanTag")),
                            longFromPickle(dict.get("type")),
                            new EntityId((int) longFromPickle(dict.get("playerAvatarId"))),
                            strFromPickle(dict.get("playerName")));
                }
            } catch (Exception e) {
                log.debug("Chat extra parse failed: {}", e.getMessage());
            }
        }
        return new DecodedPayload.ChatMessagePayload(entityId, new MetaId(senderId), audience, message, extra);
    }

    // ── VoiceLine ──────────────────────────────────────────────────────

    private DecodedPayload decodeVoiceLine(NamedArgs args) {
        int senderId;
        boolean isGlobal = false;
        VoiceLine voiceLine = new VoiceLine(0, "UnknownVoiceLine", null);
        if (version.isAtLeast(new Version(0, 12, 8, 0))) {
            senderId = (int) Integer.toUnsignedLong(intFromArg(args.get(0)));
            byte[] blob = blobFromArg(args.get(1));
            if (blob != null && blob.length >= 3) {
                var buf = ByteBuffer.wrap(blob).order(ByteOrder.LITTLE_ENDIAN);
                int line = buf.getShort() & 0xFFFF;
                isGlobal = (buf.get() & 0xFF) == 1;
                voiceLine = gameConstants.voiceLine(line, version, buf);
            }
        } else {
            isGlobal = intFromArg(args.get(0)) == 1;
            senderId = (int) Integer.toUnsignedLong(intFromArg(args.get(1)));
            int line = intFromArg(args.get(2));
            voiceLine = gameConstants.voiceLine(line, version, intFromArg(args.get(3)), longFromArg(args.get(4)));
        }
        return new DecodedPayload.VoiceLinePayload(new MetaId(senderId), isGlobal, voiceLine);
    }

    // ── Arena State ────────────────────────────────────────────────────

    private DecodedPayload decodeArenaState(NamedArgs args) {
        if (args.isEmpty()) {
            return new DecodedPayload.OnArenaStateReceivedPayload(0, 0, Map.of(), List.of(), List.of());
        }
        if (args.has("raw")) {
            byte[] rawBlob = blobFromArg(args.get("raw"));
            if (rawBlob != null && rawBlob.length > 0) return decodeArenaStateFromRawBlob(rawBlob);
            return new DecodedPayload.OnArenaStateReceivedPayload(0, 0, Map.of(), List.of(), List.of());
        }
        long arenaId = longFromArg(args.get(0));
        int teamBuildTypeId = intFromArg(args.get(1));
        Map<Long, List<Map<String, String>>> preBattlesInfo = parsePreBattlesInfo(args, 2);
        List<PlayerStateData> playerStates = parsePlayerList(args, 3, false);
        List<PlayerStateData> botStates = parsePlayerList(args, 4, true);
        return new DecodedPayload.OnArenaStateReceivedPayload(arenaId, teamBuildTypeId, preBattlesInfo, playerStates, botStates);
    }

    private DecodedPayload decodeArenaStateFromRawBlob(byte[] rawBlob) {
        try {
            Object obj = PickleReader.decode(rawBlob);
            if (obj instanceof List<?> list && !list.isEmpty()) {
                if (list.size() >= 5) {
                    long arenaId = longFromPickle(list.get(0));
                    int teamBuildTypeId = (int) longFromPickle(list.get(1));
                    List<PlayerStateData> players = parseStateList(list.get(3), false);
                    List<PlayerStateData> bots = parseStateList(list.get(4), true);
                    return new DecodedPayload.OnArenaStateReceivedPayload(arenaId, teamBuildTypeId, Map.of(), players, bots);
                }
                if (list.size() >= 4) {
                    List<PlayerStateData> players = parseStateList(list.get(3), false);
                    if (!players.isEmpty())
                        return new DecodedPayload.OnArenaStateReceivedPayload(0, 0, Map.of(), players, List.of());
                }
            }
        } catch (Exception e) {
            log.debug("decodeArenaStateFromRawBlob failed: {}", e.getMessage());
        }
        return new DecodedPayload.OnArenaStateReceivedPayload(0, 0, Map.of(), List.of(), List.of());
    }

    private List<PlayerStateData> parseStateList(Object obj, boolean isBot) {
        if (!(obj instanceof List<?> list)) return List.of();
        List<PlayerStateData> result = new ArrayList<>();
        for (var item : list) {
            if (item instanceof List<?> tuples) {
                var psd = PlayerStateData.fromTuples(tuples, version, isBot);
                if (psd.entityId() > 0 || psd.dbId() > 0) result.add(psd);
            }
        }
        return result;
    }

    private Map<Long, List<Map<String, String>>> parsePreBattlesInfo(NamedArgs args, int argIndex) {
        Map<Long, List<Map<String, String>>> result = new LinkedHashMap<>();
        if (argIndex >= args.size()) return result;
        try {
            byte[] pbBlob = blobFromArg(args.get(argIndex));
            Object pbObj = PickleReader.decode(pbBlob);
            if (pbObj instanceof Map<?, ?> pbDict) {
                for (var entry : pbDict.entrySet()) {
                    long key = longFromPickle(entry.getKey());
                    List<Map<String, String>> list = new ArrayList<>();
                    if (entry.getValue() instanceof List<?> elems) {
                        for (var elem : elems) {
                            if (elem == null) list.add(null);
                            else if (elem instanceof Map<?, ?> em) {
                                Map<String, String> sm = new LinkedHashMap<>();
                                for (var kv : em.entrySet()) sm.put(strFromPickle(kv.getKey()), strFromPickle(kv.getValue()));
                                list.add(sm);
                            }
                        }
                    }
                    result.put(key, list);
                }
            }
        } catch (Exception e) {
            log.debug("preBattlesInfo parse failed: {}", e.getMessage());
        }
        return result;
    }

    private DecodedPayload decodeGameRoomStateChanged(NamedArgs args) {
        List<Map<String, ArgValue>> states = new ArrayList<>();
        try {
            byte[] blob = blobFromArg(args.get(0));
            Object obj = PickleReader.decode(blob);
            if (obj instanceof List<?> players) {
                for (var player : players) {
                    if (player instanceof List<?> tuples) {
                        Map<String, ArgValue> mapped = new LinkedHashMap<>();
                        for (var t : tuples) {
                            if (t instanceof List<?> kv && kv.size() >= 2 && kv.get(0) instanceof Long key)
                                mapped.put(String.valueOf(key), pickleToArgValue(kv.get(1)));
                        }
                        if (!mapped.isEmpty()) states.add(mapped);
                    }
                }
            }
        } catch (Exception e) {
            log.debug("onGameRoomStateChanged: {}", e.getMessage());
        }
        return new DecodedPayload.OnGameRoomStateChangedPayload(states);
    }

    private static ArgValue pickleToArgValue(Object v) {
        switch (v) {
            case null -> {
                return new ArgValue.NullVal();
            }
            case Long l -> {
                return new ArgValue.IntVal(l);
            }
            case Double d -> {
                return new ArgValue.FloatVal(d);
            }
            case String s -> {
                return new ArgValue.StrVal(s);
            }
            case Boolean b -> {
                return new ArgValue.BoolVal(b);
            }
            case List<?> l -> {
                return new ArgValue.ArrayVal(l.stream().map(PacketDecoder::pickleToArgValue).toList());
            }
            case Map<?, ?> m -> {
                var entries = new LinkedHashMap<String, ArgValue>();
                for (var e : m.entrySet()) {
                    entries.put(String.valueOf(e.getKey()), pickleToArgValue(e.getValue()));
                }
                return new ArgValue.DictVal(entries);
            }
            default -> {
            }
        }
        return new ArgValue.StrVal(String.valueOf(v));
    }

    private DecodedPayload decodeNewPlayerSpawned(NamedArgs args) {
        List<PlayerStateData> players = parsePlayerList(args, 0, false);
        List<PlayerStateData> bots = parsePlayerList(args, 1, true);
        return new DecodedPayload.NewPlayerSpawnedInBattlePayload(players, bots);
    }

    private List<PlayerStateData> parsePlayerList(NamedArgs args, int argIndex, boolean isBot) {
        if (argIndex >= args.size()) return List.of();
        try {
            byte[] blob = blobFromArg(args.get(argIndex));
            if (blob == null || blob.length == 0) return List.of();
            Object obj = PickleReader.decode(blob);
            if (!(obj instanceof List<?> players)) return List.of();
            List<PlayerStateData> result = new ArrayList<>();
            for (var player : players) {
                if (player instanceof List<?> tuples) {
                    var psd = PlayerStateData.fromTuples(tuples, version, isBot);
                    if (psd.entityId() > 0 || psd.dbId() > 0) result.add(psd);
                }
            }
            return result;
        } catch (Exception e) {
            return List.of();
        }
    }

    // ── Combat ─────────────────────────────────────────────────────────

    private DecodedPayload decodeDamageReceived(EntityId victim, NamedArgs args) {
        var entries = new ArrayList<DecodedPayload.DamageReceivedEntry>();
        // receiveDamagesOnShip (Vehicle.def): single Arg = ARRAY<DAMAGES> at args[0],
        // DAMAGES = { vehicleID: ENTITY_ID, damage: FLOAT }.
        // receiveDamageReport (Avatar.def): args = (BLOB, INT16, BOOL); the BLOB at
        // args[0] carries the same damage entries as a pickle.
        ArgValue damagesArg = !args.isEmpty() ? args.get(0) : null;
        if (damagesArg instanceof ArgValue.ArrayVal arr) {
            parseDamageEntries(arr, entries);
        } else if (damagesArg instanceof ArgValue.BlobVal) {
            Object parsed = tryParseRest(blobFromArg(damagesArg));
            if (parsed instanceof List<?> list) {
                for (var item : list) {
                    if (item instanceof Map<?, ?> m) {
                        int agg = (int) longFromPickle(m.get("vehicleID"));
                        float dmg = (float) doubleFromPickle(m.get("damage"));
                        entries.add(new DecodedPayload.DamageReceivedEntry(new EntityId(agg), dmg));
                    }
                }
            }
        } else if (args.has("__rest")) {
            byte[] rest = blobFromArg(args.get("__rest"));
            Object parsed = tryParseRest(rest);
            if (parsed instanceof List<?> list) {
                for (var item : list) {
                    if (item instanceof Map<?, ?> m) {
                        int agg = (int) longFromPickle(m.get("vehicleID"));
                        float dmg = (float) doubleFromPickle(m.get("damage"));
                        entries.add(new DecodedPayload.DamageReceivedEntry(new EntityId(agg), dmg));
                    }
                }
            }
        }
        return new DecodedPayload.DamageReceivedPayload(victim, entries);
    }

    private void parseDamageEntries(ArgValue.ArrayVal arr, List<DecodedPayload.DamageReceivedEntry> entries) {
        for (var elem : arr.elements()) {
            if (elem instanceof ArgValue.DictVal(Map<String, ArgValue> entries1)) {
                int aggressorId = intFromArg(entries1.get("vehicleID"));
                float damage = floatFromArg(entries1.get("damage"));
                entries.add(new DecodedPayload.DamageReceivedEntry(new EntityId(aggressorId), damage));
            }
        }
    }

    /** Try to parse __rest blob as pickle or RPC value. */
    private Object tryParseRest(byte[] rest) {
        if (rest == null || rest.length == 0) return null;
        try {
            return PickleReader.decode(rest);
        } catch (Exception e) {
            return null;
        }
    }

    private DecodedPayload decodeShipDestroyed(NamedArgs args) {
        int victim = args.size() >= 2 ? intFromArg(args.get(0)) : 0;
        int killer = args.size() >= 2 ? intFromArg(args.get(1)) : 0;
        int cause = args.size() >= 3 ? intFromArg(args.get(2)) : 0;
        return new DecodedPayload.ShipDestroyedPayload(new EntityId(killer), new EntityId(victim), cause);
    }

    private DecodedPayload decodeConsumable(EntityId entity, NamedArgs args) {
        int consumableId = 0;
        float duration = 0f;
        Integer usageType = null;
        if (!args.isEmpty() && args.getFirst() instanceof ArgValue.BlobVal(byte[] b)) {
            if (b.length >= 2) {
                usageType = b[0] & 0xFF;
                consumableId = b[1] & 0xFF;
            }
            if (args.size() >= 2) duration = floatFromArg(args.get(1));
        } else if (!args.isEmpty()) {
            consumableId = intFromArg(args.getFirst());
            duration = args.size() >= 2 ? floatFromArg(args.get(1)) : 0f;
        }
        return new DecodedPayload.ConsumablePayload(entity, consumableId, duration, usageType);
    }

    /** CV 飞机（中队）消耗品：Avatar.squadronConsumableUsed(squadronId, usageParams[, workTimeLeft])。
     *  usageParams blob 与船体 onConsumableUsed 同格式（<BB…>，b[0]=usageType, b[1]=consumableType）。 */
    private DecodedPayload decodeSquadronConsumable(EntityId entity, NamedArgs args) {
        int squadronId = 0;
        int consumableId = 0;
        float duration = 0f;
        Integer usageType = null;
        for (var a : args.values()) {
            if (a instanceof ArgValue.IntVal(long v)) {
                if (squadronId == 0) squadronId = (int) v;
                else duration = v; // workTimeLeft 可能是 int 编码
            } else if (a instanceof ArgValue.BlobVal(byte[] b) && b.length >= 2) {
                usageType = b[0] & 0xFF;
                consumableId = b[1] & 0xFF;
            } else if (a instanceof ArgValue.FloatVal(double f)) {
                duration = (float) f;
            }
        }
        return new DecodedPayload.SquadronConsumablePayload(entity, squadronId, consumableId, duration, usageType);
    }

    private DecodedPayload decodeDamageStat(NamedArgs args) {
        var entries = new ArrayList<DecodedPayload.DamageStatEntry>();
        try {
            if (!args.isEmpty() && args.getFirst() instanceof ArgValue.BlobVal(byte[] value)) {
                Object obj = PickleReader.decode(value);
                if (obj instanceof List<?> items) {
                    for (var item : items) {
                        if (item instanceof List<?> kv && kv.size() >= 2
                            && kv.get(0) instanceof List<?> kt && kt.size() >= 2
                            && kv.get(1) instanceof List<?> vt && vt.size() >= 2) {
                            entries.add(new DecodedPayload.DamageStatEntry(
                                    longFromPickle(kt.get(0)), longFromPickle(kt.get(1)),
                                    longFromPickle(vt.get(0)), doubleFromPickle(vt.get(1))));
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.debug("receiveDamageStat: {}", e.getMessage());
        }
        return new DecodedPayload.DamageStatPayload(entries);
    }

    private DecodedPayload decodeBattleEnd(NamedArgs args) {
        Integer winningTeam = null;
        int finishType = 0;
        if (args.size() >= 2) {
            long wt = longFromArg(args.get(0));
            // 获胜队伍是服务端下发字段：非负为队伍索引，-1 表示平局。
            // 不能过滤掉负值——否则平局时 winningTeam=null，finish()/BattleReportBuilder
            // 的 DRAW 分支永远走不到。
            winningTeam = (int) wt;
            finishType = intFromArg(args.get(1));
        }
        return new DecodedPayload.BattleEndPayload(winningTeam, finishType);
    }

    private DecodedPayload decodeRibbon(NamedArgs args) {
        return new DecodedPayload.RibbonPayload(args.isEmpty() ? 0 : intFromArg(args.getFirst()));
    }

    // ── Minimap vision ─────────────────────────────────────────────────

    /**
     * Decode {@code updateMinimapVisionInfo}，位布局对标 Rust RawMinimapUpdate：
     * <pre>
     * arg0: Array&lt;FixedDict&lt;{vehicleID: i32, packedData: u32}&gt;&gt;
     * packedData (u32 LE):
     *   bits 0-10:  x   (B11, 0-2047)
     *   bits 11-21: y   (B11)
     *   bits 22-29: heading (B8, convert: v/256*360 - 180)
     *   bit 30:     unknown（big hunt，未使用）
     *   bit 31:     is_disappearing
     * x_norm = x/512 - 1.5, y_norm = y/512 - 1.5
     * </pre>
     */
    private DecodedPayload decodeMinimapVision(NamedArgs args) {
        var entries = new ArrayList<DecodedPayload.MinimapUpdateEntry>();
        if (args.isEmpty()) return new DecodedPayload.MinimapUpdatePayload(entries);
        try {
            if (args.getFirst() instanceof ArgValue.ArrayVal(List<ArgValue> elements)) {
                for (var elem : elements) {
                    if (elem instanceof ArgValue.DictVal(Map<String, ArgValue> d)) {
                        int vehicleId = intFromArg(d.get("vehicleID"));
                        int packed = intFromArg(d.get("packedData"));
                        int xRaw = packed & 0x7FF;
                        int yRaw = (packed >> 11) & 0x7FF;
                        int headingRaw = (packed >> 22) & 0xFF;
                        boolean disappearing = ((packed >> 31) & 1) != 0;
                        float heading = (headingRaw / 256.0f) * 360.0f - 180.0f;
                        float x = xRaw / 512.0f - 1.5f;
                        float y = yRaw / 512.0f - 1.5f;
                        boolean isSentinel = xRaw == 0 && yRaw == 0;
                        entries.add(new DecodedPayload.MinimapUpdateEntry(
                                new EntityId(vehicleId), isSentinel, disappearing, heading, x, y));
                    }
                }
            }
        } catch (Exception e) {
            log.debug("updateMinimapVisionInfo decode failed: {}", e.getMessage());
        }
        return new DecodedPayload.MinimapUpdatePayload(entries);
    }

    // ── Artillery / Torpedo ────────────────────────────────────────────

    private DecodedPayload decodeArtilleryShots(EntityId entityId, NamedArgs args) {
        // receiveArtilleryShots (Avatar.def): single Arg = ARRAY<SHOTS_PACK> at args[0].
        // The avatar is the packet's entity (receiver), mirroring the Rust decoder.
        AvatarId avatarId = new AvatarId(entityId.value());
        var salvos = new ArrayList<DecodedPayload.ArtillerySalvo>();
        if (!args.isEmpty() && args.get(0) instanceof ArgValue.ArrayVal(List<ArgValue> elements)) {
            for (var sv : elements) {
                if (sv instanceof ArgValue.DictVal(Map<String, ArgValue> d)) {
                    var shots = new ArrayList<DecodedPayload.ArtilleryShotData>();
                    if (d.get("shots") instanceof ArgValue.ArrayVal(List<ArgValue> elements1)) {
                        for (var sh : elements1)
                            if (sh instanceof ArgValue.DictVal(Map<String, ArgValue> entries)) shots.add(parseShotData(entries));
                    }
                    salvos.add(new DecodedPayload.ArtillerySalvo(
                            new EntityId((int) longFromArg(d.get("ownerID"))),
                            new GameParamId(longFromArg(d.get("paramsID"))),
                            (int) longFromArg(d.get("salvoID")), shots));
                }
            }
        }
        return new DecodedPayload.ArtilleryShotsPayload(avatarId, salvos);
    }

    private DecodedPayload.ArtilleryShotData parseShotData(Map<String, ArgValue> d) {
        return new DecodedPayload.ArtilleryShotData(
                extractVec3(d.get("pos")), floatFromArg(d.get("pitch")), floatFromArg(d.get("speed")),
                extractVec3(d.get("tarPos")), (int) longFromArg(d.get("shotID")),
                (int) longFromArg(d.get("gunBarrelID")),
                floatFromArg(d.get("serverTimeLeft")), floatFromArg(d.get("shooterHeight")),
                floatFromArg(d.get("hitDistance")));
    }

    private DecodedPayload decodeTorpedoes(EntityId entityId, NamedArgs args) {
        // receiveTorpedoes (Avatar.def): single Arg = ARRAY<TORPEDOES_PACK> at args[0].
        // Each pack holds { ownerID, paramsID, salvoID, skinID, torpedoes: [...] } and
        // each torpedo holds { pos, dir, shotID, armed }.
        AvatarId avatarId = new AvatarId(entityId.value());
        var torpedoes = new ArrayList<DecodedPayload.TorpedoData>();
        if (!args.isEmpty() && args.get(0) instanceof ArgValue.ArrayVal(List<ArgValue> elements)) {
            for (var tv : elements) {
                if (tv instanceof ArgValue.DictVal(Map<String, ArgValue> d)) {
                    int ownerId = (int) longFromArg(d.get("ownerID"));
                    long paramsId = longFromArg(d.get("paramsID"));
                    int salvoId = (int) longFromArg(d.get("salvoID"));
                    int skinId = (int) longFromArg(d.get("skinID"));
                    if (d.get("torpedoes") instanceof ArgValue.ArrayVal(List<ArgValue> elements1)) {
                        for (var torp : elements1) {
                            if (torp instanceof ArgValue.DictVal(Map<String, ArgValue> entries)) {
                                torpedoes.add(new DecodedPayload.TorpedoData(
                                        new EntityId(ownerId), new GameParamId(paramsId), salvoId, skinId,
                                        (int) longFromArg(entries.get("shotID")),
                                        extractVec3(entries.get("pos")), extractVec3(entries.get("dir")),
                                        intFromArg(entries.get("armed")) != 0));
                            }
                        }
                    }
                }
            }
        }
        return new DecodedPayload.TorpedoesReceivedPayload(avatarId, torpedoes);
    }

    private DecodedPayload decodeTorpedoDirection(NamedArgs args) {
        return new DecodedPayload.TorpedoDirectionPayload(
                new EntityId(intFromArg(args.get(0))), intFromArg(args.get(1)),
                extractVec3(args.size() >= 3 ? args.get(2) : null),
                args.size() >= 4 ? floatFromArg(args.get(3)) : 0f,
                args.size() >= 5 ? floatFromArg(args.get(4)) : 0f);
    }

    private DecodedPayload decodeShotKills(EntityId entityId, NamedArgs args) {
        // receiveShotKills (Avatar.def): single Arg = ARRAY<SHOTKILLS_PACK> at args[0].
        // Pack: { ownerID, hitType: UINT8, kills: Array<SHOTKILL> };
        // SHOTKILL: { pos: VECTOR3, shotID, terminalBallisticsInfo (AllowNone) }.
        AvatarId avatarId = new AvatarId(entityId.value());
        var hits = new ArrayList<DecodedPayload.ShotHitEntry>();
        if (!args.isEmpty() && args.get(0) instanceof ArgValue.ArrayVal(List<ArgValue> elements)) {
            for (var pack : elements) {
                if (pack instanceof ArgValue.DictVal(Map<String, ArgValue> d)) {
                    int ownerId = (int) longFromArg(d.get("ownerID"));
                    int raw = (int) longFromArg(d.get("hitType"));
                    var hitType = new DecodedPayload.HitType((raw >> 5) & 0x07, raw & 0x1F, raw);
                    if (d.get("kills") instanceof ArgValue.ArrayVal(List<ArgValue> elements1)) {
                        for (var kill : elements1) {
                            if (kill instanceof ArgValue.DictVal(Map<String, ArgValue> entries)) {
                                hits.add(new DecodedPayload.ShotHitEntry(
                                        new EntityId(ownerId), hitType,
                                        (int) longFromArg(entries.get("shotID")),
                                        extractVec3(entries.get("pos")),
                                        parseTerminalBallistics(entries.get("terminalBallisticsInfo"))));
                            }
                        }
                    }
                }
            }
        }
        return new DecodedPayload.ShotKillsPayload(avatarId, hits);
    }

    private DecodedPayload.TerminalBallistics parseTerminalBallistics(ArgValue val) {
        if (val instanceof ArgValue.DictVal(Map<String, ArgValue> d)) {
            return new DecodedPayload.TerminalBallistics(
                    extractVec3(d.get("position")), extractVec3(d.get("velocity")),
                    intFromArg(d.get("detonatorActivated")) != 0, floatFromArg(d.get("materialAngle")));
        }
        return null;
    }

    // ── Aviation ───────────────────────────────────────────────────────

    private DecodedPayload decodeWardAdded(EntityId eid, NamedArgs args) {
        return new DecodedPayload.WardAddedPayload(eid,
                longFromArg(args.size() >= 2 ? args.get(1) : args.getFirst()),
                extractVec3(args.size() >= 3 ? args.get(2) : null),
                args.size() >= 4 ? floatFromArg(args.get(3)) : 0f,
                new EntityId(args.size() >= 5 ? intFromArg(args.get(4)) : 0));
    }

    private DecodedPayload decodeWardRemoved(EntityId eid, NamedArgs args) {
        return new DecodedPayload.WardRemovedPayload(eid,
                longFromArg(args.size() >= 2 ? args.get(1) : args.getFirst()));
    }

    private DecodedPayload decodePlaneAdded(EntityId eid, NamedArgs args) {
        Vec3 pos = args.size() >= 4 ? extractVec3(args.get(3)) : new Vec3(0, 0, 0);
        return new DecodedPayload.PlaneAddedPayload(eid,
                longFromArg(args.get(0)), intFromArg(args.get(1)),
                new GameParamId(longFromArg(args.get(2))), pos.x(), pos.z());
    }

    private DecodedPayload decodePlaneRemoved(EntityId eid, NamedArgs args) {
        return new DecodedPayload.PlaneRemovedPayload(eid, longFromArg(args.get(0)));
    }

    private DecodedPayload decodePlanePosition(EntityId eid, NamedArgs args) {
        Vec3 pos = args.size() >= 2 ? extractVec3(args.get(1)) : new Vec3(0, 0, 0);
        return new DecodedPayload.PlanePositionPayload(eid, longFromArg(args.get(0)), pos.x(), pos.z());
    }

    /** receive_addSquadron：完整中队血量/状态（squadronState 是 SQUADRON_STATE 字典）。 */
    private DecodedPayload decodeSquadronAdded(NamedArgs args) {
        int totalNumPlanes = intFromArg(args.get("totalNumPlanes"));
        long parentId = longFromArg(args.get("parentID"));
        int maxHealth = intFromArg(args.get("maxHealth"));
        float healthPart = floatFromArg(args.get("squadronHealthPart"));
        long planeHealth = longFromArg(args.get("planeHealth"));

        long planeId = 0;
        boolean isActive = false;
        int numPlanes = 0;
        int currentStateId = 0;
        if (args.get("squadronState") instanceof ArgValue.DictVal(Map<String, ArgValue> d)) {
            planeId = longFromArg(d.get("planeID"));
            isActive = intFromArg(d.get("isActive")) != 0;
            numPlanes = intFromArg(d.get("numPlanes"));
            currentStateId = intFromArg(d.get("currentStateId"));
        }
        return new DecodedPayload.SquadronAddedPayload(new DecodedPayload.SquadronState(
            planeId, totalNumPlanes, numPlanes, isActive, currentStateId, parentId,
            maxHealth, healthPart, planeHealth));
    }

    private DecodedPayload decodeSquadronHealth(NamedArgs args) {
        return new DecodedPayload.SquadronHealthPayload(longFromArg(args.get(0)), floatFromArg(args.get(1)));
    }

    private DecodedPayload decodeSquadronPlanesHealth(NamedArgs args) {
        return new DecodedPayload.SquadronPlanesHealthPayload(longFromArg(args.get(0)), longFromArg(args.get(1)));
    }

    // ── Gun sync / Ammo ────────────────────────────────────────────────

    private DecodedPayload decodeGunSync(EntityId eid, NamedArgs args) {
        return new DecodedPayload.GunSyncPayload(eid,
                intFromArg(args.get(0)), intFromArg(args.get(1)),
                floatFromArg(args.get(2)), args.size() >= 4 ? floatFromArg(args.get(3)) : 0f);
    }

    private DecodedPayload decodeSetAmmo(EntityId eid, NamedArgs args) {
        return new DecodedPayload.SetAmmoForWeaponPayload(eid,
                intFromArg(args.get(0)), new GameParamId(longFromArg(args.get(1))),
                args.size() >= 3 && intFromArg(args.get(2)) != 0);
    }

    // ── Helpers ────────────────────────────────────────────────────────

    static Vec3 extractVec3(ArgValue val) {
        return switch (val) {
            case ArgValue.Vec3Val(float x, float y, float z) -> new Vec3(x, y, z);
            case ArgValue.Vec2Val(float x, float y) -> new Vec3(x, 0, y);
            case ArgValue.ArrayVal(List<ArgValue> elements) when elements.size() >= 3 -> new Vec3(floatFromArg(elements.get(0)), floatFromArg(elements.get(1)), floatFromArg(elements.get(2)));
            case ArgValue.ArrayVal(List<ArgValue> elements) when elements.size() >= 2 -> new Vec3(floatFromArg(elements.get(0)), 0, floatFromArg(elements.get(1)));
            case null, default -> new Vec3(0, 0, 0);
        };
    }

    static int intFromArg(ArgValue v) {
        return switch (v) {
            case ArgValue.IntVal iv -> (int) iv.value();
            case ArgValue.FloatVal fv -> (int) fv.value();
            case ArgValue.BoolVal bv -> bv.value() ? 1 : 0;
            case null, default -> 0;
        };
    }

    static long longFromArg(ArgValue v) {
        return switch (v) {
            case ArgValue.IntVal iv -> iv.value();
            case ArgValue.FloatVal fv -> (long) fv.value();
            case null, default -> 0;
        };
    }

    static float floatFromArg(ArgValue v) {
        return switch (v) {
            case ArgValue.FloatVal fv -> (float) fv.value();
            case ArgValue.IntVal iv -> (float) iv.value();
            case null, default -> 0f;
        };
    }

    static String strFromArg(ArgValue v) {
        return switch (v) {
            case ArgValue.StrVal sv -> sv.value();
            case null -> "";
            default -> String.valueOf(v);
        };
    }

    static byte[] blobFromArg(ArgValue v) {
        return switch (v) {
            case ArgValue.BlobVal bv -> bv.value();
            case null, default -> new byte[0];
        };
    }

    static long longFromPickle(Object v) {
        if (v instanceof Long l) return l;
        if (v instanceof Double d) return d.longValue();
        if (v instanceof String s) {
            try {
                return Long.parseLong(s);
            } catch (NumberFormatException e) {
                return 0;
            }
        }
        return 0;
    }

    static double doubleFromPickle(Object v) {
        if (v instanceof Double d) return d;
        if (v instanceof Long l) return l.doubleValue();
        return 0;
    }

    static String strFromPickle(Object v) {
        if (v instanceof String s) return s;
        return v != null ? v.toString() : "";
    }
}
