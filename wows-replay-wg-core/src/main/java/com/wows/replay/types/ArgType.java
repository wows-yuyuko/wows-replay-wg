package com.wows.replay.types;

import java.util.List;
import java.util.OptionalInt;

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
    record NamedType(String name, ArgType inner) implements ArgType {}

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
                if (s == SORT_INFINITY) yield SORT_INFINITY;
                long m = (long) s * fixed.getAsInt();
                yield m >= SORT_INFINITY ? SORT_INFINITY : (int) m;
            }
            case Tuple(var elems) -> {
                int total = 0;
                for (var e : elems) {
                    int s = e.sortSize();
                    if (s == SORT_INFINITY || total + s >= SORT_INFINITY) {
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
                // 对标 Rust fold：任一字段为 INFINITY 或求和饱和到 0xFFFF 则整体为
                // INFINITY，避免超过 INFINITY 导致排序错位。
                int total = 0;
                for (var p : props) {
                    int s = p.propType().sortSize();
                    if (s == SORT_INFINITY || total + s >= SORT_INFINITY) {
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
