package com.shinoaki.wowsreplay.core.data;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * 战报位置数组 → 具名对象解析（对标 Rust {@code wowsunpack::battle_results::resolve_battle_results}）。
 *
 * <p>服务端下发战报为紧凑位置数组；{@code constants.json} 提供字段名→索引映射：
 * {@code COMMON_RESULTS}（commonList 字段名序）、{@code CLIENT_PUBLIC_RESULTS_INDICES}
 * （玩家公开结果）、{@code CLIENT_VEH_INTERACTION_DETAILS}（逐受害者交互字段名序）、
 * {@code PLAYER_PRIVATE_RESULTS_INDICES}（玩家私有结果）。</p>
 */
public final class BattleResultsResolver {

    private BattleResultsResolver() {}

    /** 解析整个战报：commonList + playersPublicInfo（含 interactions）+ playersPrivateInfo/privateDataList。 */
    public static JsonNode resolve(JsonNode raw, JsonNode constants) {
        if (raw == null || !raw.isObject() || constants == null) return raw;
        ObjectNode out = (ObjectNode) raw.deepCopy();

        // commonList：数组 → 对象（COMMON_RESULTS 字段名序）
        JsonNode commonNames = constants.path("COMMON_RESULTS");
        JsonNode commonArr = raw.get("commonList");
        if (commonNames.isArray() && commonArr != null && commonArr.isArray()) {
            out.set("commonList", resolveArray(commonNames, commonArr));
        }

        // playersPublicInfo：每玩家数组 → 对象（CLIENT_PUBLIC_RESULTS_INDICES）
        JsonNode indices = constants.get("CLIENT_PUBLIC_RESULTS_INDICES");
        JsonNode interactionFields = constants.get("CLIENT_VEH_INTERACTION_DETAILS");
        JsonNode publicInfo = raw.get("playersPublicInfo");
        if (indices != null && indices.isObject() && publicInfo != null && publicInfo.isObject()) {
            ObjectNode resolved = (ObjectNode) publicInfo.deepCopy();
            for (var prop : resolved.properties()) {
                JsonNode playerVal = prop.getValue();
                if (playerVal.isArray()) {
                    ObjectNode obj = indexToObject(indices, (tools.jackson.databind.node.ArrayNode) playerVal);
                    // interactions：逐受害者数组 → 对象
                    JsonNode interactions = obj.get("interactions");
                    if (interactionFields != null && interactionFields.isArray()
                        && interactions != null && interactions.isObject()) {
                        ObjectNode resolvedInter = (ObjectNode) interactions.deepCopy();
                        for (var victimProp : resolvedInter.properties()) {
                            JsonNode v = victimProp.getValue();
                            if (v.isArray()) {
                                resolvedInter.set(victimProp.getKey(), resolveArray(interactionFields, (tools.jackson.databind.node.ArrayNode) v));
                            }
                        }
                        obj.set("interactions", resolvedInter);
                    }
                    resolved.set(prop.getKey(), obj);
                }
            }
            out.set("playersPublicInfo", resolved);
        }

        // playersPrivateInfo / privateDataList：位置数组 → 具名对象（PLAYER_PRIVATE_RESULTS_INDICES）
        // 兼容两种形态：
        //   对象形态 {db_id: [array], ...}（旧版 playersPrivateInfo）→ 逐玩家转具名对象；
        //   扁平数组形态 [v0, v1, ...]（新版 privateDataList，录制玩家自己的数据，无 db_id 键）→ 整个数组转一个具名对象。
        JsonNode privateIndices = constants.get("PLAYER_PRIVATE_RESULTS_INDICES");
        if (privateIndices != null && privateIndices.isObject()) {
            for (String key : new String[]{"playersPrivateInfo", "privateDataList"}) {
                JsonNode players = out.get(key);
                if (players == null) continue;
                if (players.isObject()) {
                    ObjectNode resolved = (ObjectNode) players.deepCopy();
                    for (var prop : resolved.properties()) {
                        JsonNode playerVal = prop.getValue();
                        if (playerVal.isArray()) {
                            resolved.set(prop.getKey(), indexToObject(privateIndices, (tools.jackson.databind.node.ArrayNode) playerVal));
                        }
                    }
                    out.set(key, resolved);
                } else if (players.isArray()) {
                    // 扁平数组：无 db_id 键，按索引映射整体转一个具名对象
                    out.set(key, indexToObject(privateIndices, (tools.jackson.databind.node.ArrayNode) players));
                }
            }
        }
        return out;
    }

    /** 位置数组 → 具名对象（{@code names[i]} 为 {@code values[i]} 的键）。 */
    public static ObjectNode resolveArray(JsonNode names, JsonNode values) {
        ObjectNode obj = JsonNodeFactory().objectNode();
        if (!names.isArray() || !values.isArray()) return obj;
        for (int i = 0; i < names.size(); i++) {
            JsonNode name = names.get(i);
            if (name.isString() && i < values.size()) {
                obj.set(name.stringValue(), values.get(i));
            }
        }
        return obj;
    }

    /** {@code {name: index}} 映射 → 位置数组取具名对象（playersPublicInfo / playersPrivateInfo 单玩家）。 */
    public static ObjectNode indexToObject(JsonNode indices, tools.jackson.databind.node.ArrayNode arr) {
        ObjectNode obj = JsonNodeFactory().objectNode();
        if (indices == null || !indices.isObject()) return obj;
        for (var prop : indices.properties()) {
            JsonNode idx = prop.getValue();
            if (idx.isIntegralNumber()) {
                int i = idx.intValue();
                if (i >= 0 && i < arr.size()) {
                    obj.set(prop.getKey(), arr.get(i));
                }
            }
        }
        return obj;
    }

    private static JsonNodeFactory JsonNodeFactory() {
        return JsonNodeFactory.instance;
    }
}
