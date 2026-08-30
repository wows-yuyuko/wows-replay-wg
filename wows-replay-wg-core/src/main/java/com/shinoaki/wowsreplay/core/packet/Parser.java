package com.shinoaki.wowsreplay.core.packet;

import com.shinoaki.wowsreplay.core.model.*;
import com.shinoaki.wowsreplay.core.spec.EntitySpec;
import com.shinoaki.wowsreplay.core.spi.EntitySpecProvider;
import com.shinoaki.wowsreplay.core.types.ArgType;
import com.shinoaki.wowsreplay.core.types.ArgValue;
import lombok.extern.slf4j.Slf4j;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Spec-aware packet payload decoder.
 *
 * <p>Takes a {@link RawPacket} and produces a {@link Packet} with a typed payload.
 * Entity-dependent packets (BasePlayerCreate, CellPlayerCreate, EntityCreate,
 * EntityProperty, EntityMethod, NestedPropertyUpdate) require an {@link EntitySpecProvider};
 * without one, those packets will be returned as {@link Packet#invalid} or
 * {@link Packet#unknown}.</p>
 *
 * <p>Spec-independent packets (Position, Camera, Map, GunMarker, PlayerNetStats,
 * etc.) are always decoded regardless of whether specs are available.</p>
 *
 * <p>Entity-dependent parsing is fail-soft: a property/method payload that cannot
 * be decoded logs a warning and is skipped rather than aborting the whole replay
 * (see doc §11.5).</p>
 */
@Slf4j
public class Parser {

    private final List<EntitySpec> specs;
    private final Map<Integer, EntityState> entities = new HashMap<>();
    private final Version version;
    private final List<PayloadDiagnostic> diagnostics = new ArrayList<>();

    /**
     * Create a parser with entity specs.
     */
    public Parser(List<EntitySpec> specs, Version version) {
        this.specs = specs != null ? specs : List.of();
        this.version = version;
    }

    /**
     * Create a parser by loading specs from a provider.
     */
    public Parser(EntitySpecProvider specProvider, Version version) {
        this(specProvider != null ? specProvider.loadSpecs(version) : List.of(), version);
    }

    /** Drain and return accumulated non-fatal diagnostics. */
    public List<PayloadDiagnostic> drainDiagnostics() {
        var drained = List.copyOf(diagnostics);
        diagnostics.clear();
        return drained;
    }

    /** The entity specs used by this parser. */
    public List<EntitySpec> specs() { return specs; }

    /**
     * Parse a single raw packet into a decoded {@link Packet}.
     */
    public Packet parse(RawPacket raw) {
        if (raw.isUnknown()) {
            return Packet.unknown(raw);
        }

        return switch (raw.packetType()) {
            case POSITION             -> parsePosition(raw);
            case PLAYER_ORIENTATION   -> parsePlayerOrientation(raw);
            case NON_VOLATILE_POSITION -> parseNonVolatilePosition(raw);
            case CAMERA               -> parseCamera(raw);
            case GUN_MARKER           -> parseGunMarker(raw);
            case PLAYER_NET_STATS     -> parsePlayerNetStats(raw);
            case MAP                  -> parseMap(raw);
            case SET_WEAPON_LOCK      -> parseSetWeaponLock(raw);
            case SUB_CONTROLLER       -> parseSubController(raw);
            case SHOT_TRACKING        -> parseShotTracking(raw);
            case CRUISE_STATE         -> parseCruiseState(raw);
            case SERVER_TIMESTAMP     -> parseServerTimestamp(raw);
            case SERVER_TICK          -> parseServerTick(raw);
            case OWN_SHIP             -> parseOwnShip(raw);
            case BATTLE_RESULTS       -> parseBattleResults(raw);
            case VERSION              -> parseVersion(raw);
            case ENTITY_ENTER         -> parseEntityEnter(raw);
            case ENTITY_LEAVE         -> parseEntityLeave(raw);
            case ENTITY_CONTROL       -> parseEntityControl(raw);
            case CAMERA_MODE          -> parseCameraMode(raw);
            case CAMERA_FREE_LOOK     -> parseCameraFreeLook(raw);
            case INIT_FLAG            -> parseInitFlag(raw);
            case INIT_MARKER          -> Packet.fromRaw(raw, "init_marker", new byte[0]);
            case UNKNOWN_0X2E         -> Packet.fromRaw(raw, raw.payload(), new byte[0]);
            case BASE_PLAYER_CREATE   -> parseBasePlayerCreate(raw);
            case BASE_PLAYER_CREATE_STUB -> parseBasePlayerCreateStub(raw);
            case CELL_PLAYER_CREATE   -> parseCellPlayerCreate(raw);
            case ENTITY_CREATE        -> parseEntityCreate(raw);
            case ENTITY_PROPERTY      -> parseEntityProperty(raw);
            case ENTITY_METHOD        -> parseEntityMethod(raw);
            case NESTED_PROPERTY_UPDATE -> parseNestedPropertyUpdate(raw);
        };
    }


    private Packet parsePosition(RawPacket raw) {
        var buf = buffer(raw.payload());
        var eid = new EntityId(buf.getInt());
        int spaceId = buf.getInt();
        var pos = readVec3(buf);
        var dir = readVec3(buf);
        var rot = readRot3(buf);
        boolean onGround = buf.get() != 0;
        checkRemaining(buf);
        return Packet.fromRaw(raw, new PositionPacket(eid, spaceId, pos, dir, rot, onGround), remaining(raw, buf));
    }

    private Packet parsePlayerOrientation(RawPacket raw) {
        var buf = buffer(raw.payload());
        var eid = new EntityId(buf.getInt());
        var parentId = new EntityId(buf.getInt());
        var pos = readVec3(buf);
        var rot = readRot3(buf);
        checkRemaining(buf);
        return Packet.fromRaw(raw, new PlayerOrientationPacket(eid, parentId, pos, rot), remaining(raw, buf));
    }

    private Packet parseNonVolatilePosition(RawPacket raw) {
        var buf = buffer(raw.payload());
        var eid = new EntityId(buf.getInt());
        int spaceId = buf.getInt();
        var pos = readVec3(buf);
        var rot = readRot3(buf);
        checkRemaining(buf);
        return Packet.fromRaw(raw, new NonVolatilePositionPacket(eid, spaceId, pos, rot), remaining(raw, buf));
    }

    private Packet parseCamera(RawPacket raw) {
        var buf = buffer(raw.payload());
        float[] quat = {buf.getFloat(), buf.getFloat(), buf.getFloat(), buf.getFloat()};
        var camPos = readVec3(buf);
        float fov = buf.getFloat();
        float unknown = buf.getFloat();
        var pos = readVec3(buf);
        var dir = readVec3(buf);
        return Packet.fromRaw(raw, new CameraPacket(quat, camPos, fov, unknown, pos, dir), remaining(raw, buf));
    }

    private Packet parseGunMarker(RawPacket raw) {
        var buf = buffer(raw.payload());
        var target = readVec3(buf);
        float diameter = buf.getFloat();
        var markerPos = readVec3(buf);
        var markerDir = readVec3(buf);
        float arcadeSize = buf.getFloat();
        float spg1 = buf.getFloat();
        float spg2 = buf.getFloat();
        return Packet.fromRaw(raw, new GunMarkerPacket(target, diameter, markerPos, markerDir, arcadeSize, new float[]{spg1, spg2}), remaining(raw, buf));
    }

    private Packet parsePlayerNetStats(RawPacket raw) {
        var buf = buffer(raw.payload());
        int packed = buf.getInt();
        int fps = packed & 0xFF;
        int ping = (packed >> 8) & 0xFFFF;
        boolean lagging = ((packed >> 24) & 1) != 0;
        return Packet.fromRaw(raw, new PlayerNetStatsPacket(fps, ping, lagging), remaining(raw, buf));
    }

    private Packet parseMap(RawPacket raw) {
        var buf = buffer(raw.payload());
        int spaceId = buf.getInt();
        long arenaId = buf.getLong();
        int u1 = buf.getInt();
        int u2 = buf.getInt();
        byte[] blob = new byte[128];
        buf.get(blob);
        int strLen = buf.getInt();
        byte[] nameBytes = new byte[strLen];
        buf.get(nameBytes);
        String mapName = new String(nameBytes, StandardCharsets.UTF_8);
        byte[] matrix = new byte[64]; // 4×4 f32 matrix
        buf.get(matrix);
        int unknown = buf.get() & 0xFF;
        return Packet.fromRaw(raw, new MapPacket(spaceId, arenaId, u1, u2, blob, mapName, unknown), remaining(raw, buf));
    }

    private Packet parseSetWeaponLock(RawPacket raw) {
        var buf = buffer(raw.payload());
        int weaponType = buf.getInt();
        int lockType = buf.getInt();
        var targetId = new EntityId(buf.getInt());
        return Packet.fromRaw(raw, new SetWeaponLockPacket(
            WeaponType.fromRaw(weaponType),
            WeaponLockType.fromRaw(lockType),
            targetId
        ), remaining(raw, buf));
    }

    private Packet parseSubController(RawPacket raw) {
        var buf = buffer(raw.payload());
        return Packet.fromRaw(raw, new SubControllerPacket(buf.getShort()), remaining(raw, buf));
    }

    private Packet parseShotTracking(RawPacket raw) {
        var buf = buffer(raw.payload());
        var eid = new EntityId(buf.getInt());
        long value = buf.getLong();
        return Packet.fromRaw(raw, new ShotTrackingPacket(eid, value), remaining(raw, buf));
    }

    private Packet parseCruiseState(RawPacket raw) {
        var buf = buffer(raw.payload());
        return Packet.fromRaw(raw, new CruiseStatePacket(buf.getInt(), buf.getInt()), remaining(raw, buf));
    }

    private Packet parseServerTimestamp(RawPacket raw) {
        var buf = buffer(raw.payload());
        return Packet.fromRaw(raw, new ServerTimestampPacket(buf.getDouble()), remaining(raw, buf));
    }

    private Packet parseServerTick(RawPacket raw) {
        var buf = buffer(raw.payload());
        return Packet.fromRaw(raw, new ServerTickPacket(buf.getDouble()), remaining(raw, buf));
    }

    private Packet parseOwnShip(RawPacket raw) {
        var buf = buffer(raw.payload());
        return Packet.fromRaw(raw, new OwnShipPacket(new EntityId(buf.getInt())), remaining(raw, buf));
    }

    private Packet parseBattleResults(RawPacket raw) {
        // 载荷 = [json_len: u32][json: UTF-8]，scan_alt_private_info
        byte[] payload = raw.payload();
        String json;
        if (payload.length >= 4) {
            var buf = buffer(payload);
            int len = buf.getInt();
            int avail = Math.min(len, buf.remaining());
            if (avail > 0) {
                json = new String(payload, 4, avail, StandardCharsets.UTF_8);
            } else {
                json = "";
            }
        } else {
            json = new String(payload, StandardCharsets.UTF_8);
        }
        return Packet.fromRaw(raw, new BattleResultsPacket(json), new byte[0]);
    }

    private Packet parseVersion(RawPacket raw) {
        // Version packet: [len: u32][bytes: UTF-8]
        var buf = buffer(raw.payload());
        int len = buf.getInt();
        byte[] vbytes = new byte[Math.min(len, buf.remaining())];
        buf.get(vbytes);
        return Packet.fromRaw(raw, new VersionPacket(new String(vbytes, StandardCharsets.UTF_8)), remaining(raw, buf));
    }

    private Packet parseEntityEnter(RawPacket raw) {
        var buf = buffer(raw.payload());
        return Packet.fromRaw(raw, new EntityEnterPacket(
            new EntityId(buf.getInt()), buf.getInt(), new GameParamId(buf.getInt())
        ), remaining(raw, buf));
    }

    private Packet parseEntityLeave(RawPacket raw) {
        var buf = buffer(raw.payload());
        return Packet.fromRaw(raw, new EntityLeavePacket(new EntityId(buf.getInt())), remaining(raw, buf));
    }

    private Packet parseEntityControl(RawPacket raw) {
        var buf = buffer(raw.payload());
        return Packet.fromRaw(raw, new EntityControlPacket(
            new EntityId(buf.getInt()), buf.get() != 0
        ), remaining(raw, buf));
    }

    private Packet parseCameraMode(RawPacket raw) {
        var buf = buffer(raw.payload());
        return Packet.fromRaw(raw, new CameraModePacket(buf.getInt()), remaining(raw, buf));
    }

    private Packet parseCameraFreeLook(RawPacket raw) {
        var buf = buffer(raw.payload());
        return Packet.fromRaw(raw, new CameraFreeLookPacket(buf.get() & 0xFF), remaining(raw, buf));
    }

    private Packet parseInitFlag(RawPacket raw) {
        var buf = buffer(raw.payload());
        return Packet.fromRaw(raw, new InitFlagPacket(buf.get() & 0xFF), remaining(raw, buf));
    }

    // ── Entity-dependent parsers (require EntitySpec) ─────────────────

    private Packet parseBasePlayerCreate(RawPacket raw) {
        if (specs.isEmpty()) return Packet.unknown(raw);
        try {
            var buf = buffer(raw.payload());
            var eid = new EntityId(buf.getInt());
            int entityType = buf.getShort() & 0xFFFF;
            var spec = getSpec(entityType, "BasePlayerCreate");

            var props = new LinkedHashMap<String, ArgValue>();
            for (int i = 0; i < spec.baseProperties().size(); i++) {
                var propSpec = spec.baseProperties().get(i);
                var value = parseValue(buf, propSpec.propType());
                props.put(propSpec.name(), value);
            }

            byte[] componentData = new byte[buf.remaining()];
            buf.get(componentData);

            entities.put(eid.value(), new EntityState(entityType, props));

            return Packet.fromRaw(raw, new BasePlayerCreatePacket(eid, spec.name(), props, componentData), new byte[0]);
        } catch (Exception e) {
            return Packet.invalid(raw, "BasePlayerCreate parse error: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private Packet parseBasePlayerCreateStub(RawPacket raw) {
        if (specs.isEmpty()) return Packet.unknown(raw);
        try {
            var buf = buffer(raw.payload());
            var eid = new EntityId(buf.getInt());
            int entityType = buf.getShort() & 0xFFFF;
            var spec = getSpec(entityType, "BasePlayerCreateStub");

            byte[] componentData = new byte[buf.remaining()];
            buf.get(componentData);

            entities.put(eid.value(), new EntityState(entityType, new LinkedHashMap<>()));

            return Packet.fromRaw(raw, new BasePlayerCreatePacket(eid, spec.name(), Map.of(), componentData), new byte[0]);
        } catch (Exception e) {
            return Packet.invalid(raw, "BasePlayerCreateStub parse error: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private Packet parseCellPlayerCreate(RawPacket raw) {
        if (specs.isEmpty()) return Packet.unknown(raw);
        try {
            var buf = buffer(raw.payload());
            var eid = new EntityId(buf.getInt());
            int spaceId = buf.getInt();
            var vehicleId = new GameParamId(buf.getInt());
            var pos = readVec3(buf);
            var rot = readRot3(buf);
            int propsLen = buf.getInt();

            var state = entities.get(eid.value());
            if (state == null) {
                return Packet.invalid(raw, "CellPlayerCreate for unknown entity " + eid);
            }
            var spec = getSpec(state.entityType, "CellPlayerCreate");

            var propsBuffer = buffer(raw.payload(), 4 + 4 + 4 + 12 + 12 + 4, propsLen);
            var props = new LinkedHashMap<String, ArgValue>();
            for (int i = 0; i < spec.internalProperties().size() && propsBuffer.hasRemaining(); i++) {
                var propSpec = spec.internalProperties().get(i);
                try {
                    var value = parseValue(propsBuffer, propSpec.propType());
                    props.put(propSpec.name(), value);
                } catch (Exception e) {
                    log.warn("CellPlayerCreate {} {}: internal prop[{}]={} parse failed: {}",
                        eid, spec.name(), i, propSpec.name(), e.toString());
                    break;
                }
            }

            byte[] componentData = new byte[buf.remaining()];
            buf.get(componentData);

            return Packet.fromRaw(raw, new CellPlayerCreatePacket(eid, spec.name(), spaceId, vehicleId, pos, rot, props, componentData), remaining(raw, buf));
        } catch (Exception e) {
            return Packet.invalid(raw, "CellPlayerCreate parse error: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private Packet parseEntityCreate(RawPacket raw) {
        if (specs.isEmpty()) return Packet.unknown(raw);
        try {
            var buf = buffer(raw.payload());
            var eid = new EntityId(buf.getInt());
            int entityType = buf.getShort() & 0xFFFF;
            var vehicleId = new GameParamId(buf.getInt());
            int spaceId = buf.getInt();
            var pos = readVec3(buf);
            var rot = readRot3(buf);
            int stateLen = buf.getInt();

            var spec = getSpec(entityType, "EntityCreate");

            // 先注册实体，即使属性解析失败，后续 EntityMethod/EntityProperty 仍可解析
            entities.put(eid.value(), new EntityState(entityType, new LinkedHashMap<>()));

            // Parse state: [num_props: u8][(prop_id: u8, value)...]
            int numProps = buf.get() & 0xFF;
            var props = new LinkedHashMap<String, ArgValue>();
            for (int i = 0; i < numProps && buf.hasRemaining(); i++) {
                int propId = buf.get() & 0xFF;
                if (propId >= spec.clientProperties().size()) break;
                var propSpec = spec.clientProperties().get(propId);
                try {
                    var value = parseValue(buf, propSpec.propType());
                    props.put(propSpec.name(), value);
                } catch (Exception e) {
                    log.warn("EntityCreate {} {}: prop[{}]={} parse failed: {}",
                        eid, spec.name(), propId, propSpec.name(), e.toString());
                    break;
                }
            }

            if (!props.isEmpty()) {
                entities.put(eid.value(), new EntityState(entityType, props));
            }

            return Packet.fromRaw(raw, new EntityCreatePacket(eid, entityType, spec.name(), spaceId, vehicleId, pos, rot, stateLen, props), remaining(raw, buf));
        } catch (Exception e) {
            return Packet.invalid(raw, "EntityCreate parse error: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private Packet parseEntityProperty(RawPacket raw) {
        if (specs.isEmpty()) return Packet.unknown(raw);
        try {
            var buf = buffer(raw.payload());
            var eid = new EntityId(buf.getInt());
            int propId = buf.getInt();
            int payloadLen = buf.getInt();

            var state = getEntityState(eid, "EntityProperty");
            var spec = getSpec(state.entityType, "EntityProperty");
            if (propId >= spec.clientProperties().size()) {
                return Packet.invalid(raw, "Property id " + propId + " out of bounds for " + spec.name());
            }
            var propSpec = spec.clientProperties().get(propId);

            var value = parseValue(buf, propSpec.propType());
            // 维护当前值（供 NestedPropertyUpdate 的 Array 索引位宽依赖）
            state.properties.put(propSpec.name(), value);

            int consumed = raw.payload().length - 4 - 4 - 4 - buf.remaining();
            if (consumed < payloadLen) {
                diagnostics.add(new PayloadDiagnostic("EntityProperty::" + spec.name() + "::" + propSpec.name(),
                    payloadLen, consumed));
            }

            return Packet.fromRaw(raw, new EntityPropertyPacket(eid, propSpec.name(), value), remaining(raw, buf));
        } catch (Exception e) {
            return Packet.invalid(raw, "EntityProperty parse error: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private Packet parseEntityMethod(RawPacket raw) {
        if (specs.isEmpty()) return Packet.unknown(raw);
        try {
            var buf = buffer(raw.payload());
            var eid = new EntityId(buf.getInt());
            int methodId = buf.getInt();
            int payloadLen = buf.getInt();

            var state = getEntityState(eid, "EntityMethod");
            var spec = getSpec(state.entityType, "EntityMethod");
            if (methodId >= spec.clientMethods().size()) {
                return Packet.invalid(raw, "Method id " + methodId + " out of bounds for " + spec.name());
            }
            var method = spec.clientMethods().get(methodId);

            var argNames = new ArrayList<String>();
            var argValues = new ArrayList<ArgValue>();

            for (int i = 0; i < method.args().size() && buf.hasRemaining(); i++) {
                var argSpec = method.args().get(i);
                argNames.add(argSpec.name());
                argValues.add(parseValue(buf, argSpec.argType()));
            }
            // Capture any remaining wire data that the spec didn't account for
            if (buf.hasRemaining()) {
                byte[] rest = new byte[buf.remaining()];
                buf.get(rest);
                argNames.add("__rest");
                argValues.add(new ArgValue.BlobVal(rest));
            }

            return Packet.fromRaw(raw, new EntityMethodPacket(eid, method.name(),
                new NamedArgs(argNames, argValues)), remaining(raw, buf));
        } catch (Exception e) {
            return Packet.invalid(raw, "EntityMethod parse error: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private Packet parseNestedPropertyUpdate(RawPacket raw) {
        if (specs.isEmpty()) return Packet.unknown(raw);
        try {
            var buf = buffer(raw.payload());
            var eid = new EntityId(buf.getInt());
            int isSlice = buf.get() & 0xFF;
            int payloadSize = buf.getInt();

            byte[] payload = new byte[Math.min(payloadSize, buf.remaining())];
            buf.get(payload);

            var state = entities.get(eid.value());
            if (state == null) {
                return Packet.invalid(raw, "NestedPropertyUpdate for unknown entity " + eid);
            }
            var spec = getSpec(state.entityType, "NestedPropertyUpdate");
            if (spec.clientProperties().isEmpty()) {
                return Packet.unknown(raw);
            }

            // 顶层：cont(1 bit, 恒 1) + propIdx(ceil(log2(numProps)) bit)
            int numProps = spec.clientProperties().size();
            int bitWidth = bitWidthFor(numProps);
            var bits = new BitReader(payload);
            int cont = bits.read(1);
            if (cont != 1) {
                return Packet.invalid(raw, "NestedPropertyUpdate: top-level cont != 1");
            }
            int propIdx = bits.read(bitWidth);
            if (propIdx >= numProps) {
                return Packet.invalid(raw, "NestedPropertyUpdate: propIdx " + propIdx + " out of bounds for " + spec.name());
            }
            var propSpec = spec.clientProperties().get(propIdx);

            // 走位流路径 + 解码类型化叶子值，
            // 并在属性当前值树上应用更新（Array 索引位宽依赖当前长度）。
            var properties = state.properties;
            ArgValue current = properties.getOrDefault(propSpec.name(), new ArgValue.NullVal());
            var result = walkNested((isSlice & 0x1) == 1, propSpec.propType(), current, bits);
            properties.put(propSpec.name(), result.value());

            return Packet.fromRaw(raw, new PropertyUpdatePacket(eid, propSpec.name(), payload,
                result.levels(), result.action()), new byte[0]);
        } catch (Exception e) {
            return Packet.invalid(raw, "NestedPropertyUpdate parse error: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    /** ceil(log2(n))，n.next_power_of_two.trailing_zeros。 */
    private static int bitWidthFor(int n) {
        return n > 1 ? Integer.SIZE - Integer.numberOfLeadingZeros(n - 1) : 0;
    }

    /** 去掉 NamedType/UserType 透明包装（ArgType::peeled）。 */
    private static ArgType peel(ArgType t) {
        while (true) {
            if (t instanceof ArgType.NamedType nt) t = nt.inner();
            else if (t instanceof ArgType.UserType ut) t = ut.inner();
            else return t;
        }
    }

    private static boolean isScalar(ArgType t) {
        return t instanceof ArgType.Primitive;
    }

    /** 走查结果：路径片段 + 更新命令 + 应用更新后的值。 */
    private record NestedResult(List<String> levels, NestedUpdate action, ArgValue value) {}

    /**
     * 递归走查嵌套属性路径（get_nested_prop_path_helper）。
     * 顺带把更新应用到 {@code value}；未物化（NullVal）容器会被替换为默认空容器并随返回值持久化。
     */
    private NestedResult walkNested(boolean isSlice, ArgType t, ArgValue value, BitReader r) {
        var p = peel(t);
        int cont = r.read(1);
        if (cont == 0) {
            return terminalCommand(isSlice, p, value, r);
        }
        if (p instanceof ArgType.FixedDict fixed) {
            int idx = (int) r.read(bitWidthFor(fixed.properties().size()));
            if (idx >= fixed.properties().size()) {
                throw new IllegalStateException("nested FixedDict index " + idx + " out of bounds ("
                    + fixed.properties().size() + ")");
            }
            var prop = fixed.properties().get(idx);
            var pair = ensureDict(value, fixed);
            Map<String, ArgValue> dict = pair.dict();
            if (isScalar(peel(prop.propType()))) {
                // scalar 叶子：无 cont 位，字节对齐后读值（read_aligned_scalar）
                ArgValue leaf = parseAlignedScalar(prop.propType(), r);
                dict.put(prop.name(), leaf);
                return new NestedResult(List.of(), new NestedUpdate.SetKey(prop.name(), leaf), pair.value());
            }
            var child = dict.get(prop.name());
            var inner = walkNested(isSlice, prop.propType(), child, r);
            dict.put(prop.name(), inner.value());
            var levels = new ArrayList<String>(inner.levels());
            levels.add(0, prop.name());
            return new NestedResult(levels, inner.action(), pair.value());
        } else if (p instanceof ArgType.Array arr) {
            var pair = ensureArray(value, arr.elementType());
            List<ArgValue> elems = pair.elems();
            int idx = (int) r.read(bitWidthFor(elems.size()));
            if (isScalar(peel(arr.elementType()))) {
                ArgValue leaf = parseAlignedScalar(arr.elementType(), r);
                ensureArraySize(elems, idx, arr.elementType());
                elems.set(idx, leaf);
                return new NestedResult(List.of("[" + idx + "]"), new NestedUpdate.SetElement(idx, leaf), pair.value());
            }
            ensureArraySize(elems, idx, arr.elementType());
            var child = elems.get(idx);
            var inner = walkNested(isSlice, arr.elementType(), child, r);
            elems.set(idx, inner.value());
            var levels = new ArrayList<String>(inner.levels());
            levels.add(0, "[" + idx + "]");
            return new NestedResult(levels, inner.action(), pair.value());
        }
        throw new IllegalStateException("nested property walk into unsupported type: " + t.typeName());
    }

    /** 终端更新命令（nested_update_command，cont==0 到达更新层）。 */
    private NestedResult terminalCommand(boolean isSlice, ArgType t, ArgValue value, BitReader r) {
        var p = peel(t);
        if (p instanceof ArgType.FixedDict fixed) {
            int idx = (int) r.read(bitWidthFor(fixed.properties().size()));
            if (idx >= fixed.properties().size()) {
                throw new IllegalStateException("terminal FixedDict index " + idx + " out of bounds ("
                    + fixed.properties().size() + ")");
            }
            var entry = fixed.properties().get(idx);
            ArgValue leaf = parseAlignedScalar(entry.propType(), r);
            var pair = ensureDict(value, fixed);
            pair.dict().put(entry.name(), leaf);
            return new NestedResult(List.of(), new NestedUpdate.SetKey(entry.name(), leaf), pair.value());
        } else if (p instanceof ArgType.Array arr) {
            var pair = ensureArray(value, arr.elementType());
            List<ArgValue> elems = pair.elems();
            int idxBits = bitWidthFor(isSlice ? elems.size() + 1 : elems.size());
            int idx1 = (int) r.read(idxBits);
            Integer idx2 = isSlice ? (int) r.read(idxBits) : null;
            byte[] rest = r.rest();
            var values = parseElements(rest, arr.elementType());

            if (isSlice) {
                sliceInsert(elems, idx1, idx2, values);
                return new NestedResult(List.of(), new NestedUpdate.SetRange(idx1, idx2, values), pair.value());
            }
            if (values.isEmpty()) {
                throw new IllegalStateException("non-slice element set with empty value");
            }
            ensureArraySize(elems, idx1, arr.elementType());
            elems.set(idx1, values.get(0));
            return new NestedResult(List.of("[" + idx1 + "]"), new NestedUpdate.SetElement(idx1, values.get(0)), pair.value());
        }
        throw new IllegalStateException("terminal command on unsupported type: " + t.typeName());
    }

    /** 取可变 dict；未物化时造默认空 dict 并作为替换值返回。 */
    private static DictPair ensureDict(ArgValue v, ArgType.FixedDict fixed) {
        if (v instanceof ArgValue.DictVal d) {
            return new DictPair(d.entries(), d);
        }
        var map = new LinkedHashMap<String, ArgValue>();
        for (var prop : fixed.properties()) {
            map.put(prop.name(), defaultArgValue(prop.propType()));
        }
        return new DictPair(map, new ArgValue.DictVal(map));
    }

    /** 取可变数组；未物化时造空数组并作为替换值返回。 */
    private static ArrayPair ensureArray(ArgValue v, ArgType elementType) {
        if (v instanceof ArgValue.ArrayVal a) {
            return new ArrayPair(a.elements(), a);
        }
        var list = new ArrayList<ArgValue>();
        return new ArrayPair(list, new ArgValue.ArrayVal(list));
    }

    private record DictPair(Map<String, ArgValue> dict, ArgValue value) {}
    private record ArrayPair(List<ArgValue> elems, ArgValue value) {}

    /** 字节对齐后按 def schema 读单个叶子值（read_aligned_scalar）。 */
    private ArgValue parseAlignedScalar(ArgType t, BitReader r) {
        byte[] rest = r.rest();
        return parseValue(ByteBuffer.wrap(rest).order(ByteOrder.LITTLE_ENDIAN), t);
    }

    /** 从剩余字节读一串同类型元素（终端数组 SetRange/SetElement）。 */
    private List<ArgValue> parseElements(byte[] rest, ArgType elementType) {
        var buf = ByteBuffer.wrap(rest).order(ByteOrder.LITTLE_ENDIAN);
        var out = new ArrayList<ArgValue>();
        while (buf.hasRemaining()) {
            int before = buf.position();
            var v = parseValue(buf, elementType);
            if (buf.position() == before) break; // 不前进 = 错位，停止
            out.add(v);
        }
        return out;
    }

    private void ensureArraySize(List<ArgValue> elems, int idx, ArgType elementType) {
        while (elems.size() <= idx) {
            elems.add(defaultArgValue(elementType));
        }
    }

    private static ArgValue defaultArgValue(ArgType t) {
        return switch (peel(t)) {
            case ArgType.Primitive p -> switch (p) {
                case INT8, INT16, INT32, INT64, UINT8, UINT16, UINT32, UINT64 -> new ArgValue.IntVal(0);
                case FLOAT, DOUBLE -> new ArgValue.FloatVal(0);
                case BOOL -> new ArgValue.BoolVal(false);
                case VECTOR2 -> new ArgValue.Vec2Val(0, 0);
                case VECTOR3 -> new ArgValue.Vec3Val(0, 0, 0);
                case VECTOR4 -> new ArgValue.Vec4Val(0, 0, 0, 0);
                default -> new ArgValue.NullVal();
            };
            case ArgType.Array a -> new ArgValue.ArrayVal(new ArrayList<>());
            case ArgType.FixedDict f -> {
                var map = new LinkedHashMap<String, ArgValue>();
                for (var prop : f.properties()) map.put(prop.name(), defaultArgValue(prop.propType()));
                yield new ArgValue.DictVal(map);
            }
            case ArgType.Tuple tuple -> {
                var elems = new ArrayList<ArgValue>();
                for (var et : tuple.elementTypes()) elems.add(defaultArgValue(et));
                yield new ArgValue.TupleVal(elems);
            }
            default -> new ArgValue.NullVal();
        };
    }

    /** Python 切片语义（slice_insert）：删 target[start..stop]，再在 start 插入 source。 */
    private static void sliceInsert(List<ArgValue> target, int start, int stop, List<ArgValue> source) {
        for (int i = start; i < stop; i++) {
            if (target.size() <= start) break;
            target.remove(start);
        }
        for (int i = 0; i < source.size(); i++) {
            target.add(Math.min(start + i, target.size()), source.get(i));
        }
    }

    /** MSB-first bit reader for nested-property payloads（BitReader）。 */
    private static final class BitReader {
        private final byte[] data;
        private int bitOffset;
        private final int totalBits;

        BitReader(byte[] data) {
            this.data = data;
            this.totalBits = data.length * 8;
        }

        int read(int nBits) {
            int value = 0;
            for (int i = 0; i < nBits; i++) {
                if (bitOffset >= totalBits) break;
                int byteIdx = bitOffset / 8;
                int bitIdx = 7 - (bitOffset % 8);
                value = (value << 1) | ((data[byteIdx] >> bitIdx) & 1);
                bitOffset++;
            }
            return value;
        }

        int remaining() {
            return totalBits - bitOffset;
        }

        /** 补位到字节边界并取出剩余字节（的 align + read_u8_slice）。 */
        byte[] rest() {
            while (remaining() % 8 != 0) read(1);
            int n = remaining() / 8;
            byte[] out = new byte[n];
            int start = bitOffset / 8;
            System.arraycopy(data, start, out, 0, n);
            bitOffset += n * 8;
            return out;
        }
    }

    // ── RPC value parsing ─────────────────────────────────────────────

    /**
     * Parse a single RPC value according to its type definition.
     */
    private ArgValue parseValue(ByteBuffer buf, ArgType type) {
        return switch (type) {
            case ArgType.Primitive p -> switch (p) {
                case INT8    -> new ArgValue.IntVal(buf.get());
                case UINT8   -> new ArgValue.IntVal(buf.get() & 0xFF);
                case INT16   -> new ArgValue.IntVal(buf.getShort());
                case UINT16  -> new ArgValue.IntVal(buf.getShort() & 0xFFFF);
                case INT32   -> new ArgValue.IntVal(buf.getInt());
                case UINT32  -> new ArgValue.IntVal(Integer.toUnsignedLong(buf.getInt()));
                case INT64   -> new ArgValue.IntVal(buf.getLong());
                case UINT64  -> new ArgValue.IntVal(buf.getLong()); // Java can't represent full u64
                case FLOAT   -> new ArgValue.FloatVal(buf.getFloat());
                case DOUBLE  -> new ArgValue.FloatVal(buf.getDouble());
                case BOOL    -> new ArgValue.BoolVal(buf.get() != 0);
                case STRING  -> readString(buf);
                case BLOB    -> readBlob(buf);
                case PYTHON  -> readBlob(buf);
                case VECTOR2 -> new ArgValue.Vec2Val(buf.getFloat(), buf.getFloat());
                case VECTOR3 -> new ArgValue.Vec3Val(buf.getFloat(), buf.getFloat(), buf.getFloat());
                case VECTOR4 -> new ArgValue.Vec4Val(buf.getFloat(), buf.getFloat(), buf.getFloat(), buf.getFloat());
            };
            case ArgType.Array(var fixed, var elem)  -> readArray(buf, fixed, elem);
            case ArgType.Tuple(var elems) -> readTuple(buf, elems);
            case ArgType.FixedDict fixed -> readFixedDict(buf, fixed);
            case ArgType.NamedType(var _, var inner) -> parseValue(buf, inner);
            case ArgType.UserType(var inner) -> parseValue(buf, inner); // 透明：裸内部类型
            case ArgType.AllowNone(var inner) -> readAllowNone(buf, inner);
        };
    }

    private ArgValue readString(ByteBuffer buf) {
        byte[] bytes = readLengthPrefixedBytes(buf);
        return new ArgValue.StrVal(new String(bytes, StandardCharsets.UTF_8));
    }

    private ArgValue readBlob(ByteBuffer buf) {
        byte[] bytes = readLengthPrefixedBytes(buf);
        return new ArgValue.BlobVal(bytes);
    }

    /** BigWorld RPC variable-length encoding: 1 byte if < 0xFF, else 0xFF + u16 + 1 unknown byte. */
    private byte[] readLengthPrefixedBytes(ByteBuffer buf) {
        int len = buf.get() & 0xFF;
        if (len == 0xFF) {
            len = buf.getShort() & 0xFFFF;
            buf.get(); // skip 1 unknown byte
        }
        if (len < 0 || len > buf.remaining()) {
            log.warn("长度前缀越界: len={} 但剩余 {} 字节，按空值处理", len, buf.remaining());
            len = 0;
        }
        byte[] bytes = new byte[len];
        buf.get(bytes);
        return bytes;
    }

    private ArgValue readArray(ByteBuffer buf, OptionalInt fixedSize, ArgType elementType) {
        int count;
        if (fixedSize.isPresent()) {
            // 固定长度数组：线路上没有计数字节，数量由 spec 决定
            count = fixedSize.getAsInt();
        } else {
            count = buf.get() & 0xFF;  // BigWorld RPC: 1-byte variable-length array count
            if (count > 250) count = 0; // sanity guard
        }
        var elements = new ArrayList<ArgValue>(count);
        for (int i = 0; i < count && buf.hasRemaining(); i++) {
            elements.add(parseValue(buf, elementType));
        }
        return new ArgValue.ArrayVal(elements);
    }

    /** AllowNone 类型：先读 1 字节存在标志（0=null，1=存在）。 */
    private ArgValue readAllowNone(ByteBuffer buf, ArgType inner) {
        int flag = buf.get() & 0xFF;
        if (flag == 0) return new ArgValue.NullVal();
        return parseValue(buf, inner);
    }

    private ArgValue readFixedDict(ByteBuffer buf, ArgType.FixedDict fixed) {
        // AllowNone flag
        if (fixed.allowNone()) {
            int flag = buf.get() & 0xFF;
            if (flag == 0) return new ArgValue.NullVal();
        }
        var entries = new LinkedHashMap<String, ArgValue>();
        for (var prop : fixed.properties()) {
            entries.put(prop.name(), parseValue(buf, prop.propType()));
        }
        return new ArgValue.DictVal(entries);
    }

    private ArgValue readTuple(ByteBuffer buf, List<ArgType> elementTypes) {
        var elements = new ArrayList<ArgValue>(elementTypes.size());
        for (var type : elementTypes) {
            if (!buf.hasRemaining()) break;
            elements.add(parseValue(buf, type));
        }
        return new ArgValue.TupleVal(elements);
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    private Vec3 readVec3(ByteBuffer buf) {
        return new Vec3(buf.getFloat(), buf.getFloat(), buf.getFloat());
    }

    private Rot3 readRot3(ByteBuffer buf) {
        return new Rot3(buf.getFloat(), buf.getFloat(), buf.getFloat());
    }

    private EntitySpec getSpec(int entityType, String context) {
        int idx = entityType - 1;
        if (idx < 0 || idx >= specs.size()) {
            throw new IllegalArgumentException(context + ": entity type " + entityType
                + " out of bounds (spec count=" + specs.size() + ")");
        }
        return specs.get(idx);
    }

    private EntityState getEntityState(EntityId eid, String context) {
        var state = entities.get(eid.value());
        if (state == null) {
            throw new IllegalArgumentException(context + ": unknown entity " + eid);
        }
        return state;
    }

    private static ByteBuffer buffer(byte[] data) {
        return ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
    }

    private static ByteBuffer buffer(byte[] data, int offset, int length) {
        return ByteBuffer.wrap(data, offset, length).order(ByteOrder.LITTLE_ENDIAN);
    }

    private static byte[] remaining(RawPacket raw, ByteBuffer buf) {
        if (buf.hasRemaining()) {
            byte[] rest = new byte[buf.remaining()];
            buf.get(rest);
            return rest;
        }
        return new byte[0];
    }

    private static void checkRemaining(ByteBuffer buf) {
        // No-op: leftover is captured in remaining() call
    }

    // ── Inner types ─────────────────────────────────────────────────────────

    private record EntityState(int entityType, Map<String, ArgValue> properties) {}

    /**
     * Non-fatal parsing diagnostic: a method/property payload was not fully consumed.
     */
    public record PayloadDiagnostic(String context, int payloadLen, int consumed) {}
}
