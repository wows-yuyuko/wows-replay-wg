package com.wows.replay.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.wows.replay.spec.spi.GameConstantsProvider;
import com.wows.replay.spec.types.Version;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Reads game constants from a WoWs {@code constants.json} file.
 *
 * <p>The file format is a flat JSON object whose keys are enum type names
 * and whose values are name→id (or id→name) maps.  Because the exact set
 * of constants changes between game versions, this provider stores the
 * entire JSON tree without mapping any section to static Java types.</p>
 *
 * <h3>File format (extract)</h3>
 * <pre>{@code
 * {
 *   "BATTLE_STAGES":     { "WAITING": 0, "BATTLE": 1, ... },
 *   "CAMERA_MODE":       { "TACTICALMAP": 3, "DOCK": 2, ... },
 *   "DEATH_REASON_NAME": { "AP_SHELL": "AP_SHELL", "RAM": "RAM", ... },
 *   "CONSUMABLE_STATES": { "READY": 0, "AT_WORK": 3, ... },
 *   "BATTLE_TYPES":      { "RANDOM_BATTLE": "RandomBattle", ... },
 *   ...
 * }
 * }</pre>
 */
public final class JsonConstantsProvider implements GameConstantsProvider {

    private final JsonNode root;
    private final Map<Integer, String> deathReasons;
    private final Map<Integer, String> cameraModes;
    private final Map<Integer, String> battleStages;
    private final Map<Integer, String> consumableStates;

    /** Load from a byte array (e.g. from a VFS or network source). */
    public JsonConstantsProvider(byte[] jsonBytes) throws IOException {
        this.root = JsonMapper.mapper().readTree(jsonBytes);
        this.deathReasons = buildReverseLookup("DEATH_REASON_NAME");
        this.cameraModes = buildReverseLookup("CAMERA_MODE");
        this.battleStages = buildReverseLookup("BATTLE_STAGES");
        this.consumableStates = buildReverseLookup("CONSUMABLE_STATES");
    }

    /** Load from a file path. */
    public static JsonConstantsProvider fromFile(Path path) throws IOException {
        return new JsonConstantsProvider(java.nio.file.Files.readAllBytes(path));
    }

    // ── GameConstantsProvider impl ───────────────────────────────────────────

    @Override
    public Optional<String> consumableName(int id) {
        return Optional.ofNullable(consumableStates.get(id));
    }

    @Override
    public Optional<String> deathReasonName(int id) {
        return Optional.ofNullable(deathReasons.get(id));
    }

    @Override
    public Optional<String> cameraModeName(int id) {
        return Optional.ofNullable(cameraModes.get(id));
    }

    @Override
    public Optional<String> battleStageName(int id, Version version) {
        return Optional.ofNullable(battleStages.get(id));
    }

    @Override
    public Map<Integer, String> consumableIds() {
        return Collections.unmodifiableMap(consumableStates);
    }

    @Override
    public Map<Integer, String> battleStages(Version version) {
        return Collections.unmodifiableMap(battleStages);
    }

    // ── Generic access ───────────────────────────────────────────────────────

    /**
     * Get any top-level section as a raw JsonNode for ad-hoc queries.
     * Returns {@code null} if the section does not exist in this version.
     */
    public JsonNode section(String name) {
        return root.get(name);
    }

    /**
     * Lookup a value in a name→value section by name.
     * Works with both {@code "KEY": 123} and {@code "KEY": "value"} shapes.
     */
    public Optional<JsonNode> lookup(String section, String name) {
        var node = root.get(section);
        if (node == null) return Optional.empty();
        var value = node.get(name);
        return value != null ? Optional.of(value) : Optional.empty();
    }

    /**
     * Reverse lookup: id→name in a name→id section.
     * Sections where multiple names share the same id keep the last one seen.
     */
    public Optional<String> reverseLookup(String section, int id) {
        var map = buildReverseLookup(section);
        return Optional.ofNullable(map.get(id));
    }

    // ── Internals ────────────────────────────────────────────────────────────

    /**
     * Build an id→name map from a name→value section.
     * Handles both numeric and string values.
     */
    private Map<Integer, String> buildReverseLookup(String sectionName) {
        var node = root.get(sectionName);
        if (node == null || !node.isObject()) return Map.of();

        var result = new LinkedHashMap<Integer, String>();
        var fields = node.fields();
        while (fields.hasNext()) {
            var entry = fields.next();
            String name = entry.getKey();
            JsonNode value = entry.getValue();
            if (value.isInt()) {
                result.put(value.intValue(), name);
            } else if (value.isTextual() && !name.equals(value.textValue())) {
                // For sections like DEATH_REASON_NAME where value is a display name
                result.put(result.size(), name); // use ordinal as synthetic id
            }
        }
        return result;
    }

    /** All section names available in this constants file. */
    public List<String> sectionNames() {
        var names = new java.util.ArrayList<String>();
        root.fieldNames().forEachRemaining(names::add);
        return names;
    }
}
