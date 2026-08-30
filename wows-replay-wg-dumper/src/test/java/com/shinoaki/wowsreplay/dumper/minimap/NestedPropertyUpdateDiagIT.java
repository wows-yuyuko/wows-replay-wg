package com.shinoaki.wowsreplay.dumper.minimap;

import com.shinoaki.wowsreplay.core.ReplayFile;
import com.shinoaki.wowsreplay.core.model.Version;
import com.shinoaki.wowsreplay.core.packet.EntityCreatePacket;
import com.shinoaki.wowsreplay.core.packet.Parser;
import com.shinoaki.wowsreplay.core.packet.PropertyUpdatePacket;
import com.shinoaki.wowsreplay.core.spec.EntitySpec;
import com.shinoaki.wowsreplay.core.spec.GameDataCache;
import com.shinoaki.wowsreplay.core.spec.Property;
import com.shinoaki.wowsreplay.core.spi.EntitySpecProvider;
import com.shinoaki.wowsreplay.core.types.ArgType;
import com.shinoaki.wowsreplay.core.types.ArgValue;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * NestedPropertyUpdate 值解码验证（capture-point-audit.md §3）。
 *
 * <p>（C:/Users/uuz/Documents/GitHub/wows-toolkit/crates/wows-replays/
 * src/）的位流路径走查 + 字节对齐叶子值解码，
 * 跑真实回放全部 NestedPropertyUpdate，验证「载荷不是 {levels,action} pickle，而是
 * 路径位 + 类型化叶子值」的诊断（capture-point-audit.md §3.3）。</p>
 *
 * <p>已确认语义：</p>
 * <ul>
 *   <li>顶层：cont(1) + propIdx(ceil(log2(numClientProps)))；</li>
 *   <li>FixedDict 索引 = ceil(log2(属性数)) bit；</li>
 *   <li>Array 索引 = ceil(log2(当前长度)) bit —— 依赖当前属性值，需维护值树；</li>
 *   <li>叶子值：路径位后补位到字节边界，再按 def schema（RPC 编码）读取。</li>
 * </ul>
 */
@Slf4j
class NestedPropertyUpdateDiagIT {

    private static final String REPLAY_PATH =
            "temp/wg_15.6/20260730_013138_PASB720-Rhode-Island_56_AngelWings.wowsreplay";
    private static final String WOWS_DATA_PATH = "temp/wows-data";

    private static ReplayFile replay;
    private static Version version;
    private static EntitySpecProvider specProvider;

    @BeforeAll
    static void setUp() throws Exception {
        var base = resolve(WOWS_DATA_PATH);
        replay = ReplayFile.fromFile(resolve(REPLAY_PATH), base);
        version = replay.version();
        assertNotNull(GameDataCache.resolveGameDataDir(replay), "游戏数据未找到: " + base);
        specProvider = GameDataCache.withMaxSize(4).gameData(replay).entitySpecs();
    }

    private static Path resolve(String path) {
        return Path.of(System.getProperty("user.dir")).getParent().resolve(path);
    }

    // ──  ───────────────────────────────────

    static final class BitReader {
        final byte[] data;
        int bitOffset;

        BitReader(byte[] data) { this.data = data; }

        long read(int n) {
            long value = 0;
            for (int i = 0; i < n; i++) {
                if (bitOffset >= data.length * 8) break;
                int byteIdx = bitOffset / 8;
                int bitIdx = 7 - (bitOffset % 8);
                value = (value << 1) | ((data[byteIdx] >> bitIdx) & 1);
                bitOffset++;
            }
            return value;
        }

        int remaining() { return data.length * 8 - bitOffset; }

        void alignByte() {
            while (remaining() % 8 != 0) read(1);
        }

        byte[] rest() {
            alignByte();
            int n = remaining() / 8;
            byte[] out = new byte[n];
            System.arraycopy(data, bitOffset / 8, out, 0, n);
            bitOffset += n * 8;
            return out;
        }
    }

    /** next_power_of_two.trailing_zeros == ceil(log2(n))。 */
    static int bitWidthFor(int n) {
        if (n <= 1) return 0;
        return Integer.SIZE - Integer.numberOfLeadingZeros(n - 1);
    }

    /** 去掉 Named/UserType 透明包装（t.peeled）。 */
    static ArgType peel(ArgType t) {
        while (true) {
            if (t instanceof ArgType.NamedType nt) t = nt.inner();
            else if (t instanceof ArgType.UserType ut) t = ut.inner();
            else return t;
        }
    }

    private static boolean isPrimitive(ArgType t) {
        return t instanceof ArgType.Primitive;
    }

    private static int arrLength(ArgValue v) {
        return v instanceof ArgValue.ArrayVal a ? a.elements().size() : 0;
    }

    private static ArgValue arrayElem(ArgValue v, int idx, ArgType elementType) {
        if (v instanceof ArgValue.ArrayVal a && idx < a.elements().size()) {
            return a.elements().get(idx);
        }
        return new ArgValue.NullVal();
    }

    private static ArgValue dictGet(ArgValue v, String key) {
        if (v instanceof ArgValue.DictVal d) {
            var e = d.entries();
            if (e.containsKey(key)) return e.get(key);
        }
        return new ArgValue.NullVal();
    }

    sealed interface Action {
        record SetKey(String key, ArgValue value) implements Action {}
        record SetElement(int index, ArgValue value) implements Action {}
        record SetRange(int start, int stop, List<ArgValue> values) implements Action {}
        record RemoveRange(int start, int stop) implements Action {}
    }

    record Walk(List<String> levels, Action action) {}

    /** 终端命令（nested_update_command，cont==0 到达更新层）。 */
    static Walk terminalCommand(boolean isSlice, ArgType t, ArgValue value, BitReader r) {
        var p = peel(t);
        if (p instanceof ArgType.FixedDict fixed) {
            int idx = (int) r.read(bitWidthFor(fixed.properties().size()));
            var entry = fixed.properties().get(idx);
            ArgValue v = parseWire(entry.propType(), r.rest());
            return new Walk(List.of(), new Action.SetKey(entry.name(), v));
        } else if (p instanceof ArgType.Array arr) {
            int len = arrLength(value);
            int idxBits = bitWidthFor(isSlice ? len + 1 : len);
            int idx1 = (int) r.read(idxBits);
            Integer idx2 = isSlice ? (int) r.read(idxBits) : null;
            byte[] rest = r.rest();
            List<ArgValue> elems = new ArrayList<>();
            int off = 0;
            while (off < rest.length) {
                var parsed = parseWirePrefix(arr.elementType(), rest, off);
                if (parsed == null || parsed.consumed == 0) break;
                elems.add(parsed.value);
                off += parsed.consumed;
            }
            if (isSlice) {
                return new Walk(List.of(), new Action.SetRange(idx1, idx2, elems));
            }
            if (elems.isEmpty()) throw new IllegalStateException("non-slice element set with empty value");
            return new Walk(List.of(), new Action.SetElement(idx1, elems.get(0)));
        }
        throw new IllegalStateException("terminal command on unsupported type: " + t.typeName());
    }

    /** 递归走查（get_nested_prop_path_helper，含 scalar 叶子分支）。 */
    static Walk walk(boolean isSlice, ArgType t, ArgValue value, BitReader r) {
        var p = peel(t);
        int cont = (int) r.read(1);
        if (cont == 0) {
            return terminalCommand(isSlice, p, value, r);
        }
        if (p instanceof ArgType.FixedDict fixed) {
            int idx = (int) r.read(bitWidthFor(fixed.properties().size()));
            var prop = fixed.properties().get(idx);
            if (isPrimitive(peel(prop.propType()))) {
                // scalar 叶子：无 cont 位，字节对齐后读值（对应 新版 read_aligned_scalar）
                ArgValue v = parseWire(prop.propType(), r.rest());
                return new Walk(List.of(), new Action.SetKey(prop.name(), v));
            }
            var inner = walk(isSlice, prop.propType(), dictGet(value, prop.name()), r);
            var levels = new ArrayList<String>(inner.levels);
            levels.add(0, prop.name());
            return new Walk(levels, inner.action());
        } else if (p instanceof ArgType.Array arr) {
            int len = arrLength(value);
            int idx = (int) r.read(bitWidthFor(len));
            if (isPrimitive(peel(arr.elementType()))) {
                ArgValue v = parseWire(arr.elementType(), r.rest());
                return new Walk(List.of("[" + idx + "]"), new Action.SetElement(idx, v));
            }
            var inner = walk(isSlice, arr.elementType(), arrayElem(value, idx, arr.elementType()), r);
            var levels = new ArrayList<String>(inner.levels);
            levels.add(0, "[" + idx + "]");
            return new Walk(levels, inner.action());
        }
        throw new IllegalStateException("walk into unsupported type: " + t.typeName());
    }

    // ── 线值解码（BigWorld RPC，little-endian，对应 Parser.parseValue）──────

    record Parsed(ArgValue value, int consumed) {}

    static ArgValue parseWire(ArgType t, byte[] data) {
        var p = parseWirePrefix(t, data, 0);
        return p != null ? p.value : new ArgValue.NullVal();
    }

    static Parsed parseWirePrefix(ArgType t, byte[] data, int off) {
        var pt = peel(t);
        if (pt instanceof ArgType.Primitive prim) {
            var buf = ByteBuffer.wrap(data, off, data.length - off).order(ByteOrder.LITTLE_ENDIAN);
            try {
                return switch (prim) {
                    case INT8    -> new Parsed(new ArgValue.IntVal(buf.get()), 1);
                    case UINT8   -> new Parsed(new ArgValue.IntVal(buf.get() & 0xFF), 1);
                    case INT16   -> new Parsed(new ArgValue.IntVal(buf.getShort()), 2);
                    case UINT16  -> new Parsed(new ArgValue.IntVal(buf.getShort() & 0xFFFF), 2);
                    case INT32   -> new Parsed(new ArgValue.IntVal(buf.getInt()), 4);
                    case UINT32  -> new Parsed(new ArgValue.IntVal(Integer.toUnsignedLong(buf.getInt())), 4);
                    case INT64, UINT64 -> new Parsed(new ArgValue.IntVal(buf.getLong()), 8);
                    case FLOAT   -> new Parsed(new ArgValue.FloatVal(buf.getFloat()), 4);
                    case DOUBLE  -> new Parsed(new ArgValue.FloatVal(buf.getDouble()), 8);
                    case BOOL    -> new Parsed(new ArgValue.BoolVal(buf.get() != 0), 1);
                    case STRING, BLOB, PYTHON -> {
                        int len = buf.get() & 0xFF;
                        if (len == 0xFF) {
                            len = buf.getShort() & 0xFFFF;
                            buf.get();
                        }
                        byte[] bytes = new byte[len];
                        buf.get(bytes);
                        yield new Parsed(new ArgValue.BlobVal(bytes), 1 + (len == 0xFF ? 4 : 0) + len);
                    }
                    default -> new Parsed(new ArgValue.NullVal(), 0);
                };
            } catch (Exception e) {
                return null;
            }
        } else if (pt instanceof ArgType.Array arr) {
            int count = data[off] & 0xFF;
            int cursor = off + 1;
            List<ArgValue> elems = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                var p = parseWirePrefix(arr.elementType(), data, cursor);
                if (p == null) return null;
                elems.add(p.value);
                cursor += p.consumed;
            }
            return new Parsed(new ArgValue.ArrayVal(elems), cursor - off);
        } else if (pt instanceof ArgType.FixedDict fixed) {
            int cursor = off;
            if (fixed.allowNone()) {
                int flag = data[cursor++] & 0xFF;
                if (flag == 0) return new Parsed(new ArgValue.NullVal(), cursor - off);
            }
            Map<String, ArgValue> map = new LinkedHashMap<>();
            for (var prop : fixed.properties()) {
                var p = parseWirePrefix(prop.propType(), data, cursor);
                if (p == null) return null;
                map.put(prop.name(), p.value);
                cursor += p.consumed;
            }
            return new Parsed(new ArgValue.DictVal(map), cursor - off);
        } else if (pt instanceof ArgType.Tuple tuple) {
            int cursor = off;
            List<ArgValue> elems = new ArrayList<>();
            for (var et : tuple.elementTypes()) {
                var p = parseWirePrefix(et, data, cursor);
                if (p == null) return null;
                elems.add(p.value);
                cursor += p.consumed;
            }
            return new Parsed(new ArgValue.TupleVal(elems), cursor - off);
        }
        return null;
    }

    // ── 验证主体 ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("NestedPropertyUpdate 值解码：位流路径 + 类型化叶子值（非 pickle）")
    void verifyNestedValueDecoding() {
        var parser = new Parser(specProvider, replay.version());
        var iter = replay.packetIterator();

        var entitySpecs = new HashMap<Integer, String>();
        var entityValues = new HashMap<Integer, Map<String, ArgValue>>();
        Set<String> seenPaths = new LinkedHashSet<>();
        var progressUpdates = new ArrayList<Double>();
        var scoreUpdates = new ArrayList<Long>();
        var teamIdUpdates = new ArrayList<Long>();

        int total = 0, decoded = 0, failed = 0;

        while (iter.hasNext()) {
            var raw = iter.next();
            var packet = parser.parse(raw);
            if (packet == null || packet.payload() == null) continue;

            switch (packet.payload()) {
                case EntityCreatePacket ec -> {
                    entitySpecs.put(ec.entityId().value(), ec.entityType());
                    entityValues.put(ec.entityId().value(), ec.props());
                }
                case PropertyUpdatePacket pu -> {
                    total++;
                    String entityType = entitySpecs.getOrDefault(pu.entityId().value(), "?");
                    var spec = findSpec(parser, entityType);
                    Property prop = null;
                    if (spec != null) {
                        for (var p : spec.clientProperties()) {
                            if (p.name().equals(pu.property())) { prop = p; break; }
                        }
                    }
                    byte[] payload = (byte[]) pu.updateCmd();
                    if (prop == null) { failed++; continue; }
                    try {
                        var r = new BitReader(payload);
                        int cont = (int) r.read(1);
                        if (cont != 1) { failed++; continue; }
                        int numProps = spec.clientProperties().size();
                        int propIdx = (int) r.read(bitWidthFor(numProps));
                        if (propIdx >= numProps
                            || !spec.clientProperties().get(propIdx).name().equals(pu.property())) {
                            failed++;
                            continue;
                        }
                        ArgValue current = entityValues
                            .getOrDefault(pu.entityId().value(), Map.of())
                            .getOrDefault(pu.property(), new ArgValue.NullVal());
                        var walk = walk(false, prop.propType(), current, r);
                        decoded++;

                        if (walk.levels().isEmpty()) {
                            seenPaths.add(entityType + "::" + pu.property() + "::<leaf>");
                        } else {
                            seenPaths.add(entityType + "::" + pu.property() + "::"
                                + String.join(".", walk.levels()));
                        }

                        // 占领点进度：componentsState.captureLogic.progress = FLOAT
                        if (entityType.equals("InteractiveZone") && pu.property().equals("componentsState")
                            && walk.levels().size() == 1 && walk.levels().get(0).equals("captureLogic")
                            && walk.action() instanceof Action.SetKey sk && sk.key().equals("progress")
                            && sk.value() instanceof ArgValue.FloatVal f) {
                            progressUpdates.add(f.value());
                        }
                        // 队伍比分：state.missions.teamsScore[*].score = UINT16
                        if (entityType.equals("BattleLogic") && pu.property().equals("state")
                            && walk.levels().stream().anyMatch(l -> l.equals("teamsScore"))
                            && walk.action() instanceof Action.SetKey sk && sk.key().equals("score")
                            && sk.value() instanceof ArgValue.IntVal iv) {
                            scoreUpdates.add(iv.value());
                        }
                        // 占领点团队：componentsState.captureLogic.invaderTeam / teamId
                        if (entityType.equals("InteractiveZone") && pu.property().equals("componentsState")
                            && walk.levels().size() == 1 && walk.levels().get(0).equals("captureLogic")
                            && walk.action() instanceof Action.SetKey sk
                            && (sk.key().equals("invaderTeam") || sk.key().equals("teamId"))
                            && sk.value() instanceof ArgValue.IntVal iv) {
                            teamIdUpdates.add(iv.value());
                        }
                    } catch (Exception e) {
                        failed++;
                    }
                }
                default -> {}
            }
        }

        log.info("=== NestedPropertyUpdate: total={} decoded={} failed={} ===", total, decoded, failed);
        seenPaths.stream().limit(20).forEach(p -> log.info("  {}", p));
        if (!progressUpdates.isEmpty()) {
            log.info("captureLogic.progress 序列(前20): {}", progressUpdates.stream().limit(20).map(String::valueOf).toList());
        }
        if (!scoreUpdates.isEmpty()) {
            log.info("teamsScore.score 样例(前20): {}", scoreUpdates.stream().limit(20).map(String::valueOf).toList());
        }
        if (!teamIdUpdates.isEmpty()) {
            log.info("captureLogic invaderTeam/teamId 样例(前20): {}", teamIdUpdates.stream().limit(20).map(String::valueOf).toList());
        }

        // 核心断言：能按位流路径+叶子值解码（非 pickle），且占领点/比分解出合理值
        assertTrue(decoded > 0, "应能按位流路径+叶子值解码 NestedPropertyUpdate（非 pickle）");
        assertFalse(progressUpdates.isEmpty(), "应解出 captureLogic.progress 更新");
        for (double p : progressUpdates) {
            assertTrue(p >= 0 && p <= 1, "progress 应在 [0,1]: " + p);
        }
        assertFalse(scoreUpdates.isEmpty(), "应解出 teamsScore.score 更新");
        assertTrue(decoded >= total * 0.7,
            "解码成功率应较高（无值追踪下嵌套数组更新解不出，>=70% 即可）: " + decoded + "/" + total);
    }

    private static EntitySpec findSpec(Parser parser, String entityType) {
        for (var s : parser.specs()) {
            if (s.name().equals(entityType)) return s;
        }
        return null;
    }

    private static String hex(byte[] data) {
        var sb = new StringBuilder(data.length * 3);
        for (var b : data) sb.append(String.format("%02x ", b));
        return sb.toString().trim();
    }
}
