package com.wows.replay.spec.spi;

import com.wows.replay.spec.types.Version;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 提供游戏常量查找（消耗品、死亡原因、战斗阶段、相机模式等）。
 * battle stages, camera modes, etc.).
 *
 * <p>These constants are extracted from game Scripts/ directory or
 * provided as a JSON dump. Falls back to hardcoded defaults when
 * no provider is available.</p>
 */
public interface GameConstantsProvider {

    /** Human-readable name for a consumable ID, or empty if unknown. */
    default Optional<String> consumableName(int id) { return Optional.empty(); }

    /** Human-readable name for a death reason ID, or empty if unknown. */
    default Optional<String> deathReasonName(int id) { return Optional.empty(); }

    /** Human-readable name for a game mode ID, or empty if unknown. */
    default Optional<String> gameModeName(int id) { return Optional.empty(); }

    /** Human-readable name for a camera mode ID, or empty if unknown. */
    default Optional<String> cameraModeName(int id) { return Optional.empty(); }

    /** Human-readable name for a battle stage ID, or empty if unknown. */
    default Optional<String> battleStageName(int id, Version version) { return Optional.empty(); }

    /** All known consumable ID → name mappings. */
    default Map<Integer, String> consumableIds() { return Collections.emptyMap(); }

    /** All known battle stage ID → name mappings. */
    default Map<Integer, String> battleStages(Version version) { return Collections.emptyMap(); }

    /** List of known ribbon names (indexed by ribbon type ID). */
    default List<String> ribbonNames() { return Collections.emptyList(); }

    /**
     * 空提供者 — all lookups return empty.
     */
    static GameConstantsProvider empty() {
        return new GameConstantsProvider() {};
    }
}
