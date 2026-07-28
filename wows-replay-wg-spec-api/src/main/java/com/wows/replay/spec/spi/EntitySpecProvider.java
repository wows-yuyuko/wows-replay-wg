package com.wows.replay.spec.spi;

import com.wows.replay.spec.entity.EntitySpec;
import com.wows.replay.spec.types.Version;

import java.util.List;

/**
 * Provides entity specification definitions (from game .def files).
 *
 * <p>This is the primary abstraction for decoupling from wowsunpack.
 * Implementations load entity specs from game data and cache them
 * by version. A JSON-file-based default implementation is provided
 * for testing without a game install.</p>
 */
@FunctionalInterface
public interface EntitySpecProvider {

    /**
     * Load entity specs for the given game version.
     *
     * @param version the replay's game version
     * @return ordered list of entity specs (index = entity_type - 1)
     */
    List<EntitySpec> loadSpecs(Version version);

    /**
     * Empty provider — no entity specs. Packet parsing will fail
     * on entity-dependent packets (EntityCreate, EntityProperty, etc.)
     * but spec-independent packets still work.
     */
    static EntitySpecProvider empty() {
        return version -> List.of();
    }
}
