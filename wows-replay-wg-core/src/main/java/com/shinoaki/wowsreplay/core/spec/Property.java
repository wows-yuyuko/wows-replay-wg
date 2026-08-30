package com.shinoaki.wowsreplay.core.spec;

import com.shinoaki.wowsreplay.core.types.ArgType;

import java.util.Set;

/**
 * Entity property definition.  entitydefs::Property.
 */
public record Property(
    /** Property name (e.g. "position", "health", "maxHealth"). */
    String name,

    /** Wire type of this property. */
    ArgType propType,

    /** Visibility flags controlling when this property is transmitted. */
    Set<PropertyFlags> flags,

    /** Index within the entity's sorted property array. */
    int index
) {
    public Property(String name, ArgType propType, int index) {
        this(name, propType, Set.of(PropertyFlags.ALL_CLIENTS), index);
    }

    /** 检查属性是否包含任一指定标记。 */
    public boolean hasAnyFlag(PropertyFlags... fs) {
        for (var f : fs) if (flags.contains(f)) return true;
        return false;
    }
}
