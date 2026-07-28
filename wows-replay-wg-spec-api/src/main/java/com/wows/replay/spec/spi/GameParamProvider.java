package com.wows.replay.spec.spi;

import com.wows.replay.spec.types.GameParamId;

import java.util.Collections;
import java.util.Map;
import java.util.Optional;

/**
 * Provides game parameter lookups (ship names, stats, etc.).
 *
 * <p>Abstraction over wowsunpack's GameParams.data loading.
 * Implementations may load from game files or a pre-extracted JSON dump.</p>
 */
public interface GameParamProvider {

    /**
     * Get the index/name for a game parameter by its ID.
     *
     * @param id the ship/equipment parameter ID
     * @return the parameter index string (e.g. "PASB018_Alaska_1950")
     */
    Optional<String> paramNameById(GameParamId id);

    /**
     * All GameParamId → index mappings for ID resolution in reports.
     */
    default Map<Long, String> paramNames() { return Collections.emptyMap(); }

    /** Empty provider — all lookups return empty. */
    static GameParamProvider empty() {
        return id -> Optional.empty();
    }
}
