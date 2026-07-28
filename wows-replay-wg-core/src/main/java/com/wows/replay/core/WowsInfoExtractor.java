package com.wows.replay.core;

import com.wows.replay.core.json.JNode;
import com.wows.replay.spec.spi.GameParamProvider;
import com.wows.replay.spec.types.GameParamId;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Extracts GameParam ID→name mappings from a WoWs {@code wowsinfo.json} file
 * and provides them via {@link GameParamProvider}.
 *
 * <p>The file lives at {@code app/data/wowsinfo.json} inside the game data
 * directory and contains all ship/consumable/module parameter IDs with
 * their human-readable index strings.</p>
 *
 * <h3>File format</h3>
 * <pre>{@code
 * {
 *   "ships": {
 *     "4292851696": { "id": 4292851696, "index": "PASA002", "tier": 5, ... },
 *     ...
 *   }
 * }
 * }</pre>
 */
public final class WowsInfoExtractor implements GameParamProvider {

    private final Map<Long, String> idToIndex;
    private final Map<Long, String> idToName;

    /** Load from a wowsinfo.json file. */
    public WowsInfoExtractor(Path wowsInfoPath) throws IOException {
        this(JsonMapper.readTree(java.nio.file.Files.readAllBytes(wowsInfoPath)));
    }

    /** Load from already-parsed JSON tree. */
    public WowsInfoExtractor(JNode root) {
        var indexMap = new LinkedHashMap<Long, String>();
        var nameMap = new LinkedHashMap<Long, String>();

        // Extract ships section: ships > id > { id, index, name, ... }
        var ships = root.get("ships");
        if (ships != null && ships.isObject()) {
            var fields = ships.fields();
            while (fields.hasNext()) {
                var entry = fields.next();
                try {
                    long id = Long.parseLong(entry.getKey());
                    var ship = entry.getValue();
                    if (ship.isObject()) {
                        var indexNode = ship.get("index");
                        var nameNode = ship.get("name");
                        if (indexNode != null && indexNode.isTextual()) {
                            indexMap.put(id, indexNode.textValue());
                        }
                        if (nameNode != null && nameNode.isTextual()) {
                            nameMap.put(id, nameNode.textValue());
                        }
                        // Also index by the ship's own "id" field (can differ from key)
                        var idNode = ship.get("id");
                        if (idNode != null && idNode.isInt()) {
                            long selfId = idNode.longValue();
                            if (selfId != id && indexNode != null && indexNode.isTextual()) {
                                indexMap.putIfAbsent(selfId, indexNode.textValue());
                            }
                        }
                    }
                } catch (NumberFormatException ignored) {
                    // skip non-numeric keys
                }
            }
        }

        // Extract consumable/component names from ship modules
        for (var shipEntry : indexMap.entrySet()) {
            // This is handled inline above; components nested in ships don't have
            // their own top-level entries in wowsinfo.json
        }

        this.idToIndex = Collections.unmodifiableMap(indexMap);
        this.idToName = Collections.unmodifiableMap(nameMap);
    }

    // ── GameParamProvider impl ───────────────────────────────────────────────

    @Override
    public Optional<String> paramNameById(GameParamId id) {
        return Optional.ofNullable(idToIndex.get(id.value()));
    }

    @Override
    public Optional<Integer> paramIndexById(GameParamId id) {
        // wowsinfo.json doesn't have numeric indices like GameParams.data
        return Optional.empty();
    }

    @Override
    public Map<Long, String> paramNames() {
        return idToIndex;
    }

    // ── Additional accessors ─────────────────────────────────────────────────

    /** The number of resolved entries. */
    public int size() { return idToIndex.size(); }

    /** Get the display name for a ship ID (e.g. "IDS_PASA002"). */
    public Optional<String> displayName(long id) {
        return Optional.ofNullable(idToName.get(id));
    }
}
