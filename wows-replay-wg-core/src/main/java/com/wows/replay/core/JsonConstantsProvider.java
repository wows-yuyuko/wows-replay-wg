package com.wows.replay.core;

import com.wows.replay.core.spi.GameConstantsProvider;
import com.wows.replay.core.types.Version;
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
    private final Map<Integer, String> consumableStates;

    /** 从字节数组加载。 */
    public JsonConstantsProvider(byte[] jsonBytes) {
        this.root = JsonMapper.readTree(jsonBytes);
        this.deathReasons = buildReverseLookup("DEATH_REASON_NAME");
        this.cameraModes = buildReverseLookup("CAMERA_MODE");
        this.battleStages = buildReverseLookup("BATTLE_STAGES");
        this.consumableStates = buildReverseLookup("CONSUMABLE_STATES");
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
    @Override public Map<Integer, String> consumableIds() { return Collections.unmodifiableMap(consumableStates); }
    @Override public Map<Integer, String> battleStages(Version version) { return Collections.unmodifiableMap(battleStages); }

    /** 获取顶级节点，返回 null 表示该版本无此字段。 */
    public JsonNode section(String name) { return root.get(name); }

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
}
