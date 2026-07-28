package com.wows.replay.spec.rpc;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ArgTypeTest {

    @ParameterizedTest
    @CsvSource({
        "INT8,    INT8",
        "INT16,   INT16",
        "INT32,   INT32",
        "INT64,   INT64",
        "UINT8,   UINT8",
        "UINT16,  UINT16",
        "UINT32,  UINT32",
        "UINT64,  UINT64",
        "FLOAT,   FLOAT",
        "FLOAT32, FLOAT",
        "FLOAT64, DOUBLE",
        "DOUBLE,  DOUBLE",
        "STRING,  STRING",
        "BOOL,    BOOL",
        "BLOB,    BLOB",
        "PYTHON,  PYTHON",
        "VECTOR2, VECTOR2",
        "VECTOR3, VECTOR3",
        "VECTOR4, VECTOR4",
    })
    @DisplayName("Primitive descriptors resolve correctly")
    void primitiveDescriptor(String input, String expectedTypeName) {
        var t = ArgType.fromDescriptor(input);
        assertEquals(expectedTypeName, t.typeName());
        assertInstanceOf(ArgType.Primitive.class, t);
    }

    @Test
    @DisplayName("Case insensitive")
    void caseInsensitive() {
        assertEquals(ArgType.Primitive.UINT32, ArgType.fromDescriptor("uint32"));
        assertEquals(ArgType.Primitive.FLOAT, ArgType.fromDescriptor("Float32"));
    }

    @Test
    @DisplayName("Null descriptor → BLOB")
    void nullDescriptor() {
        assertEquals(ArgType.Primitive.BLOB, ArgType.fromDescriptor(null));
    }

    @Test
    @DisplayName("Unknown descriptor → BLOB fallback")
    void unknownDescriptor() {
        assertEquals(ArgType.Primitive.BLOB, ArgType.fromDescriptor("NONEXISTENT"));
    }

    @Test
    @DisplayName("ARRAY <of> UINT32")
    void arrayOfUint32() {
        var t = ArgType.fromDescriptor("ARRAY <of> UINT32");
        assertInstanceOf(ArgType.Array.class, t);
        var arr = (ArgType.Array) t;
        assertEquals(ArgType.Primitive.UINT32, arr.elementType());
        assertEquals("ARRAY<UINT32>", t.typeName());
    }

    @Test
    @DisplayName("ARRAY without <of>")
    void arrayWithoutOf() {
        var t = ArgType.fromDescriptor("ARRAY FLOAT");
        assertEquals("ARRAY<FLOAT>", t.typeName());
    }

    @Test
    @DisplayName("TUPLE <of> FLOAT,FLOAT,FLOAT")
    void tupleOfFloats() {
        var t = ArgType.fromDescriptor("TUPLE <of> FLOAT,FLOAT,FLOAT");
        assertInstanceOf(ArgType.Tuple.class, t);
        var tuple = (ArgType.Tuple) t;
        assertEquals(3, tuple.elementTypes().size());
        assertEquals("TUPLE<FLOAT,FLOAT,FLOAT>", t.typeName());
    }

    @Test
    @DisplayName("Nested ARRAY of ARRAY")
    void nestedArray() {
        var t = ArgType.fromDescriptor("ARRAY <of> ARRAY <of> UINT8");
        assertInstanceOf(ArgType.Array.class, t);
        var outer = (ArgType.Array) t;
        assertInstanceOf(ArgType.Array.class, outer.elementType());
    }

    @Test
    @DisplayName("FIXED_DICT → NamedType")
    void fixedDict() {
        var t = ArgType.fromDescriptor("FIXED_DICT AvatarCommon");
        assertInstanceOf(ArgType.NamedType.class, t);
        assertEquals("AvatarCommon", ((ArgType.NamedType) t).name());
        assertEquals("FIXED_DICT<AvatarCommon>", t.typeName());
    }

    @Test
    @DisplayName("sortSize for primitives")
    void sortSize() {
        assertEquals(1, ArgType.Primitive.INT8.sortSize());
        assertEquals(2, ArgType.Primitive.INT16.sortSize());
        assertEquals(4, ArgType.Primitive.INT32.sortSize());
        assertEquals(8, ArgType.Primitive.INT64.sortSize());
        assertEquals(8, ArgType.Primitive.VECTOR2.sortSize());
        assertEquals(12, ArgType.Primitive.VECTOR3.sortSize());
        assertEquals(16, ArgType.Primitive.VECTOR4.sortSize());
        assertEquals(ArgType.SORT_INFINITY, ArgType.Primitive.STRING.sortSize());
    }

    @Test
    @DisplayName("sortSize for TUPLE")
    void sortSizeTuple() {
        var t = new ArgType.Tuple(List.of(ArgType.Primitive.INT32, ArgType.Primitive.FLOAT));
        assertEquals(8, t.sortSize());
    }

    @Test
    @DisplayName("sortSize for TUPLE with variable element")
    void sortSizeTupleVariable() {
        var t = new ArgType.Tuple(List.of(ArgType.Primitive.INT32, ArgType.Primitive.STRING));
        assertEquals(ArgType.SORT_INFINITY, t.sortSize());
    }
}
