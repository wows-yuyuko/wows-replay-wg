package com.wows.replay.spec.rpc;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * BigWorld RPC argument type definition.
 * Describes how to parse a value from the wire.
 *
 * <p>Mirrors Rust's {@code ArgType} enum in {@code wowsunpack::rpc::typedefs}.</p>
 */
public sealed interface ArgType {

    // ── Primitive types ──────────────────────────────────────────────────────

    /**
     * Wire-level primitive types.
     * Replaces 16 separate marker records with a single enum, matching
     * Rust's {@code PrimitiveType} enum.
     */
    enum Primitive implements ArgType {
        INT8, INT16, INT32, INT64,
        UINT8, UINT16, UINT32, UINT64,
        FLOAT, DOUBLE,
        STRING, BOOL, BLOB, PYTHON,
        VECTOR2, VECTOR3, VECTOR4
    }

    // ── Compound types ───────────────────────────────────────────────────────

    /** Array of a single element type. Mirrors Rust's {@code Array(Option<usize>, Box<ArgType>)}. */
    record Array(ArgType elementType) implements ArgType {}

    /** Fixed-size tuple of heterogeneous types. Mirrors Rust's {@code Tuple(Box<ArgType>, usize)}. */
    record Tuple(List<ArgType> elementTypes) implements ArgType {}

    /**
     * A named type reference — resolved via EntitySpec definitions.
     * Mirrors Rust's {@code Named { name, inner }}.
     * {@code inner} defaults to {@link Primitive#BLOB} until resolved by the spec layer.
     */
    record NamedType(String name, ArgType inner) implements ArgType {
        /** Convenience constructor for unresolved references. */
        public NamedType(String name) { this(name, Primitive.BLOB); }
    }

    // ── Descriptor parsing ───────────────────────────────────────────────────

    /** Matches "ARRAY <of> element" / "ARRAY element". */
    Pattern ARRAY_PATTERN = Pattern.compile(
        "ARRAY\\s*(?:<OF>)?\\s*(.+)", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    /** Matches "TUPLE <of> type1,type2,..." / "TUPLE type1,type2,...". */
    Pattern TUPLE_PATTERN = Pattern.compile(
        "TUPLE\\s*(?:<OF>)?\\s*(.+)", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    /** Lookup table for primitive type descriptors. */
    Map<String, Primitive> DESCRIPTOR_MAP = Map.ofEntries(
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

    /**
     * Parse a type descriptor string from .def files.
     * Examples: "UINT8", "FLOAT32", "STRING", "ARRAY <of> UINT32",
     * "TUPLE <of> FLOAT,FLOAT,FLOAT", "FIXED_DICT AvatarCommon".
     */
    static ArgType fromDescriptor(String descriptor) {
        if (descriptor == null) return Primitive.BLOB;
        var trimmed = descriptor.trim().toUpperCase();

        // FIXED_DICT → NamedType reference (resolved later via EntitySpec)
        if (trimmed.startsWith("FIXED_DICT")) {
            var name = trimmed.substring("FIXED_DICT".length()).trim();
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
        var prim = DESCRIPTOR_MAP.get(trimmed);
        return prim != null ? prim : Primitive.BLOB; // unrecognized → raw bytes
    }

    /**
     * Split a comma-separated type list, respecting angle-bracket nesting.
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

    /** Sentinel for variable-length types (matches Rust's {@code INFINITY}). */
    int SORT_INFINITY = 0xFFFF;

    /**
     * Estimated fixed wire size in bytes.
     * Returns {@link #SORT_INFINITY} for variable-length or nullable types.
     * Mirrors Rust's {@code ArgType::sort_size()}.
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
     * Human-readable type name for error messages and debugging.
     */
    default String typeName() {
        return switch (this) {
            case Primitive p  -> p.name();
            case Array(var e) -> "ARRAY<" + e.typeName() + ">";
            case Tuple(var es) -> "TUPLE<" + es.stream()
                .map(ArgType::typeName)
                .collect(java.util.stream.Collectors.joining(",")) + ">";
            case NamedType(var n, var _) -> "FIXED_DICT<" + n + ">";
        };
    }
}
