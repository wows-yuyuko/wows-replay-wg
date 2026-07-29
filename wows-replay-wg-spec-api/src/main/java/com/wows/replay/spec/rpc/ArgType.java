package com.wows.replay.spec.rpc;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * BigWorld RPC 参数类型定义。
 * 描述如何从线路上解析值。
 *
 * <p>对标 Rust {@code ArgType} enum in {@code wowsunpack::rpc::typedefs}.</p>
 */
public sealed interface ArgType {

    // ── Primitive types ──────────────────────────────────────────────────────

    /**
     * 线级基本类型。
     * 用单个枚举替代 16 个独立标记 record，对标
     * Rust {@code PrimitiveType} enum.
     */
    enum Primitive implements ArgType {
        INT8, INT16, INT32, INT64,
        UINT8, UINT16, UINT32, UINT64,
        FLOAT, DOUBLE,
        STRING, BOOL, BLOB, PYTHON,
        VECTOR2, VECTOR3, VECTOR4
    }

    // ── Compound types ───────────────────────────────────────────────────────

    /** Array of a single element type. 对标 Rust {@code Array(Option<usize>, Box<ArgType>)}. */
    record Array(ArgType elementType) implements ArgType {}

    /** Fixed-size tuple of heterogeneous types. 对标 Rust {@code Tuple(Box<ArgType>, usize)}. */
    record Tuple(List<ArgType> elementTypes) implements ArgType {}

    /**
     * 具名类型引用 — resolved via EntitySpec definitions.
     * 对标 Rust {@code Named { name, inner }}.
     * {@code inner} 默认 {@link Primitive#BLOB}，直到由 spec 层解析。
     */
    record NamedType(String name, ArgType inner) implements ArgType {
        /** 未解析引用的便捷构造器。 */
        public NamedType(String name) { this(name, Primitive.BLOB); }
    }

    // ── Descriptor parsing ───────────────────────────────────────────────────

    /** 匹配 "ARRAY <of> element" / "ARRAY element". */
    Pattern ARRAY_PATTERN = Pattern.compile(
        "ARRAY\\s*(?:<OF>)?\\s*(.+)", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    /** 匹配 "TUPLE <of> type1,type2,..." / "TUPLE type1,type2,...". */
    Pattern TUPLE_PATTERN = Pattern.compile(
        "TUPLE\\s*(?:<OF>)?\\s*(.+)", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    /**
     * 基本类型描述符查找表（方法而非字段，避免 ArgType ↔ Primitive 类加载循环）。
     * 首次调用时初始化，此时 Primitive 枚举已完全就绪。
     */
    static Map<String, Primitive> descriptorMap() {
        return DescriptorMapHolder.MAP;
    }
    static final class DescriptorMapHolder {
        static final Map<String, Primitive> MAP = Map.ofEntries(
            Map.entry("INT8",    Primitive.INT8),
            Map.entry("INT16",   Primitive.INT16),
            Map.entry("INT32",   Primitive.INT32),
            Map.entry("INT64",   Primitive.INT64),
            Map.entry("UINT8",   Primitive.UINT8),
            Map.entry("UINT16",  Primitive.UINT16),
            Map.entry("UINT32",  Primitive.UINT32),
            Map.entry("UINT64",  Primitive.UINT64),
            Map.entry("FLOAT",   Primitive.FLOAT),
            Map.entry("FLOAT32", Primitive.FLOAT),
            Map.entry("FLOAT64", Primitive.DOUBLE),
            Map.entry("DOUBLE",  Primitive.DOUBLE),
            Map.entry("STRING",  Primitive.STRING),
            Map.entry("BOOL",    Primitive.BOOL),
            Map.entry("BLOB",    Primitive.BLOB),
            Map.entry("PYTHON",  Primitive.PYTHON),
            Map.entry("VECTOR2", Primitive.VECTOR2),
            Map.entry("VECTOR3", Primitive.VECTOR3),
            Map.entry("VECTOR4", Primitive.VECTOR4)
        );
    }

    /**
     * 从 .def 文件解析类型描述符字符串。
     * Examples: "UINT8", "FLOAT32", "STRING", "ARRAY 解析",
     * "TUPLE <of> FLOAT,FLOAT,FLOAT", "FIXED_DICT 解析 AvatarCommon".
     */
    static ArgType fromDescriptor(String descriptor) {
        if (descriptor == null) return Primitive.BLOB;
        var trimmed = descriptor.trim().toUpperCase();

        // FIXED_DICT 解析 → NamedType reference (resolved later via EntitySpec)
        if (trimmed.startsWith("FIXED_DICT 解析")) {
            var name = trimmed.substring("FIXED_DICT 解析".length()).trim();
            return new NamedType(name);
        }

        // ARRAY <of> ELEMENT_TYPE
        var arrMatch = ARRAY_PATTERN.matcher(trimmed);
        if (arrMatch.matches()) {
            return new Array(fromDescriptor(arrMatch.group(1).trim()));
        }

        // TUPLE <of> TYPE1,TYPE2,...
        var tupMatch = TUPLE_PATTERN.matcher(trimmed);
        if (tupMatch.matches()) {
            var elements = splitTupleTypes(tupMatch.group(1).trim());
            return new Tuple(elements.stream().map(ArgType::fromDescriptor).toList());
        }

        // Primitives via lookup table
        var prim = descriptorMap().get(trimmed);
        return prim != null ? prim : Primitive.BLOB; // unrecognized → raw bytes
    }

    /**
     * 分割逗号分隔的类型列表，保留尖括号嵌套。
     * e.g. "ARRAY<UINT32>,FLOAT" → ["ARRAY<UINT32>", "FLOAT"]
     */
    private static ArrayList<String> splitTupleTypes(String s) {
        var result = new ArrayList<String>();
        int depth = 0;
        var current = new StringBuilder();
        for (char c : s.toCharArray()) {
            if (c == ',' && depth == 0) {
                result.add(current.toString().trim());
                current.setLength(0);
            } else {
                if (c == '<') depth++;
                else if (c == '>') depth--;
                current.append(c);
            }
        }
        if (!current.isEmpty()) result.add(current.toString().trim());
        return result;
    }

    // ── Wire size estimation ─────────────────────────────────────────────────

    /** 变长类型哨兵值（对标 Rust {@code INFINITY}). */
    int SORT_INFINITY = 0xFFFF;

    /**
     * 估算的固定线尺寸（字节）。
     * 变长或可空类型返回 {@link #SORT_INFINITY}。
     * 对标 Rust {@code ArgType::sort_size()}.
     */
    default int sortSize() {
        return switch (this) {
            case Primitive p -> switch (p) {
                case INT8, UINT8, BOOL       -> 1;
                case INT16, UINT16           -> 2;
                case INT32, UINT32, FLOAT    -> 4;
                case INT64, UINT64, DOUBLE, VECTOR2 -> 8;
                case VECTOR3 -> 12;
                case VECTOR4 -> 16;
                default      -> SORT_INFINITY; // STRING, BLOB, PYTHON
            };
            case Array(var elem) -> elem.sortSize(); // variable count, same as element
            case Tuple(var elems) -> {
                int total = 0;
                for (var e : elems) {
                    int s = e.sortSize();
                    if (s == SORT_INFINITY) {
                        total = SORT_INFINITY;
                        break;
                    }
                    total += s;
                }
                yield total;
            }
            case NamedType(var _, var inner) -> inner.sortSize();
        };
    }

    // ── Display ──────────────────────────────────────────────────────────────

    /**
     * 可读类型名，用于错误消息和调试。
     */
    default String typeName() {
        return switch (this) {
            case Primitive p  -> p.name();
            case Array(var e) -> "ARRAY<" + e.typeName() + ">";
            case Tuple(var es) -> "TUPLE<" + es.stream()
                .map(ArgType::typeName)
                .collect(java.util.stream.Collectors.joining(",")) + ">";
            case NamedType(var n, var _) -> "FIXED_DICT 解析<" + n + ">";
        };
    }
}
