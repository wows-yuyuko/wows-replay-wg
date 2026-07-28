package com.wows.replay.core;

import com.wows.replay.spec.spi.GameConstantsProvider;
import com.wows.replay.spec.types.Version;
import tools.jackson.databind.JsonNode;

import java.nio.file.Path;
import java.util.*;

/**
 * Reads game constants from a WoWs {@code constants.json} file.
 *
 * <p>The file format is a flat JSON object whose keys are enum type names
 * and whose values are name→id (or id→name) maps.  Because the exact set
 * of constants changes between game versions, this provider stores the
 * entire JSON tree without mapping any section to static Java types.</p>
 */
public final class JsonConstantsProvider implements GameConstantsProvider {

    private final JsonNode root;
    private final Map<Integer, String> deathReasons;
    private final Map<Integer, String> cameraModes;
    private final Map<Integer, String> battleStages;
    private final Map<Integer, String> consumableStates;

    /** Load from a byte array (e.g. from a VFS or network source). */
    public JsonConstantsProvider(byte[] jsonBytes) {
        this.root = JsonMapper.readTree(jsonBytes);
        this.deathReasons = buildReverseLookup("DEATH_REASON_NAME");
        this.cameraModes = buildReverseLookup("CAMERA_MODE");
        this.battleStages = buildReverseLookup("BATTLE_STAGES");
        this.consumableStates = buildReverseLookup("CONSUMABLE_STATES");
    }

    /** Load from a file path. */
    public static JsonConstantsProvider fromFile(Path path) {
        try {
            return new JsonConstantsProvider(java.nio.file.Files.readAllBytes(path));
        } catch (java.io.IOException e) {
            throw new RuntimeException("Failed to read constants.json: " + path, e);
        }
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

    /** Get any top-level section as a raw {@link JsonNode} for ad-hoc queries. */
    public JsonNode section(String name) {
        return root.get(name);
    }

    /** Lookup a value in a name→value section by name. */
    public Optional<JsonNode> lookup(String section, String name) {
        var node = root.get(section);
        if (node == null) return Optional.empty();
        var value = node.get(name);
        return value != null ? Optional.of(value) : Optional.empty();
    }

    /** Reverse lookup: id→name in a name→id section. */
    public Optional<String> reverseLookup(String section, int id) {
        var map = buildReverseLookup(section);
        return Optional.ofNullable(map.get(id));
    }

    /** All section names available in this constants file. */
    public List<String> sectionNames() {
        return root.propertyNames().stream().toList();
    }

    // ── Internals ────────────────────────────────────────────────────────────

    private Map<Integer, String> buildReverseLookup(String sectionName) {
        var node = root.get(sectionName);
        if (node == null || !node.isObject()) return Map.of();
        var result = new LinkedHashMap<Integer, String>();
        for (var data : node.properties()) {
            String name = data.getKey();
            JsonNode value = data.getValue();
            if (value.isInt()) {
                result.put(value.intValue(), name);
            } else if (value.isString() && !name.equals(value.stringValue())) {
                result.put(result.size(), name);
            }
        }
        return result;
    }
}
