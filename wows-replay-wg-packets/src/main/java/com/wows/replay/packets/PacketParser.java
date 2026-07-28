package com.wows.replay.packets;

import com.wows.replay.core.PacketTypeId;
import com.wows.replay.core.RawPacket;
import com.wows.replay.spec.entity.EntitySpec;
import com.wows.replay.spec.entity.PropertySpec;
import com.wows.replay.spec.rpc.ArgType;
import com.wows.replay.spec.rpc.ArgValue;
import com.wows.replay.spec.spi.EntitySpecProvider;
import com.wows.replay.spec.types.*;

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
 */
public class PacketParser {

    private final List<EntitySpec> specs;
    private final Map<Integer, EntityState> entities = new HashMap<>();
    private final Version version;
    private final List<PayloadDiagnostic> diagnostics = new ArrayList<>();

    /**
     * Create a parser with entity specs.
     */
    public PacketParser(List<EntitySpec> specs, Version version) {
        this.specs = specs != null ? specs : List.of();
        this.version = version;
    }

    /**
     * Create a parser by loading specs from a provider.
     */
    public PacketParser(EntitySpecProvider specProvider, Version version) {
        this(specProvider != null ? specProvider.loadSpecs(version) : List.of(), version);
    }

    /**
     * Create a parser without entity specs (spec-independent packets only).
     */
    public PacketParser() {
        this(List.of(), null);
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
            case BASE_PLAYER_CREATE   -> parseBasePlayerCreate(raw);
            case BASE_PLAYER_CREATE_STUB -> parseBasePlayerCreateStub(raw);
            case CELL_PLAYER_CREATE   -> parseCellPlayerCreate(raw);
            case ENTITY_CREATE        -> parseEntityCreate(raw);
            case ENTITY_PROPERTY      -> parseEntityProperty(raw);
            case ENTITY_METHOD        -> parseEntityMethod(raw);
            case NESTED_PROPERTY_UPDATE -> parseNestedPropertyUpdate(raw);
        };
    }

    // ── Spec-independent parsers ────────────────────────────────────────────

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
        String json = new String(raw.payload(), StandardCharsets.UTF_8);
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

    // ── Entity-dependent parsers (require EntitySpec) ────────────────────────

    private Packet parseBasePlayerCreate(RawPacket raw) {
        if (specs.isEmpty()) return Packet.unknown(raw);
        try {
            var buf = buffer(raw.payload());
            var eid = new EntityId(buf.getInt());
            int entityType = buf.getShort() & 0xFFFF;
            var spec = getSpec(entityType, "BasePlayerCreate");

            var props = new LinkedHashMap<String, ArgValue>();
            var storedProps = new ArrayList<ArgValue>();
            for (int i = 0; i < spec.baseProperties().size(); i++) {
                var propSpec = spec.baseProperties().get(i);
                var value = parseValue(buf, propSpec.propType());
                props.put(propSpec.name(), value);
                storedProps.add(value);
            }

            byte[] componentData = new byte[buf.remaining()];
            buf.get(componentData);

            entities.put(eid.value(), new EntityState(entityType, storedProps));

            return Packet.fromRaw(raw, new BasePlayerCreatePacket(eid, spec.name(), props, componentData), new byte[0]);
        } catch (Exception e) {
            return Packet.invalid(raw, "BasePlayerCreate parse error: " + e.getMessage());
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

            entities.put(eid.value(), new EntityState(entityType, List.of()));

            return Packet.fromRaw(raw, new BasePlayerCreatePacket(eid, spec.name(), Map.of(), componentData), new byte[0]);
        } catch (Exception e) {
            return Packet.invalid(raw, "BasePlayerCreateStub parse error: " + e.getMessage());
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
                var value = parseValue(propsBuffer, propSpec.propType());
                props.put(propSpec.name(), value);
            }

            byte[] componentData = new byte[buf.remaining()];
            buf.get(componentData);

            return Packet.fromRaw(raw, new CellPlayerCreatePacket(eid, spec.name(), spaceId, vehicleId, pos, rot, props, componentData), remaining(raw, buf));
        } catch (Exception e) {
            return Packet.invalid(raw, "CellPlayerCreate parse error: " + e.getMessage());
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

            // Parse state: [num_props: u8][(prop_id: u8, value)...]
            int numProps = buf.get() & 0xFF;
            var props = new LinkedHashMap<String, ArgValue>();
            var storedProps = new ArrayList<ArgValue>();
            for (int i = 0; i < numProps && buf.hasRemaining(); i++) {
                int propId = buf.get() & 0xFF;
                if (propId >= spec.clientProperties().size()) break;
                var propSpec = spec.clientProperties().get(propId);
                var value = parseValue(buf, propSpec.propType());
                props.put(propSpec.name(), value);
                storedProps.add(value);
            }

            entities.put(eid.value(), new EntityState(entityType, storedProps));

            return Packet.fromRaw(raw, new EntityCreatePacket(eid, entityType, spec.name(), spaceId, vehicleId, pos, rot, stateLen, props), remaining(raw, buf));
        } catch (Exception e) {
            return Packet.invalid(raw, "EntityCreate parse error: " + e.getMessage());
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

            int consumed = raw.payload().length - 4 - 4 - 4 - buf.remaining();
            if (consumed < payloadLen) {
                diagnostics.add(new PayloadDiagnostic("EntityProperty::" + spec.name() + "::" + propSpec.name(),
                    payloadLen, consumed));
            }

            return Packet.fromRaw(raw, new EntityPropertyPacket(eid, propSpec.name(), value), remaining(raw, buf));
        } catch (Exception e) {
            return Packet.invalid(raw, "EntityProperty parse error: " + e.getMessage());
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
            var methodSpec = spec.clientMethods().get(methodId);

            var args = new ArrayList<ArgValue>();
            for (int i = 0; i < methodSpec.args().size() && buf.hasRemaining(); i++) {
                var argSpec = methodSpec.args().get(i);
                args.add(parseValue(buf, argSpec.argType()));
            }

            return Packet.fromRaw(raw, new EntityMethodPacket(eid, methodSpec.name(), args), remaining(raw, buf));
        } catch (Exception e) {
            return Packet.invalid(raw, "EntityMethod parse error: " + e.getMessage());
        }
    }

    private Packet parseNestedPropertyUpdate(RawPacket raw) {
        // Nested property updates are complex — for initial implementation,
        // return as unknown if no specs
        if (specs.isEmpty()) return Packet.unknown(raw);
        try {
            var buf = buffer(raw.payload());
            var eid = new EntityId(buf.getInt());
            int isSlice = buf.get() & 0xFF;
            int payloadSize = buf.getInt();

            byte[] payload = new byte[Math.min(payloadSize, buf.remaining())];
            buf.get(payload);

            // For now, treat nested property updates as raw blobs
            return Packet.fromRaw(raw, new PropertyUpdatePacket(eid, "nested", payload), new byte[0]);
        } catch (Exception e) {
            return Packet.invalid(raw, "NestedPropertyUpdate parse error: " + e.getMessage());
        }
    }

    // ── RPC value parsing ───────────────────────────────────────────────────

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
            case ArgType.Array(var elem)  -> readArray(buf, elem);
            case ArgType.Tuple(var elems) -> readTuple(buf, elems);
            case ArgType.NamedType(var _, var inner) -> parseValue(buf, inner);
        };
    }

    private ArgValue readString(ByteBuffer buf) {
        int len = buf.getInt();
        if (len < 0 || len > buf.remaining()) {
            return new ArgValue.StrVal("[invalid string length: " + len + "]");
        }
        byte[] bytes = new byte[len];
        buf.get(bytes);
        return new ArgValue.StrVal(new String(bytes, StandardCharsets.UTF_8));
    }

    private ArgValue readBlob(ByteBuffer buf) {
        int len = buf.getInt();
        if (len < 0 || len > buf.remaining()) {
            return new ArgValue.BlobVal(new byte[0]);
        }
        byte[] bytes = new byte[len];
        buf.get(bytes);
        return new ArgValue.BlobVal(bytes);
    }

    private ArgValue readArray(ByteBuffer buf, ArgType elementType) {
        int count = buf.getInt();
        if (count < 0 || count > 100000) count = 0;
        var elements = new ArrayList<ArgValue>(count);
        for (int i = 0; i < count && buf.hasRemaining(); i++) {
            elements.add(parseValue(buf, elementType));
        }
        return new ArgValue.ArrayVal(elements);
    }

    private ArgValue readTuple(ByteBuffer buf, List<ArgType> elementTypes) {
        var elements = new ArrayList<ArgValue>(elementTypes.size());
        for (var type : elementTypes) {
            if (!buf.hasRemaining()) break;
            elements.add(parseValue(buf, type));
        }
        return new ArgValue.TupleVal(elements);
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

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

    private record EntityState(int entityType, List<ArgValue> properties) {}

    /**
     * Non-fatal parsing diagnostic: a method/property payload was not fully consumed.
     */
    public record PayloadDiagnostic(String context, int payloadLen, int consumed) {}
}
