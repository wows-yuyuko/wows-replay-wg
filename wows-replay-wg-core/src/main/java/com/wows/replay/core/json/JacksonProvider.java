package com.wows.replay.core.json;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Jackson 3 implementation of {@link JsonProvider}.
 */
public final class JacksonProvider implements JsonProvider {

    static final ObjectMapper COMPACT = new ObjectMapper();
    static final ObjectMapper PRETTY = new ObjectMapper()
        .enable(SerializationFeature.INDENT_OUTPUT);

    @Override public JNode readTree(byte[] bytes) {
        try { return new JacksonNode(COMPACT.readTree(bytes)); }
        catch (Exception e) { throw new JacksonException(e); }
    }

    @Override public JNode readTree(String json) {
        try { return new JacksonNode(COMPACT.readTree(json)); }
        catch (Exception e) { throw new JacksonException(e); }
    }

    @Override public <T> T fromJson(String json, Class<T> type) {
        try { return COMPACT.readerFor(type).readValue(json); }
        catch (Exception e) { throw new JacksonException(e); }
    }

    @Override public String toJson(Object obj) {
        try { return COMPACT.writeValueAsString(obj); }
        catch (Exception e) { throw new JacksonException(e); }
    }

    @Override public String toPrettyJson(Object obj) {
        try { return PRETTY.writeValueAsString(obj); }
        catch (Exception e) { throw new JacksonException(e); }
    }

    @Override public String nodeToJson(JNode node) {
        return toJson(((JacksonNode) node).delegate);
    }

    @Override public String toJson(JObject obj) {
        return toJson(((JacksonBuilder) obj).delegate);
    }

    @Override public JNode toTree(Object obj) {
        return new JacksonNode(COMPACT.valueToTree(obj));
    }

    @Override public JObject createObject() {
        return new JacksonBuilder(COMPACT.createObjectNode());
    }

    // ── Wrapper types ────────────────────────────────────────────────────────

    static final class JacksonNode implements JNode {
        final JsonNode delegate;

        JacksonNode(JsonNode delegate) { this.delegate = delegate; }

        @Override public boolean isObject()  { return delegate.isObject(); }
        @Override public boolean isArray()   { return delegate.isArray(); }
        @Override public boolean isTextual() { return delegate.isString(); }
        @Override public boolean isInt()     { return delegate.isInt(); }
        @Override public boolean isNull()    { return delegate.isNull(); }
        @Override public String textValue()  { return delegate.stringValue(); }
        @Override public int intValue()      { return delegate.intValue(); }
        @Override public long longValue()    { return delegate.longValue(); }

        @Override
        public Iterator<Map.Entry<String, JNode>> fields() {
            var it = delegate.properties();
            return new Iterator<>() {
                @Override public boolean hasNext() { return it.hasNext(); }
                @Override public Map.Entry<String, JNode> next() {
                    var e = it.next();
                    return Map.entry(e.getKey(), new JacksonNode(e.getValue()));
                }
            };
        }

        @Override public List<JNode> elements() {
            var list = new java.util.ArrayList<JNode>();
            for (var el : delegate) list.add(new JacksonNode(el));
            return list;
        }

        @Override public JNode get(String name) {
            var child = delegate.get(name);
            return child != null ? new JacksonNode(child) : null;
        }
        @Override public JNode get(int index) {
            var child = delegate.get(index);
            return child != null ? new JacksonNode(child) : null;
        }
    }

    static final class JacksonBuilder implements JObject {
        final ObjectNode delegate;
        JacksonBuilder(ObjectNode delegate) { this.delegate = delegate; }

        @Override public JObject put(String name, String value)  { delegate.put(name, value); return this; }
        @Override public JObject put(String name, int value)     { delegate.put(name, value); return this; }
        @Override public JObject put(String name, long value)    { delegate.put(name, value); return this; }
        @Override public JObject put(String name, boolean value) { delegate.put(name, value); return this; }
        @Override public JObject set(String name, JNode node) {
            delegate.set(name, ((JacksonNode) node).delegate); return this;
        }
    }

    static final class JacksonArrayBuilder implements JArray {
        final ArrayNode delegate;
        JacksonArrayBuilder(ArrayNode delegate) { this.delegate = delegate; }

        @Override public JArray add(String value)  { delegate.add(value); return this; }
        @Override public JArray add(int value)     { delegate.add(value); return this; }
        @Override public JArray add(long value)    { delegate.add(value); return this; }
        @Override public JArray add(JNode node) {
            delegate.add(((JacksonNode) node).delegate); return this;
        }
    }

    /** Unchecked wrapper for JSON library exceptions. */
    private static final class JacksonException extends RuntimeException {
        JacksonException(Throwable cause) { super(cause); }
    }
}
