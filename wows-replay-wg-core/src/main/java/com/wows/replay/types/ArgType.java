package com.wows.replay.types;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
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

    /**
     * Array of a single element type. 对标 Rust {@code Array(Option<usize>, Box<ArgType>)}.
     *
     * <p>When {@code fixedSize} is present the wire format has <em>no</em> count byte
     * (the count is known from the spec); otherwise a u8 count precedes the elements.</p>
     */
    record Array(OptionalInt fixedSize, ArgType elementType) implements ArgType {
        /** 无固定长度数组（线路上带 u8 计数字节）。 */
        Array(ArgType elementType) { this(OptionalInt.empty(), elementType); }
    }

    /** Fixed-size tuple of heterogeneous types. 对标 Rust {@code Tuple(Box<ArgType>, usize)}. */
    record Tuple(List<ArgType> elementTypes) implements ArgType {}

    /** FIXED_DICT with inline property definitions. 对标 Rust {@code FixedDict(bool, Vec<FixedDictProperty>)}. */
    record FixedDict(boolean allowNone, List<FixedDictProperty> properties) implements ArgType {}

    /** A single field within a FIXED_DICT. */
    record FixedDictProperty(String name, ArgType propType) {}

    /**
     * 具名类型引用 — resolved via EntitySpec definitions.
     * 对标 Rust {@code Named { name, inner }}.
     * {@code inner} 默认 {@link Primitive#BLOB}，直到由 spec 层解析。
     */
    record NamedType(String name, ArgType inner) implements ArgType {
        /** 未解析引用的便捷构造器。 */
        public NamedType(String name) { this(name, Primitive.BLOB); }
    }

    /**
     * USER_TYPE（converter-backed 自定义类型）。线路上按内部 {@code inner} 类型
     * 原样传输（透明，<em>无长度前缀</em>）；但排序时视为变长（INFINITY），
     * 因为 converter 的流长度不可预知。对标 Rust {@code UserType(Box<ArgType>)}。
     */
    record UserType(ArgType inner) implements ArgType {}

    /**
     * 可空类型（def 中带 {@code <AllowNone>}）。线路上先读 1 字节存在标志
     * （0=null，1=存在），再按内部类型解析。对标 Rust 中 AllowNone 的通用处理。
     */
    record AllowNone(ArgType inner) implements ArgType {}

    // ── Descriptor parsing ───────────────────────────────────────────────────

    /** 匹配 "ARRAY <of> element" / "ARRAY element". */
    Pattern ARRAY_PATTERN = Pattern.compile(
        "ARRAY\\s*(?:<OF>)?\\s*(.+)", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    /** 匹配描述符中的固定长度标记：{@code <SIZE>N</SIZE>}. */
    Pattern ARRAY_SIZE_PATTERN = Pattern.compile(
        "<SIZE>\\s*(\\d+)\\s*</SIZE>", Pattern.CASE_INSENSITIVE);

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
     * "TUPLE <of> FLOAT,FLOAT,FLOAT", "FIXED_DICT AvatarCommon".
     */
    static ArgType fromDescriptor(String descriptor) {
        if (descriptor == null) return Primitive.BLOB;
        var trimmed = descriptor.trim().toUpperCase();

        // FIXED_DICT <name> → NamedType reference (resolved later via EntitySpec)
        if (trimmed.startsWith("FIXED_DICT")) {
            var name = trimmed.substring("FIXED_DICT".length()).trim();
            return new NamedType(name);
        }

        // ARRAY <of> ELEMENT_TYPE
        var arrMatch = ARRAY_PATTERN.matcher(trimmed);
        if (arrMatch.matches()) {
            var rest = arrMatch.group(1).trim();
            var sizeMatch = ARRAY_SIZE_PATTERN.matcher(rest);
            OptionalInt size = OptionalInt.empty();
            if (sizeMatch.find()) {
                try { size = OptionalInt.of(Integer.parseInt(sizeMatch.group(1))); } catch (NumberFormatException ignored) {}
                rest = sizeMatch.replaceFirst("").trim();
            }
            return new Array(size, fromDescriptor(rest));
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
            case Array(var fixed, var elem) -> {
                if (fixed.isEmpty()) {
                    yield SORT_INFINITY; // 变长数组无法估算固定尺寸
                }
                int s = elem.sortSize();
                yield s == SORT_INFINITY ? SORT_INFINITY : s * fixed.getAsInt();
            }
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
            case FixedDict(var allowNone, var props) -> {
                if (allowNone) {
                    yield SORT_INFINITY; // 可空类型无法估算固定尺寸
                }
                // 对标 Rust fold：任一字段为 INFINITY 则整体饱和为 INFINITY，
                // 否则求和。不能简单 sum（会超过 INFINITY 导致排序错位）。
                int total = 0;
                for (var p : props) {
                    int s = p.propType().sortSize();
                    if (s == SORT_INFINITY) {
                        total = SORT_INFINITY;
                        break;
                    }
                    total += s;
                }
                yield total == 0 ? SORT_INFINITY : total;
            }
            case NamedType(var _, var inner) -> inner.sortSize();
            case UserType(var _) -> SORT_INFINITY; // converter 流长度不可预知
            case AllowNone(var _) -> SORT_INFINITY; // 可空类型无法估算固定尺寸
        };
    }

    // ── Display ──────────────────────────────────────────────────────────────

    /**
     * 可读类型名，用于错误消息和调试。
     */
    default String typeName() {
        return switch (this) {
            case Primitive p  -> p.name();
            case Array(var fixed, var e) -> "ARRAY<"
                + (fixed.isPresent() ? fixed.getAsInt() + " x " : "") + e.typeName() + ">";
            case Tuple(var es) -> "TUPLE<" + es.stream()
                .map(ArgType::typeName)
                .collect(java.util.stream.Collectors.joining(",")) + ">";
            case FixedDict(var _, var props) -> "FIXED_DICT{" +
                props.stream().map(p -> p.name() + ":" + p.propType().typeName())
                    .collect(java.util.stream.Collectors.joining(",")) + "}";
            case NamedType(var n, var _) -> "FIXED_DICT<" + n + ">";
            case UserType(var inner) -> "USER_TYPE<" + inner.typeName() + ">";
            case AllowNone(var inner) -> "ALLOW_NONE<" + inner.typeName() + ">";
        };
    }
}
