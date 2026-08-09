package com.shinoaki.wowsreplay.core.packet;

import com.shinoaki.wowsreplay.core.types.ArgValue;

import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * Spec-driven named + positional argument container for entity method calls.
 *
 * <p>Pairs {@link ArgValue} instances with their corresponding {@code ArgSpec.name()}
 * from the entity definition.  Consumers can access arguments by name (version-safe)
 * or by positional index (backward-compatible).</p>
 *
 * <h3>Usage</h3>
 * <pre>{@code
 * // by name — safe against WG reordering args
 * var blob = args.get("playersBLOB");
 *
 * // by index — backward compatible
 * var blob = args.get(1);
 * }</pre>
 */
public final class NamedArgs {

    private final List<String> names;
    private final List<ArgValue> values;
    private volatile Map<String, Integer> nameIndex; // lazily built

    public NamedArgs(List<String> names, List<ArgValue> values) {
        if (names.size() != values.size()) {
            throw new IllegalArgumentException(
                "names.size=" + names.size() + " != values.size=" + values.size());
        }
        this.names = List.copyOf(names);
        this.values = List.copyOf(values);
    }

    /** Access by positional index (backward-compatible). */
    public ArgValue get(int index) {
        return values.get(index);
    }

    /** Access by argument name (version-safe, spec-driven). */
    public ArgValue get(String name) {
        var idx = nameIndex().get(name);
        if (idx == null) {
            throw new NoSuchElementException(
                "No argument named '" + name + "'. Available: " + names);
        }
        return values.get(idx);
    }

    /** Returns true if an argument with this name exists. */
    public boolean has(String name) {
        return nameIndex().containsKey(name);
    }

    /** Number of arguments. */
    public int size() {
        return values.size();
    }

    /** Convenience — same as {@code size() == 0}. */
    public boolean isEmpty() {
        return values.isEmpty();
    }

    /** Convenience — same as {@code get(0)}. */
    public ArgValue getFirst() {
        return values.getFirst();
    }

    /** Convenience — same as {@code get(size() - 1)}. */
    public ArgValue getLast() {
        return values.getLast();
    }

    /** All argument names in wire order. */
    public List<String> names() {
        return names;
    }

    /** All argument values in wire order. */
    public List<ArgValue> values() {
        return values;
    }

    @Override
    public String toString() {
        var sb = new StringBuilder("NamedArgs{");
        for (int i = 0; i < names.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(names.get(i)).append('=').append(values.get(i));
        }
        return sb.append('}').toString();
    }

    private Map<String, Integer> nameIndex() {
        if (nameIndex == null) {
            nameIndex = IntStream.range(0, names.size())
                .boxed()
                .collect(Collectors.toUnmodifiableMap(names::get, i -> i, (a, b) -> a));
        }
        return nameIndex;
    }
}
