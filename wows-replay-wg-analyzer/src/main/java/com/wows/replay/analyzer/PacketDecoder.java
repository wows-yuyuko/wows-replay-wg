package com.wows.replay.analyzer;

import com.wows.replay.core.rpc.ArgValue;
import com.wows.replay.core.types.*;
import com.wows.replay.packets.*;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.*;

import lombok.extern.slf4j.Slf4j;

/**
 * Layer-2 packet decoder — converts {@link PacketParser} output into
 * semantically meaningful {@link DecodedPayload} variants.
 *
 * <p>Mirrors Rust {@code PacketDecoder}. Handles EntityMethod → domain event
 * conversion, pickle blob parsing, and version-dependent field layouts.</p>
 */
@Slf4j
public class PacketDecoder {

    private final Version version;

    public PacketDecoder(Version version) {
        this.version = version;
    }

    /**
     * Decode a parsed {@link Packet} into a {@link DecodedPayload}.
     *
     * @param packet  the parsed packet from {@link PacketParser}
     * @return the decoded payload, never null
     */
    public DecodedPayload decode(Packet packet) {
        if (packet == null || packet.payload() instanceof Packet.InvalidPayload) {
            return new DecodedPayload.InvalidPayload("null or invalid packet");
        }

        Object payload = packet.payload();

        return switch (payload) {
            // ── EntityMethod → semantic events ──────────────────────────
            case EntityMethodPacket em -> decodeEntityMethod(em);

            // ── Entity lifecycle (pass-through) ─────────────────────────
            case EntityCreatePacket ec     -> new DecodedPayload.EntityCreatePayload(ec);
            case BasePlayerCreatePacket bp -> new DecodedPayload.BasePlayerCreatePayload(bp);
            case CellPlayerCreatePacket cp -> new DecodedPayload.CellPlayerCreatePayload(cp);
            case EntityEnterPacket ee      -> new DecodedPayload.EntityEnterPayload(ee);
            case EntityLeavePacket el      -> new DecodedPayload.EntityLeavePayload(el);
            case EntityPropertyPacket ep   -> new DecodedPayload.EntityPropertyPayload(ep);
            case EntityControlPacket ec2   -> new DecodedPayload.EntityControlPayload(ec2);

            // ── Position ────────────────────────────────────────────────
            case PositionPacket pos        -> new DecodedPayload.PositionPayload(pos);
            case PlayerOrientationPacket po -> new DecodedPayload.PlayerOrientationPayload(po);
            case NonVolatilePositionPacket nvp -> new DecodedPayload.NonVolatilePositionPayload(nvp);

            // ── Property update (pass-through) ──────────────────────────
            case PropertyUpdatePacket pu   -> new DecodedPayload.PropertyUpdatePayload(pu);

            // ── Simple pass-through ─────────────────────────────────────
            case MapPacket mp              -> new DecodedPayload.MapPayload(mp);
            case VersionPacket vp          -> new DecodedPayload.VersionPayload(vp.version());
            case CameraPacket cp2          -> new DecodedPayload.CameraPayload(cp2);
            case CameraModePacket cm       -> new DecodedPayload.CameraModePayload(cm.mode());
            case CameraFreeLookPacket cfl  -> new DecodedPayload.CameraFreeLookPayload(cfl.freeLook() != 0);
            case CruiseStatePacket cs      -> new DecodedPayload.CruiseStatePayload(cs.key(), cs.value());
            case OwnShipPacket os          -> new DecodedPayload.OwnShipPayload(os);
            case SetWeaponLockPacket swl   -> new DecodedPayload.SetWeaponLockPayload(swl);
            case ServerTimestampPacket st  -> new DecodedPayload.ServerTimestampPayload(st.timestamp());
            case ServerTickPacket st2      -> new DecodedPayload.ServerTickPayload(st2.tickRate());
            case SubControllerPacket sc    -> new DecodedPayload.SubControllerPayload(sc);
            case ShotTrackingPacket st3    -> new DecodedPayload.ShotTrackingPayload(st3);
            case GunMarkerPacket gm        -> new DecodedPayload.GunMarkerPayload(gm);
            case PlayerNetStatsPacket pns  -> new DecodedPayload.PlayerNetStatsPayload(pns);
            case InitFlagPacket iff        -> new DecodedPayload.InitFlagPayload(iff.flag());
            case BattleResultsPacket br    -> new DecodedPayload.BattleResultsPayload(br.json());

            // ── Fallback ────────────────────────────────────────────────
            case Packet.InvalidPayload inv -> new DecodedPayload.InvalidPayload(inv.error());
            case null, default -> {
                // Handle String payloads (INIT_MARKER, UNKNOWN_0X2E byte[], etc.)
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
            case "onChatMessage"           -> decodeChat(em.entityId(), args);
            case "receive_CommonCMD"       -> decodeVoiceLine(args);
            case "onArenaStateReceived"    -> decodeArenaState(args);
            case "onGameRoomStateChanged"  -> decodeGameRoomStateChanged(args);
            case "onNewPlayerSpawnedInBattle" -> decodeNewPlayerSpawned(args);
            case "receiveDamagesOnShip"    -> decodeDamageReceived(em.entityId(), args);
            case "receiveVehicleDeath"     -> decodeShipDestroyed(args);
            case "onConsumableUsed"        -> decodeConsumable(em.entityId(), args);
            case "receiveDamageStat"       -> decodeDamageStat(args);
            case "onBattleEnd"             -> decodeBattleEnd(args);
            case "onShotFired",
                 "receiveArtilleryShots"   -> decodeArtilleryShots(args);
            case "receiveTorpedoes"        -> decodeTorpedoes(args);
            case "receiveShotKills"        -> decodeShotKills(args);
            case "receive_wardAdded"       -> decodeWardAdded(em.entityId(), args);
            case "receive_wardRemoved"     -> decodeWardRemoved(em.entityId(), args);
            case "onPlaneAdded"            -> decodePlaneAdded(em.entityId(), args);
            case "onPlaneRemoved"          -> decodePlaneRemoved(em.entityId(), args);
            case "onPlanePosition"         -> decodePlanePosition(em.entityId(), args);
            case "onGunSync"               -> decodeGunSync(em.entityId(), args);
            case "onSetAmmoForWeapon"      -> decodeSetAmmo(em.entityId(), args);
            case "receiveTorpedoDirection" -> decodeTorpedoDirection(args);
            case "onRibbon"                -> decodeRibbon(args);
            case "syncShipCracks"          -> new DecodedPayload.EntityMethodPayload(em);
            default                        -> new DecodedPayload.EntityMethodPayload(em);
        };
    }

    // ── Chat ───────────────────────────────────────────────────────────

    private DecodedPayload decodeChat(EntityId entityId, NamedArgs args) {
        int senderId = (int) (Integer.toUnsignedLong(intFromArg(args.get(0))));
        String audience = strFromArg(args.get(1));
        String message = strFromArg(args.get(2));

        DecodedPayload.ChatExtra extra = null;
        if (senderId == 0 && args.size() >= 4) {
            try {
                byte[] pickleBytes = blobFromArg(args.get(3));
                Object extraObj = PickleDecoder.decode(pickleBytes);
                if (extraObj instanceof Map<?, ?> extraDict) {
                    @SuppressWarnings("unchecked")
                    var dict = (Map<Object, Object>) extraDict;
                    long preBattleSign = longFromPickle(dict.get("preBattleSign"));
                    long preBattleId  = longFromPickle(dict.get("prebattleId"));
                    String clanTag    = strFromPickle(dict.get("playerClanTag"));
                    long type         = longFromPickle(dict.get("type"));
                    int avatarId      = (int) longFromPickle(dict.get("playerAvatarId"));
                    String playerName = strFromPickle(dict.get("playerName"));
                    extra = new DecodedPayload.ChatExtra(
                        preBattleSign, preBattleId, clanTag, type,
                        new EntityId(avatarId), playerName);
                }
            } catch (Exception e) {
                log.debug("Failed to parse onChatMessage extra data: {}", e.getMessage());
            }
        }

        return new DecodedPayload.ChatMessagePayload(
            entityId, new AccountId(senderId), audience, message, extra);
    }

    // ── VoiceLine ──────────────────────────────────────────────────────

    private DecodedPayload decodeVoiceLine(NamedArgs args) {
        int senderId;
        boolean isGlobal = false;
        String voiceLine = "unknown";

        if (version.isAtLeast(new Version(0, 12, 8, 0))) {
            senderId = (int) Integer.toUnsignedLong(intFromArg(args.get(0)));
            byte[] blob = blobFromArg(args.get(1));
            if (blob != null && blob.length >= 3) {
                var buf = ByteBuffer.wrap(blob).order(ByteOrder.LITTLE_ENDIAN);
                int line = buf.getShort() & 0xFFFF;
                int audience = buf.get() & 0xFF;
                isGlobal = audience == 1;
                voiceLine = voiceLineName(line, buf);
            }
        } else {
            // Old format: (audience: u8, sender: i32, line: u8, a: u32, b: u64)
            int audience = intFromArg(args.get(0));
            senderId     = (int) Integer.toUnsignedLong(intFromArg(args.get(1)));
            int line     = intFromArg(args.get(2));
            isGlobal = audience == 1;
            voiceLine = voiceLineNameOld(line, intFromArg(args.get(3)), longFromArg(args.get(4)));
        }

        return new DecodedPayload.VoiceLinePayload(
            new AccountId(senderId), isGlobal, voiceLine);
    }

    private static String voiceLineName(int line, ByteBuffer buf) {
        return switch (line) {
            case 1 -> "AttentionToSquare(" + buf.getShort() + "," + buf.getShort() + ")";
            case 2 -> "QuickTactic(" + buf.getShort() + "," + buf.getLong() + ")";
            case 3 -> "RequestingSupport";
            case 5 -> "Wilco";
            case 6 -> "Negative";
            case 7 -> "WellDone";
            case 8 -> "FairWinds";
            case 9 -> "Curses";
            case 10 -> "DefendTheBase";
            case 11 -> "ProvideAntiAircraft";
            case 12 -> {
                buf.getShort(); // target_type
                long id = buf.getLong();
                yield "Retreat" + (id != 0 ? "(" + id + ")" : "");
            }
            case 13 -> "IntelRequired";
            case 14 -> "SetSmokeScreen";
            case 15 -> "UsingRadar";
            case 16 -> "UsingHydroSearch";
            case 17 -> "FollowMe";
            case 18 -> "MapPointAttention(" + buf.getFloat() + "," + buf.getFloat() + ")";
            case 19 -> "UsingSubmarineLocator";
            default -> "UnknownVoiceLine(" + line + ")";
        };
    }

    private static String voiceLineNameOld(int line, int a, long b) {
        return switch (line) {
            case 1 -> "AttentionToSquare(" + a + "," + b + ")";
            case 2 -> "QuickTactic(" + a + "," + b + ")";
            case 3 -> "RequestingSupport";
            case 5 -> "Wilco";
            case 6 -> "Negative";
            case 7 -> "WellDone";
            case 8 -> "FairWinds";
            case 9 -> "Curses";
            case 10 -> "DefendTheBase";
            case 11 -> "ProvideAntiAircraft";
            case 12 -> "Retreat" + (b != 0 ? "(" + b + ")" : "");
            case 13 -> "IntelRequired";
            case 14 -> "SetSmokeScreen";
            case 15 -> "UsingRadar";
            case 16 -> "UsingHydroSearch";
            case 17 -> "FollowMe";
            case 18 -> "MapPointAttention(" + a + "," + b + ")";
            case 19 -> "UsingSubmarineLocator";
            default -> "UnknownVoiceLine(" + line + ")";
        };
    }

    // ── Arena State ────────────────────────────────────────────────────

    private DecodedPayload decodeArenaState(NamedArgs args) {
        long arenaId = longFromArg(args.get(0));
        int teamBuildTypeId = intFromArg(args.get(1));

        // args[2]: preBattlesInfo (pickle dict: i64 → list of optional dicts)
        Map<Long, List<Map<String, String>>> preBattlesInfo = new LinkedHashMap<>();
        try {
            byte[] pbBlob = blobFromArg(args.get(2));
            Object pbObj = PickleDecoder.decode(pbBlob);
            if (pbObj instanceof Map<?, ?> pbDict) {
                for (var entry : pbDict.entrySet()) {
                    long key = longFromPickle(entry.getKey());
                    List<Map<String, String>> list = new ArrayList<>();
                    if (entry.getValue() instanceof List<?> elems) {
                        for (var elem : elems) {
                            if (elem == null) {
                                list.add(null);
                            } else if (elem instanceof Map<?, ?> elemDict) {
                                Map<String, String> strMap = new LinkedHashMap<>();
                                for (var kv : elemDict.entrySet()) {
                                    strMap.put(strFromPickle(kv.getKey()), strFromPickle(kv.getValue()));
                                }
                                list.add(strMap);
                            }
                        }
                    }
                    preBattlesInfo.put(key, list);
                }
            }
        } catch (Exception e) {
            log.debug("Failed to parse preBattlesInfo: {}", e.getMessage());
        }

        // args[3]: human player states (pickle blob → list of player dicts)
        List<PlayerStateData> playerStates = parsePlayerList(args, 3, false);

        // args[4]: bot player states (pickle blob → list of bot dicts)
        List<PlayerStateData> botStates = parsePlayerList(args, 4, true);

        return new DecodedPayload.OnArenaStateReceivedPayload(
            arenaId, teamBuildTypeId, preBattlesInfo, playerStates, botStates);
    }

    private DecodedPayload decodeGameRoomStateChanged(NamedArgs args) {
        List<Map<String, ArgValue>> states = new ArrayList<>();
        try {
            byte[] blob = blobFromArg(args.get(0));
            Object obj = PickleDecoder.decode(blob);
            if (obj instanceof List<?> players) {
                for (var player : players) {
                    if (player instanceof List<?> tuples) {
                        Map<String, ArgValue> mapped = new LinkedHashMap<>();
                        for (var t : tuples) {
                            if (t instanceof List<?> kv && kv.size() >= 2
                                && kv.get(0) instanceof Long key) {
                                mapped.put(String.valueOf(key), pickleToArgValue(kv.get(1)));
                            }
                        }
                        if (!mapped.isEmpty()) states.add(mapped);
                    }
                }
            }
        } catch (Exception e) {
            log.debug("onGameRoomStateChanged parse error: {}", e.getMessage());
        }
        return new DecodedPayload.OnGameRoomStateChangedPayload(states);
    }

    /** Convert a pickle-decoded value to an ArgValue. */
    private static ArgValue pickleToArgValue(Object v) {
        if (v == null) return new ArgValue.NullVal();
        if (v instanceof Long l)    return new ArgValue.IntVal(l);
        if (v instanceof Double d)  return new ArgValue.FloatVal(d);
        if (v instanceof String s)  return new ArgValue.StrVal(s);
        if (v instanceof Boolean b) return new ArgValue.BoolVal(b);
        if (v instanceof List<?> l) {
            var elems = l.stream().map(PacketDecoder::pickleToArgValue).toList();
            return new ArgValue.ArrayVal(elems);
        }
        if (v instanceof Map<?,?> m) {
            var entries = new LinkedHashMap<String, ArgValue>();
            for (var e : m.entrySet()) {
                entries.put(String.valueOf(e.getKey()), pickleToArgValue(e.getValue()));
            }
            return new ArgValue.DictVal(entries);
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
            Object obj = PickleDecoder.decode(blob);
            if (!(obj instanceof List<?> players)) return List.of();

            List<PlayerStateData> result = new ArrayList<>();
            for (var player : players) {
                if (player instanceof List<?> tuples) {
                    result.add(PlayerStateData.fromTuples(tuples, version, isBot));
                }
            }
            return result;
        } catch (Exception e) {
            log.debug("parsePlayerList[{}] error: {}", argIndex, e.getMessage());
            return List.of();
        }
    }

    // ── Combat ─────────────────────────────────────────────────────────

    private DecodedPayload decodeDamageReceived(EntityId victim, NamedArgs args) {
        var entries = new ArrayList<DecodedPayload.DamageReceivedEntry>();
        if (!args.isEmpty() && args.getFirst() instanceof ArgValue.ArrayVal arr) {
            for (var elem : arr.elements()) {
                if (elem instanceof ArgValue.DictVal dict) {
                    int aggressorId = intFromArg(dict.entries().get("vehicleID"));
                    float damage = floatFromArg(dict.entries().get("damage"));
                    entries.add(new DecodedPayload.DamageReceivedEntry(
                        new EntityId(aggressorId), damage));
                }
            }
        }
        return new DecodedPayload.DamageReceivedPayload(victim, entries);
    }

    private DecodedPayload decodeShipDestroyed(NamedArgs args) {
        int victim = args.size() >= 2 ? intFromArg(args.get(0)) : 0;
        int killer = args.size() >= 2 ? intFromArg(args.get(1)) : 0;
        int cause  = args.size() >= 3 ? intFromArg(args.get(2)) : 0;
        return new DecodedPayload.ShipDestroyedPayload(
            new EntityId(killer), new EntityId(victim), cause);
    }

    private DecodedPayload decodeConsumable(EntityId entity, NamedArgs args) {
        int consumableId = 0;
        float duration = 0f;
        Integer usageType = null;

        if (!args.isEmpty() && args.getFirst() instanceof ArgValue.BlobVal blob) {
            // 15.2+ packed struct
            byte[] b = blob.value();
            if (b.length >= 2) {
                usageType = b[0] & 0xFF;
                consumableId = b[1] & 0xFF;
            }
            if (args.size() >= 2) {
                duration = floatFromArg(args.get(1));
            }
        } else if (!args.isEmpty()) {
            // Pre-15.2: first arg is consumable id
            consumableId = intFromArg(args.getFirst());
            duration = args.size() >= 2 ? floatFromArg(args.get(1)) : 0f;
        }

        return new DecodedPayload.ConsumablePayload(
            entity, consumableId, duration, usageType);
    }

    private DecodedPayload decodeDamageStat(NamedArgs args) {
        var entries = new ArrayList<DecodedPayload.DamageStatEntry>();
        try {
            if (!args.isEmpty() && args.getFirst() instanceof ArgValue.BlobVal blob) {
                Object obj = PickleDecoder.decode(blob.value());
                if (obj instanceof List<?> items) {
                    for (var item : items) {
                        if (item instanceof List<?> kv && kv.size() >= 2
                            && kv.get(0) instanceof List<?> keyTuple && keyTuple.size() >= 2
                            && kv.get(1) instanceof List<?> valTuple && valTuple.size() >= 2)
                        {
                            long weapon   = longFromPickle(keyTuple.get(0));
                            long category = longFromPickle(keyTuple.get(1));
                            long count    = longFromPickle(valTuple.get(0));
                            double total  = doubleFromPickle(valTuple.get(1));
                            entries.add(new DecodedPayload.DamageStatEntry(
                                weapon, category, count, total));
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.debug("receiveDamageStat parse error: {}", e.getMessage());
        }
        return new DecodedPayload.DamageStatPayload(entries);
    }

    private DecodedPayload decodeBattleEnd(NamedArgs args) {
        Integer winningTeam = null;
        int finishType = 0;

        if (args.size() >= 2) {
            long wt = longFromArg(args.get(0));
            if (wt >= 0) winningTeam = (int) wt;
            finishType = intFromArg(args.get(1));
        }
        return new DecodedPayload.BattleEndPayload(winningTeam, finishType);
    }

    private DecodedPayload decodeRibbon(NamedArgs args) {
        int ribbonId = args.isEmpty() ? 0 : intFromArg(args.getFirst());
        return new DecodedPayload.RibbonPayload(ribbonId);
    }

    // ── Artillery / Torpedo ────────────────────────────────────────────

    private DecodedPayload decodeArtilleryShots(NamedArgs args) {
        AvatarId avatarId = new AvatarId(args.isEmpty() ? 0 : intFromArg(args.getFirst()));
        var salvos = new ArrayList<DecodedPayload.ArtillerySalvo>();

        if (args.size() >= 2 && args.get(1) instanceof ArgValue.ArrayVal arr) {
            for (var salvoVal : arr.elements()) {
                if (salvoVal instanceof ArgValue.DictVal sd) {
                    var d = sd.entries();
                    int ownerId = intFromArg(d.get("owner_id"));
                    long paramsId = longFromArg(d.get("params_id"));
                    int salvoId = intFromArg(d.get("salvo_id"));

                    var shots = new ArrayList<DecodedPayload.ArtilleryShotData>();
                    if (d.get("shots") instanceof ArgValue.ArrayVal shotArr) {
                        for (var shotVal : shotArr.elements()) {
                            if (shotVal instanceof ArgValue.DictVal shDict) {
                                shots.add(parseShotData(shDict.entries()));
                            }
                        }
                    }
                    salvos.add(new DecodedPayload.ArtillerySalvo(
                        new EntityId(ownerId), new GameParamId(paramsId), salvoId, shots));
                }
            }
        }
        return new DecodedPayload.ArtilleryShotsPayload(avatarId, salvos);
    }

    private DecodedPayload.ArtilleryShotData parseShotData(Map<String, ArgValue> d) {
        return new DecodedPayload.ArtilleryShotData(
            extractVec3(d.get("origin")),
            floatFromArg(d.get("pitch")),
            floatFromArg(d.get("speed")),
            extractVec3(d.get("target")),
            intFromArg(d.get("shot_id")),
            intFromArg(d.get("gun_barrel_id")),
            floatFromArg(d.get("server_time_left")),
            floatFromArg(d.get("shooter_height")),
            floatFromArg(d.get("hit_distance"))
        );
    }

    private DecodedPayload decodeTorpedoes(NamedArgs args) {
        AvatarId avatarId = new AvatarId(args.isEmpty() ? 0 : intFromArg(args.getFirst()));
        var torpedoes = new ArrayList<DecodedPayload.TorpedoData>();

        if (args.size() >= 2 && args.get(1) instanceof ArgValue.ArrayVal arr) {
            for (var torpVal : arr.elements()) {
                if (torpVal instanceof ArgValue.DictVal td) {
                    var d = td.entries();
                    torpedoes.add(new DecodedPayload.TorpedoData(
                        new EntityId(intFromArg(d.get("owner_id"))),
                        new GameParamId(longFromArg(d.get("params_id"))),
                        intFromArg(d.get("salvo_id")),
                        intFromArg(d.get("skin_id")),
                        intFromArg(d.get("shot_id")),
                        extractVec3(d.get("origin")),
                        extractVec3(d.get("direction")),
                        intFromArg(d.get("armed")) != 0
                    ));
                }
            }
        }
        return new DecodedPayload.TorpedoesReceivedPayload(avatarId, torpedoes);
    }

    private DecodedPayload decodeTorpedoDirection(NamedArgs args) {
        int ownerId = intFromArg(args.get(0));
        int shotId  = intFromArg(args.get(1));
        Vec3 pos    = extractVec3(args.size() >= 3 ? args.get(2) : null);
        float targetYaw = args.size() >= 4 ? floatFromArg(args.get(3)) : 0f;
        float speedCoef = args.size() >= 5 ? floatFromArg(args.get(4)) : 0f;
        return new DecodedPayload.TorpedoDirectionPayload(
            new EntityId(ownerId), shotId, pos, targetYaw, speedCoef);
    }

    private DecodedPayload decodeShotKills(NamedArgs args) {
        AvatarId avatarId = new AvatarId(args.isEmpty() ? 0 : intFromArg(args.getFirst()));
        var hits = new ArrayList<DecodedPayload.ShotHitEntry>();

        if (args.size() >= 2 && args.get(1) instanceof ArgValue.ArrayVal arr) {
            for (var hitVal : arr.elements()) {
                if (hitVal instanceof ArgValue.DictVal hd) {
                    var d = hd.entries();
                    int raw = intFromArg(d.get("hit_type"));
                    int collisionId = (raw >> 5) & 0x07;
                    int shellHitId  = raw & 0x1F;
                    var hitType = new DecodedPayload.HitType(collisionId, shellHitId, raw);

                    Vec3 pos = extractVec3(d.get("position"));
                    DecodedPayload.TerminalBallistics tb = null;
                    if (d.containsKey("terminal_ballistics")) {
                        tb = parseTerminalBallistics(d.get("terminal_ballistics"));
                    }

                    hits.add(new DecodedPayload.ShotHitEntry(
                        new EntityId(intFromArg(d.get("owner_id"))),
                        hitType,
                        intFromArg(d.get("shot_id")),
                        pos, tb
                    ));
                }
            }
        }
        return new DecodedPayload.ShotKillsPayload(avatarId, hits);
    }

    private DecodedPayload.TerminalBallistics parseTerminalBallistics(ArgValue val) {
        if (val instanceof ArgValue.DictVal td) {
            var d = td.entries();
            return new DecodedPayload.TerminalBallistics(
                extractVec3(d.get("position")),
                extractVec3(d.get("velocity")),
                intFromArg(d.get("detonator_activated")) != 0,
                floatFromArg(d.get("material_angle"))
            );
        }
        return null;
    }

    // ── Aviation ───────────────────────────────────────────────────────

    private DecodedPayload decodeWardAdded(EntityId entityId, NamedArgs args) {
        long planeId = longFromArg(args.size() >= 2 ? args.get(1) : args.getFirst());
        Vec3 pos     = extractVec3(args.size() >= 3 ? args.get(2) : null);
        float radius = args.size() >= 4 ? floatFromArg(args.get(3)) : 0f;
        int ownerId  = args.size() >= 5 ? intFromArg(args.get(4)) : 0;
        return new DecodedPayload.WardAddedPayload(
            entityId, planeId, pos, radius, new EntityId(ownerId));
    }

    private DecodedPayload decodeWardRemoved(EntityId entityId, NamedArgs args) {
        long planeId = longFromArg(args.size() >= 2 ? args.get(1) : args.getFirst());
        return new DecodedPayload.WardRemovedPayload(entityId, planeId);
    }

    private DecodedPayload decodePlaneAdded(EntityId entityId, NamedArgs args) {
        long planeId   = longFromArg(args.get(0));
        int teamId     = intFromArg(args.get(1));
        long paramsId  = longFromArg(args.get(2));
        float x = 0, z = 0;
        if (args.size() >= 4) {
            Vec3 pos = extractVec3(args.get(3));
            x = pos.x(); z = pos.z();
        }
        return new DecodedPayload.PlaneAddedPayload(
            entityId, planeId, teamId, new GameParamId(paramsId), x, z);
    }

    private DecodedPayload decodePlaneRemoved(EntityId entityId, NamedArgs args) {
        long planeId = longFromArg(args.get(0));
        return new DecodedPayload.PlaneRemovedPayload(entityId, planeId);
    }

    private DecodedPayload decodePlanePosition(EntityId entityId, NamedArgs args) {
        long planeId = longFromArg(args.get(0));
        float x = 0, z = 0;
        if (args.size() >= 2) {
            Vec3 pos = extractVec3(args.get(1));
            x = pos.x(); z = pos.z();
        }
        return new DecodedPayload.PlanePositionPayload(entityId, planeId, x, z);
    }

    // ── Gun sync / Ammo ────────────────────────────────────────────────

    private DecodedPayload decodeGunSync(EntityId entityId, NamedArgs args) {
        int weaponType = intFromArg(args.get(0));
        int gunId      = intFromArg(args.get(1));
        float yaw      = floatFromArg(args.get(2));
        float pitch    = args.size() >= 4 ? floatFromArg(args.get(3)) : 0f;
        return new DecodedPayload.GunSyncPayload(entityId, weaponType, gunId, yaw, pitch);
    }

    private DecodedPayload decodeSetAmmo(EntityId entityId, NamedArgs args) {
        int weaponType    = intFromArg(args.get(0));
        long ammoParamId  = longFromArg(args.get(1));
        boolean isReload  = args.size() >= 3 && intFromArg(args.get(2)) != 0;
        return new DecodedPayload.SetAmmoForWeaponPayload(
            entityId, weaponType, new GameParamId(ammoParamId), isReload);
    }

    // ── Helpers ────────────────────────────────────────────────────────

    static Vec3 extractVec3(ArgValue val) {
        if (val == null) return new Vec3(0, 0, 0);
        if (val instanceof ArgValue.Vec3Val v3) return new Vec3(v3.x(), v3.y(), v3.z());
        if (val instanceof ArgValue.Vec2Val v2) return new Vec3(v2.x(), 0, v2.y());
        if (val instanceof ArgValue.ArrayVal arr && arr.elements().size() >= 3) {
            return new Vec3(
                floatFromArg(arr.elements().get(0)),
                floatFromArg(arr.elements().get(1)),
                floatFromArg(arr.elements().get(2))
            );
        }
        if (val instanceof ArgValue.ArrayVal arr && arr.elements().size() >= 2) {
            return new Vec3(
                floatFromArg(arr.elements().get(0)),
                0,
                floatFromArg(arr.elements().get(1))
            );
        }
        return new Vec3(0, 0, 0);
    }

    private static int intFromArg(ArgValue v) {
        return switch (v) {
            case ArgValue.IntVal iv  -> (int) iv.value();
            case ArgValue.FloatVal fv -> (int) fv.value();
            case ArgValue.BoolVal bv -> bv.value() ? 1 : 0;
            case null -> 0;
            default -> 0;
        };
    }

    private static long longFromArg(ArgValue v) {
        return switch (v) {
            case ArgValue.IntVal iv  -> iv.value();
            case ArgValue.FloatVal fv -> (long) fv.value();
            case null -> 0;
            default -> 0;
        };
    }

    private static float floatFromArg(ArgValue v) {
        return switch (v) {
            case ArgValue.FloatVal fv -> (float) fv.value();
            case ArgValue.IntVal iv  -> (float) iv.value();
            case null -> 0f;
            default -> 0f;
        };
    }

    private static String strFromArg(ArgValue v) {
        return switch (v) {
            case ArgValue.StrVal sv -> sv.value();
            case null -> "";
            default -> String.valueOf(v);
        };
    }

    private static byte[] blobFromArg(ArgValue v) {
        return switch (v) {
            case ArgValue.BlobVal bv -> bv.value();
            case null -> new byte[0];
            default -> new byte[0];
        };
    }

    // Pickle value helpers (values come from PickleDecoder.decode)
    static long longFromPickle(Object v) {
        if (v instanceof Long l) return l;
        if (v instanceof Double d) return d.longValue();
        if (v instanceof String s) {
            try { return Long.parseLong(s); } catch (NumberFormatException e) { return 0; }
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
        if (v == null) return "";
        return v.toString();
    }
}
