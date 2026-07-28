package com.wows.replay.core;

import tools.jackson.core.JsonFactory;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import com.wows.replay.spec.spi.GameParamProvider;
import com.wows.replay.spec.types.GameParamId;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Extracts GameParam ID→name mappings from a WoWs {@code wowsinfo.json}
 * file using streaming JSON parsing to avoid loading the full 7 MB tree
 * into memory.
 *
 * <p>Only the fields we need ({@code id}, {@code index}, {@code name})
 * are retained; all other ship data is skipped without materializing.</p>
 */
public final class WowsInfoExtractor implements GameParamProvider {

    private final Map<Long, String> idToIndex;
    private final Map<Long, String> idToName;

    /** Load from a wowsinfo.json file (streaming). */
    public WowsInfoExtractor(Path wowsInfoPath) throws IOException {
        try (var in = Files.newInputStream(wowsInfoPath)) {
            var result = parse(in);
            this.idToIndex = Collections.unmodifiableMap(result.index);
            this.idToName = Collections.unmodifiableMap(result.name);
        }
    }

    // ── GameParamProvider impl ───────────────────────────────────────────────

    @Override public Optional<String> paramNameById(GameParamId id) {
        return Optional.ofNullable(idToIndex.get(id.value())); }
    @Override public Optional<Integer> paramIndexById(GameParamId id) {
        return Optional.empty(); }
    @Override public Map<Long, String> paramNames() { return idToIndex; }
    public int size() { return idToIndex.size(); }
    public Optional<String> displayName(long id) {
        return Optional.ofNullable(idToName.get(id)); }

    // ── Streaming parser ─────────────────────────────────────────────────────

    private static final JsonFactory FACTORY = new JsonFactory();

    private record Result(Map<Long, String> index, Map<Long, String> name) {}

    /**
     * Stream-parse only the fields we care about.
     *
     * <p>File shape: {@code {"ships": {"ID": {"index": "X", "name": "Y", ...}, ...}}}
     * We read ship IDs as map keys, then scan each ship object for {@code index}
     * and {@code name} fields, skipping everything else.</p>
     */
    private static Result parse(InputStream in) throws IOException {
        var index = new LinkedHashMap<Long, String>();
        var name  = new LinkedHashMap<Long, String>();

        try (var p = FACTORY.createParser(in)) {
            // Navigate to "ships"
            expect(p, JsonToken.START_OBJECT);     // root {
            expect(p, JsonToken.FIELD_NAME);
            if (!"ships".equals(p.currentName())) {
                return new Result(index, name);    // unexpected format
            }

            expect(p, JsonToken.START_OBJECT);     // ships {

            while (p.nextToken() == JsonToken.FIELD_NAME) {
                // Ship ID is the field name
                long shipId;
                try {
                    shipId = Long.parseLong(p.currentName());
                } catch (NumberFormatException e) {
                    p.nextToken();                 // skip value
                    p.skipChildren();
                    continue;
                }

                expect(p, JsonToken.START_OBJECT); // ship {

                // Scan ship fields until we hit the closing }
                String shipIndex = null;
                String shipName = null;
                while (p.nextToken() != JsonToken.END_OBJECT) {
                    if (p.currentToken() != JsonToken.FIELD_NAME) continue;
                    var field = p.currentName();
                    p.nextToken();                 // field value
                    if ("index".equals(field) && p.currentToken() == JsonToken.VALUE_STRING) {
                        shipIndex = p.getValueAsString();
                    } else if ("name".equals(field) && p.currentToken() == JsonToken.VALUE_STRING) {
                        shipName = p.getValueAsString();
                    } else {
                        p.skipChildren();          // skip nested objects/arrays
                    }
                }

                if (shipIndex != null) index.put(shipId, shipIndex);
                if (shipName != null) name.put(shipId, shipName);
            }
        }
        return new Result(index, name);
    }

    private static void expect(JsonParser p, JsonToken token) throws IOException {
        if (p.nextToken() != token) {
            throw new IOException("Expected " + token + " but got " + p.currentToken());
        }
    }
}
