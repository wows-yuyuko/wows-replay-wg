package com.wows.replay.core.json;

import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * A JSON tree node — abstract over framework-specific types
 * like Jackson's {@code JsonNode} or Gson's {@code JsonElement}.
 */
public sealed interface JNode permits JacksonProvider.JacksonNode {

    boolean isObject();
    boolean isArray();
    boolean isTextual();
    boolean isInt();
    boolean isNull();

    String textValue();
    int intValue();
    long longValue();

    /** Field iterator for objects. */
    Iterator<Map.Entry<String, JNode>> fields();

    /** Element access for arrays. */
    List<JNode> elements();

    /** Get a named field (objects only). */
    JNode get(String name);

    /** Get an indexed element (arrays only). */
    JNode get(int index);
}
