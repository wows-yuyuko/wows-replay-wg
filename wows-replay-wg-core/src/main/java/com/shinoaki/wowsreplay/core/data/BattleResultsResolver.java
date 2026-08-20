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
 * {@code PLAYER_PRIVATE_RESULTS_INDICES}（玩家私有结果）、
 * {@code BR_NESTED/PLAYER_PRIVATE_RESULTS}（私有结果嵌套子数组展开规则）。</p>
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
        // 之后再用 BR_NESTED/PLAYER_PRIVATE_RESULTS 展开嵌套子数组（init_economics/common_economics/subtotal_economics）。
        JsonNode privateIndices = constants.get("PLAYER_PRIVATE_RESULTS_INDICES");
        if (privateIndices != null && privateIndices.isObject()) {
            for (String key : new String[]{"playersPrivateInfo", "privateDataList"}) {
                JsonNode players = out.get(key);
                if (players == null) continue;
                if (players.isObject()) {
                    ObjectNode resolved = (ObjectNode) players.deepCopy();
                    for (var prop : resolved.properties()) {
                        // 旧版形态键是 db_id（数字串）；已解析形态键是字段名 → 跳过，保持幂等
                        if (!isNumericKey(prop.getKey())) continue;
                        JsonNode playerVal = prop.getValue();
                        if (playerVal.isArray()) {
                            ObjectNode obj = indexToObject(privateIndices, (tools.jackson.databind.node.ArrayNode) playerVal);
                            resolveNested(obj, constants);
                            resolved.set(prop.getKey(), obj);
                        }
                    }
                    out.set(key, resolved);
                } else if (players.isArray()) {
                    // 扁平数组：无 db_id 键，按索引映射整体转一个具名对象
                    ObjectNode obj = indexToObject(privateIndices, (tools.jackson.databind.node.ArrayNode) players);
                    resolveNested(obj, constants);
                    out.set(key, obj);
                }
            }
        }
        return out;
    }

    /**
     * {@code BR_NESTED/PLAYER_PRIVATE_RESULTS} 嵌套展开：entry 的 {@code field} 对应顶层字段
     * 若是数组，用常量 {@code sub_list} section 的字段名序转具名对象
     * （15.7.0 有 3 条：init_economics / common_economics / subtotal_economics）。
     */
    private static void resolveNested(ObjectNode obj, JsonNode constants) {
        JsonNode nested = constants.path("BR_NESTED").path("PLAYER_PRIVATE_RESULTS");
        if (!nested.isArray()) return;
        for (JsonNode entry : nested) {
            String field = entry.path("field").asText(null);
            String subList = entry.path("sub_list").asText(null);
            if (field == null || subList == null) continue;
            JsonNode fieldVal = obj.get(field);
            if (fieldVal == null || !fieldVal.isArray()) continue;
            JsonNode names = constants.get(subList);
            obj.set(field, resolveArray(names, (tools.jackson.databind.node.ArrayNode) fieldVal));
        }
    }

    /** 旧版私有结果对象形态的键是否为 db_id（纯数字串）。 */
    private static boolean isNumericKey(String s) {
        if (s == null || s.isEmpty()) return false;
        for (int i = 0; i < s.length(); i++) {
            if (!Character.isDigit(s.charAt(i))) return false;
        }
        return true;
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
