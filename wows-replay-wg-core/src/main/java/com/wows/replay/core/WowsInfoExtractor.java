package com.wows.replay.core;

import com.wows.replay.spec.spi.GameParamProvider;
import com.wows.replay.spec.types.GameParamId;
import tools.jackson.core.JsonToken;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Extracts GameParam ID→index mappings from a WoWs {@code wowsinfo.json}
 * file using streaming JSON parsing.
 */
public final class WowsInfoExtractor implements GameParamProvider {

    private final Map<Long, String> idToIndex;

    public WowsInfoExtractor(Path wowsInfoPath) {
        try (var in = Files.newInputStream(wowsInfoPath)) {
            this.idToIndex = Collections.unmodifiableMap(parse(in));
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse wowsinfo.json: " + wowsInfoPath, e);
        }
    }

    @Override public Optional<String> paramNameById(GameParamId id) {
        return Optional.ofNullable(idToIndex.get(id.value())); }
    @Override public Map<Long, String> paramNames() { return idToIndex; }
    public int size() { return idToIndex.size(); }

    // ── Streaming parser ─────────────────────────────────────────────────────

    private static Map<Long, String> parse(InputStream in) {
        var result = new LinkedHashMap<Long, String>();

        try (var p = JsonMapper.getMapper().createParser(in)) {
            if (p.nextToken() != JsonToken.START_OBJECT) return result;
            if (p.nextToken() != JsonToken.PROPERTY_NAME
                || !"ships".equals(p.currentName())) return result;
            if (p.nextToken() != JsonToken.START_OBJECT) return result;

            while (p.nextToken() != JsonToken.END_OBJECT) {
                if (p.currentToken() != JsonToken.PROPERTY_NAME) {
                    p.skipChildren();
                    continue;
                }
                long shipId;
                try {
                    shipId = Long.parseLong(p.currentName());
                } catch (NumberFormatException e) {
                    p.nextToken();
                    p.skipChildren();
                    continue;
                }

                if (p.nextToken() != JsonToken.START_OBJECT) {
                    p.skipChildren();
                    continue;
                }

                String shipIndex = null;
                while (p.nextToken() != JsonToken.END_OBJECT) {
                    if (p.currentToken() != JsonToken.PROPERTY_NAME) continue;
                    if ("index".equals(p.currentName())) {
                        if (p.nextToken() == JsonToken.VALUE_STRING) {
                            shipIndex = p.getString();
                        } else {
                            p.skipChildren();
                        }
                    } else {
                        p.nextToken();
                        p.skipChildren();
                    }
                }
                if (shipIndex != null) result.put(shipId, shipIndex);
            }
        }
        return result;
    }
}
