package com.wows.replay.pickle;

import lombok.extern.slf4j.Slf4j;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Minimal Python pickle protocol decoder supporting only the types needed for
 * BigWorld replay data.
 *
 * <p>Supported opcodes:</p>
 * <ul>
 * <li>PROTO (0x80) / FRAME (0x95)</li>
 * <li>EMPTY_LIST (]) / EMPTY_TUPLE ()) / EMPTY_DICT ({)</li>
 * <li>MARK (() / TUPLE (t) / TUPLE1-TUPLE3 (0x85-0x87)</li>
 * <li>BININT1 (K) / BININT2 (M) / BININT (J) / INT (I) / LONG1 (0x8a) / LONG4 (0x8b)</li>
 * <li>SHORT_BINSTRING (U) / BINUNICODE (T) / SHORT_BINUNICODE (0x8c) / BINUNICODE8 (0x8d)</li>
 * <li>STRING (S) / BINBYTES (B)</li>
 * <li>BINFLOAT (G) / FLOAT (F)</li>
 * <li>NONE (N)</li>
 * <li>APPEND (a) / APPENDS (e)</li>
 * <li>BINPUT (q) / LONG_BINPUT (0x81) / BINGET (h) / LONG_BINGET (0x82)</li>
 * <li>SETITEM (r) / SETITEMS (u)</li>
 * <li>STOP (.)</li>
 * </ul>
 *
 * <p>Arbitrary object deserialization (REDUCE, GLOBAL, etc.) is NOT supported.</p>
 */
@Slf4j
public final class PickleReader {

    private final byte[] data;
    private int pos;
    private final List<Object> stack = new ArrayList<>();
    private final List<Integer> marks = new ArrayList<>();
    private final Map<Integer, Object> memo = new HashMap<>();

    // ── Opcode audit ────────────────────────────────────────────────────

    /** Unknown opcodes encountered, with hit counts. */
    private static final Map<Integer, Integer> unknownOpcodes = new LinkedHashMap<>();
    /** Set of opcodes that have been logged as warnings. */
    private static final Set<Integer> warnedOpcodes = new HashSet<>();
    /** Total number of unknown opcode hits. */
    private static long unknownOpcodeCount = 0;

    /** Return audit info: map of unknown opcode → hit count, plus total hits. */
    public static Map<Integer, Integer> unknownOpcodeAudit() {
        synchronized (unknownOpcodes) {
            return new LinkedHashMap<>(unknownOpcodes);
        }
    }

    /** Total number of unknown-opcode events since the JVM started. */
    public static long unknownOpcodeCount() {
        return unknownOpcodeCount;
    }

    /** Reset opcode audit state (useful between runs). */
    public static void resetAudit() {
        synchronized (unknownOpcodes) {
            unknownOpcodes.clear();
            warnedOpcodes.clear();
            unknownOpcodeCount = 0;
        }
    }

    private PickleReader(byte[] data) {
        this.data = data;
        this.pos = 0;
    }

    /** 解码 pickle 字节，返回顶层值。 */
    public static Object decode(byte[] data) {
        if (data == null || data.length == 0) return null;
        var d = new PickleReader(data);
        return d.parse();
    }

    private Object parse() {
        while (pos < data.length) {
            int op = data[pos++] & 0xFF;
            switch (op) {
                case 0x80: // PROTO
                    pos++; // skip proto version byte
                    break;
                case '(': // MARK
                    marks.add(stack.size());
                    break;
                case '.': // STOP
                    return stack.isEmpty() ? null : stack.getLast();
                case '0': // POP
                    if (!stack.isEmpty()) stack.removeLast();
                    break;
                case ']': // EMPTY_LIST
                    stack.add(new ArrayList<>());
                    break;
                case ')': // EMPTY_TUPLE
                    stack.add(List.of());
                    break;
                case 'a': { // APPEND
                    var val = stack.removeLast();
                    @SuppressWarnings("unchecked")
                    var list = (List<Object>) stack.getLast();
                    list.add(val);
                    break;
                }
                case 'e': { // APPENDS
                    int mark = marks.isEmpty() ? 0 : marks.removeLast();
                    var slice = new ArrayList<>(stack.subList(mark, stack.size()));
                    stack.subList(mark, stack.size()).clear();
                    @SuppressWarnings("unchecked")
                    var list = (List<Object>) stack.getLast();
                    list.addAll(slice);
                    break;
                }
                case 't': { // TUPLE
                    int mark = marks.isEmpty() ? 0 : marks.removeLast();
                    var tuple = List.copyOf(stack.subList(mark, stack.size()));
                    stack.subList(mark, stack.size()).clear();
                    stack.add(tuple);
                    break;
                }
                case 0x85: // TUPLE1
                    stack.add(List.of(stack.removeLast()));
                    break;
                case 0x86: { // TUPLE2
                    var v2 = stack.removeLast();
                    var v1 = stack.removeLast();
                    stack.add(List.of(v1, v2));
                    break;
                }
                case 0x87: { // TUPLE3
                    var v3 = stack.removeLast();
                    var v2 = stack.removeLast();
                    var v1 = stack.removeLast();
                    stack.add(List.of(v1, v2, v3));
                    break;
                }
                case 'K': // BININT1 (1 byte unsigned)
                    stack.add((long) (data[pos++] & 0xFF));
                    break;
                case 'M': // BININT2 (2 bytes little-endian unsigned)
                    stack.add((long) ((data[pos++] & 0xFF) | ((data[pos++] & 0xFF) << 8)));
                    break;
                case 'J': { // BININT (4 bytes little-endian signed)
                    int v = (data[pos++] & 0xFF) | ((data[pos++] & 0xFF) << 8)
                          | ((data[pos++] & 0xFF) << 16) | (data[pos++] << 24);
                    stack.add((long) v);
                    break;
                }
                case 'I': { // INT (newline-terminated string repr)
                    var sb = new StringBuilder();
                    while (pos < data.length && data[pos] != '\n') sb.append((char) data[pos++]);
                    pos++; // skip \n
                    stack.add(Long.parseLong(sb.toString()));
                    break;
                }
                case 'G': { // BINFLOAT (8 bytes little-endian double)
                    long bits = 0;
                    for (int i = 0; i < 8; i++) bits |= (long) (data[pos++] & 0xFF) << (i * 8);
                    stack.add(Double.longBitsToDouble(bits));
                    break;
                }
                case 'F': { // FLOAT (newline-terminated string repr)
                    var sb = new StringBuilder();
                    while (pos < data.length && data[pos] != '\n') sb.append((char) data[pos++]);
                    pos++; // skip \n
                    stack.add(Double.parseDouble(sb.toString()));
                    break;
                }
                case 'U': { // SHORT_BINSTRING
                    int len = data[pos++] & 0xFF;
                    if (len < 0 || pos + len > data.length) { stack.add("[invalid]"); break; }
                    var s = new String(data, pos, len, StandardCharsets.UTF_8);
                    pos += len;
                    stack.add(s);
                    break;
                }
                case 'T': { // BINUNICODE
                    int len = readInt32();
                    if (len < 0 || pos + len > data.length || len > 100_000_000) { stack.add("[invalid]"); break; }
                    var s = new String(data, pos, len, StandardCharsets.UTF_8);
                    pos += len;
                    stack.add(s);
                    break;
                }
                case 'S': { // STRING (quoted string)
                    if (pos >= data.length) break;
                    char quote = (char) data[pos++];
                    var sb = new StringBuilder();
                    while (pos < data.length) {
                        byte b = data[pos++];
                        if (b == '\\' && pos < data.length) { sb.append((char) data[pos++]); }
                        else if (b == quote) break;
                        else sb.append((char) b);
                    }
                    if (pos < data.length) pos++; // skip \n
                    stack.add(sb.toString());
                    break;
                }
                case 'N': // NONE
                    stack.add(null);
                    break;
                case 'q': { // BINPUT
                    int idx = data[pos++] & 0xFF;
                    memo.put(idx, stack.getLast());
                    break;
                }
                case 'h': { // BINGET
                    int idx = data[pos++] & 0xFF;
                    stack.add(memo.get(idx));
                    break;
                }
                case 'r': { // SETITEM
                    var val = stack.removeLast();
                    var key = stack.removeLast();
                    @SuppressWarnings("unchecked")
                    var dict = (Map<Object, Object>) stack.getLast();
                    dict.put(key, val);
                    break;
                }
                case 'u': { // SETITEMS
                    int mark = marks.isEmpty() ? 0 : marks.removeLast();
                    @SuppressWarnings("unchecked")
                    var dict = (Map<Object, Object>) stack.get(stack.size() - mark - 1);
                    for (int i = mark; i < stack.size(); i += 2) {
                        dict.put(stack.get(i), stack.get(i + 1));
                    }
                    stack.subList(mark, stack.size()).clear();
                    break;
                }
                case '{': // EMPTY_DICT
                    stack.add(new LinkedHashMap<>());
                    break;
                case 0x81: { // LONG_BINPUT (4-byte memo key)
                    int idx = readInt32();
                    memo.put(idx, stack.getLast());
                    break;
                }
                case 0x82: { // LONG_BINGET (4-byte memo key)
                    int idx = readInt32();
                    stack.add(memo.get(idx));
                    break;
                }
                case 0x8a: { // LONG1 (length-prefixed long, 1-byte length)
                    int len = data[pos++] & 0xFF;
                    if (pos + len > data.length) { stack.add(0L); break; }
                    long v = parseArbitraryLong(data, pos, len);
                    pos += len;
                    stack.add(v);
                    break;
                }
                case 0x8b: { // LONG4 (length-prefixed long, 4-byte length)
                    int len = readInt32();
                    if (len < 0 || pos + len > data.length) { stack.add(0L); break; }
                    long v = parseArbitraryLong(data, pos, len);
                    pos += len;
                    stack.add(v);
                    break;
                }
                case 'B': { // BINBYTES
                    int len = readInt32();
                    if (len < 0 || pos + len > data.length) { stack.add(new byte[0]); break; }
                    stack.add(Arrays.copyOfRange(data, pos, pos + len));
                    pos += len;
                    break;
                }
                case 0x8c: { // SHORT_BINUNICODE (1-byte length, UTF-8)
                    int len = data[pos++] & 0xFF;
                    if (pos + len > data.length) { stack.add(""); break; }
                    stack.add(new String(data, pos, len, StandardCharsets.UTF_8));
                    pos += len;
                    break;
                }
                case 0x8d: { // BINUNICODE8 (8-byte length, big-endian)
                    long lenHi = readInt32();
                    long lenLo = readInt32();
                    long len = (lenHi << 32) | (lenLo & 0xFFFF_FFFFL);
                    if (len < 0 || len > 100_000_000 || pos + len > data.length) { stack.add(""); break; }
                    stack.add(new String(data, pos, (int) len, StandardCharsets.UTF_8));
                    pos += (int) len;
                    break;
                }
                case 0x95: // FRAME (8-byte frame size, big-endian — ignored)
                    pos += 8;
                    break;
                default:
                    recordUnknownOpcode(op);
            }
        }
        return stack.isEmpty() ? null : stack.getLast();
    }

    private int readInt32() {
        return (data[pos++] & 0xFF) | ((data[pos++] & 0xFF) << 8)
             | ((data[pos++] & 0xFF) << 16) | (data[pos++] << 24);
    }

    // ── 高层 API：解析 ArenaState 玩家列表 ──────────────────────────────

    /**
     * 从 onArenaStateReceived 的 args[3] BLOB 中解析玩家状态。
     * 返回 (entity_id → db_id) 和 (db_id → username) 映射。
     */
    public record ArenaPlayers(
        Map<Integer, Long> entityToDbId,   // entity_id → db_id
        Map<Long, String> dbIdToName,      // db_id → username
        Map<Long, Integer> dbIdToTeam,     // db_id → team_id
        Map<Long, Long> dbIdToMetaShipId   // db_id → meta_ship_id (用于匹配)
    ) {}

    /** 解析 onArenaStateReceived 中的玩家列表 pickle BLOB。 */
    public static ArenaPlayers parseArenaPlayers(byte[] pickledBlob) {
        var entityToDbId = new LinkedHashMap<Integer, Long>();
        var dbIdToName = new LinkedHashMap<Long, String>();
        var dbIdToTeam = new LinkedHashMap<Long, Integer>();
        var dbIdToMetaShipId = new LinkedHashMap<Long, Long>();

        Object root = decode(pickledBlob);
        if (!(root instanceof List<?> players)) return new ArenaPlayers(entityToDbId, dbIdToName, dbIdToTeam, dbIdToMetaShipId);

        for (Object playerObj : players) {
            // 每个 player 是 List of tuples: [(key0, val0), (key1, val1), ...]
            if (!(playerObj instanceof List<?> tuples)) continue;

            long dbId = 0, metaShipId = 0;
            int entityId = 0, teamId = -1;
            String name = "";

            for (Object tupleObj : tuples) {
                if (!(tupleObj instanceof List<?> kv) || kv.size() < 2) continue;
                Object keyObj = kv.get(0);
                if (!(keyObj instanceof Long key)) continue;
                Object val = kv.get(1);

                switch ((int) (long) key) {
                    case 0:  // accountDBID
                        dbId = longVal(val);
                        break;
                    case 11: // id (meta_ship_id, for matching with meta.vehicles)
                        metaShipId = longVal(val);
                        break;
                    case 25: // name
                        name = val instanceof String s ? s : "";
                        break;
                    case 33: // shipId (= entity_id)
                        entityId = (int) longVal(val);
                        break;
                    case 36: // teamId
                        teamId = (int) longVal(val);
                        break;
                }
            }

            if (entityId > 0 && dbId > 0) {
                entityToDbId.put(entityId, dbId);
                dbIdToName.put(dbId, name);
                dbIdToTeam.put(dbId, teamId);
                dbIdToMetaShipId.put(dbId, metaShipId);
            }
        }

        return new ArenaPlayers(entityToDbId, dbIdToName, dbIdToTeam, dbIdToMetaShipId);
    }

    private static long longVal(Object v) {
        if (v instanceof Long l) return l;
        if (v instanceof Double) return ((Double) v).longValue();
        if (v instanceof String s) {
            try { return Long.parseLong(s); }
            catch (NumberFormatException e) { return 0; }
        }
        return 0;
    }

    // ── opcode audit ────────────────────────────────────────────────────

    private void recordUnknownOpcode(int op) {
        unknownOpcodeCount++;
        synchronized (unknownOpcodes) {
            unknownOpcodes.merge(op, 1, Integer::sum);
        }
        if (!warnedOpcodes.contains(op)) {
            synchronized (warnedOpcodes) {
                if (warnedOpcodes.add(op)) {
                    log.warn("PickleReader: unknown opcode 0x{} ({}) at position {}", Integer.toHexString(op), op, pos - 1);
                }
            }
        }
    }

    /** Parse a big-endian signed integer of arbitrary length (1-8 bytes). */
    private static long parseArbitraryLong(byte[] data, int offset, int len) {
        if (len == 0) return 0;
        boolean negative = (data[offset] & 0x80) != 0;
        long v = 0;
        for (int i = 0; i < len && i < 8; i++) {
            byte b = data[offset + i];
            v = (v << 8) | (negative ? (b ^ 0xFF) : (b & 0xFF));
        }
        if (negative) v = -(v + 1);
        return v;
    }
}
