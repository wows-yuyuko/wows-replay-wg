package com.shinoaki.wowsreplay.ship.view;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import tools.jackson.databind.JsonNode;

import java.util.List;

/**
 * 战舰详细配置的本地化视图（查看结果）。
 *
 * <p>名称类字段均为本地化后的显示文本；组件 {@code definition} 为 wowsinfo 组件库里的原始定义（数字，无需翻译）。</p>
 */
public record ShipConfigView(
        @JsonProperty("ship_id") long shipId,
        @JsonProperty("index") String index,
        @JsonProperty("name") String name,
        @JsonProperty("description") String description,
        @JsonProperty("year") String year,
        @JsonProperty("tier") int tier,
        @JsonProperty("nation") String nation,
        @JsonProperty("nation_name") String nationName,
        @JsonProperty("ship_type") String shipType,
        @JsonProperty("ship_type_name") String shipTypeName,
        @JsonProperty("group") String group,
        @JsonProperty("premium") boolean premium,
        @JsonProperty("special") boolean special,
        @JsonProperty("cost_credit") long costCredit,
        @JsonProperty("cost_gold") long costGold,
        @JsonProperty("cost_xp") long costXp,
        @JsonProperty("alias") @JsonInclude(JsonInclude.Include.NON_NULL) String alias,
        @JsonProperty("modules") List<ModuleSlotView> modules,
        @JsonProperty("consumables") List<ConsumableView> consumables,
        @JsonProperty("next_ships") List<NextShipView> nextShips,
        @JsonProperty("camos") List<CamoView> camos
) {

    /** 一个模块槽及其全部可选模块。 */
    public record ModuleSlotView(
            @JsonProperty("slot") String slot,
            @JsonProperty("label") String label,
            @JsonProperty("options") List<ModuleOptionView> options
    ) {}

    /** 一个可选模块：本地化名称 + 成本 + 引用组件。 */
    public record ModuleOptionView(
            @JsonProperty("index") int index,
            @JsonProperty("name") String name,
            @JsonProperty("cost_xp") long costXp,
            @JsonProperty("cost_cr") long costCr,
            @JsonProperty("components") List<ComponentGroupView> components
    ) {}

    /** 按组件类型分组的一组组件引用。 */
    public record ComponentGroupView(
            @JsonProperty("type") String type,
            @JsonProperty("type_label") String typeLabel,
            @JsonProperty("components") List<ComponentView> components
    ) {}

    /** 单个组件：名称 + 原始定义（找不到定义时为 null）。 */
    public record ComponentView(
            @JsonProperty("name") String name,
            @JsonProperty("definition") @JsonInclude(JsonInclude.Include.NON_NULL) JsonNode definition
    ) {}

    /** 一个消耗品（本地化名称/类型 + 图标 + 关键参数）。 */
    public record ConsumableView(
            @JsonProperty("key") String key,
            @JsonProperty("name") String name,
            @JsonProperty("description") String description,
            @JsonProperty("type") String type,
            @JsonProperty("type_name") String typeName,
            @JsonProperty("icon") String icon,
            @JsonProperty("reload_s") double reloadS,
            @JsonProperty("work_s") double workS,
            @JsonProperty("preparation_s") double preparationS,
            @JsonProperty("charges") long charges
    ) {}

    /** 研发线下一级舰船。 */
    public record NextShipView(
            @JsonProperty("ship_id") long shipId,
            @JsonProperty("index") String index,
            @JsonProperty("name") String name,
            @JsonProperty("tier") int tier
    ) {}

    /** 永久涂装（本地化名称）。 */
    public record CamoView(
            @JsonProperty("key") String key,
            @JsonProperty("name") String name
    ) {}
}
