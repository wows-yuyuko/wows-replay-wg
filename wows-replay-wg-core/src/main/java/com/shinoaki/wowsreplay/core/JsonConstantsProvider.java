package com.shinoaki.wowsreplay.core;

import com.shinoaki.wowsreplay.core.model.Version;
import com.shinoaki.wowsreplay.core.spi.GameConstantsProvider;
import tools.jackson.databind.JsonNode;

import java.nio.file.Path;
import java.util.*;

/**
 * 从 WoWs {@code constants.json} 文件加载游戏常量。
 *
 * <p>将整个 JSON 读入 {@link JsonNode} 树，不做静态类型映射——各版本间字段会变。</p>
 */
public final class JsonConstantsProvider implements GameConstantsProvider {

    private final JsonNode root;
    private final Map<Integer, String> deathReasons;
    private final Map<Integer, String> cameraModes;
    private final Map<Integer, String> battleStages;
    private final Map<Integer, String> finishTypes;
    private final Map<Integer, String> damageStatCategories;
    private final Map<Integer, String> consumableStates;

    /** 从字节数组加载。 */
    public JsonConstantsProvider(byte[] jsonBytes) {
        this.root = JsonMapper.readTree(jsonBytes);
        // 15.x：CONSUMABLE_IDS = name→id
        this.consumableStates = buildReverseLookup("CONSUMABLE_IDS");
        // 15.x：DEATH_REASONS 是 [{icon,id,name,sound}, ...] 数组；旧版为 DEATH_REASON_NAME 对象。
        this.deathReasons = buildDeathReasonMap();
        this.cameraModes = buildReverseLookup("CAMERA_MODE");
        this.battleStages = buildReverseLookup("BATTLE_STAGES");
        // FINISH_TYPE / DAMAGE_STATS：name→id，反向查找成 id→name（供 GameConstants 对未知 id 兜底）。
        this.finishTypes = buildReverseLookup("FINISH_TYPE");
        this.damageStatCategories = buildReverseLookup("DAMAGE_STATS");
    }

    /** 从文件路径加载。 */
    public static JsonConstantsProvider fromFile(Path path) {
        try { return new JsonConstantsProvider(java.nio.file.Files.readAllBytes(path)); }
        catch (java.io.IOException e) { throw new RuntimeException("读取 constants.json 失败: " + path, e); }
    }

    @Override public Optional<String> consumableName(int id) { return Optional.ofNullable(consumableStates.get(id)); }
    @Override public Optional<String> deathReasonName(int id) { return Optional.ofNullable(deathReasons.get(id)); }
    @Override public Optional<String> cameraModeName(int id) { return Optional.ofNullable(cameraModes.get(id)); }
    @Override public Optional<String> battleStageName(int id, Version version) { return Optional.ofNullable(battleStages.get(id)); }
    @Override public Optional<String> finishTypeName(int id, Version version) { return Optional.ofNullable(finishTypes.get(id)); }
    @Override public Optional<String> damageStatCategoryName(int id, Version version) { return Optional.ofNullable(damageStatCategories.get(id)); }
    @Override public Map<Integer, String> consumableIds() { return Collections.unmodifiableMap(consumableStates); }
    @Override public Map<Integer, String> battleStages(Version version) { return Collections.unmodifiableMap(battleStages); }
    @Override public Map<Integer, String> finishTypeNames(Version version) { return Collections.unmodifiableMap(finishTypes); }
    @Override public Map<Integer, String> damageStatCategories(Version version) { return Collections.unmodifiableMap(damageStatCategories); }

    /** 获取顶级节点，返回 null 表示该版本无此字段。 */
    public JsonNode section(String name) { return root.get(name); }

    /** 整个 constants.json 的原始 JSON 树。 */
    public JsonNode root() { return root; }

    /** 在 name→value 节点中按名称查找。 */
    public Optional<JsonNode> lookup(String section, String name) {
        var node = root.get(section);
        if (node == null) return Optional.empty();
        var value = node.get(name);
        return value != null ? Optional.of(value) : Optional.empty();
    }

    /** 反向查找：id→name。 */
    public Optional<String> reverseLookup(String section, int id) {
        return Optional.ofNullable(buildReverseLookup(section).get(id));
    }

    /** 此常量文件中所有顶级字段名。 */
    public List<String> sectionNames() {
        return root.propertyNames().stream().toList();
    }

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

    /** 15.x DEATH_REASONS：对象 { "0": {icon,id,name,sound}, ... } 或数组 → id→name。 */
    private Map<Integer, String> buildDeathReasonMap() {
        var node = root.get("DEATH_REASONS");
        if (node != null) {
            var result = new LinkedHashMap<Integer, String>();
            if (node.isObject()) {
                for (var entry : node.properties()) {
                    var v = entry.getValue();
                    var id = v.get("id");
                    var name = v.get("icon");
                    if (id != null && id.isIntegralNumber() && name != null && name.isString()) {
                        result.put(id.intValue(), name.stringValue());
                    }
                }
            } else if (node.isArray()) {
                for (var entry : node) {
                    var id = entry.get("id");
                    var name = entry.get("icon");
                    if (id != null && id.isIntegralNumber() && name != null && name.isString()) {
                        result.put(id.intValue(), name.stringValue());
                    }
                }
            }
            if (!result.isEmpty()) return result;
        }
        return buildReverseLookup("DEATH_REASON_NAME");
    }

    private static Map<Integer, String> firstNonEmpty(Map<Integer, String> a, Map<Integer, String> b) {
        return a.isEmpty() ? b : a;
    }
}
