package com.shinoaki.wowsreplay.core.packet;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.shinoaki.wowsreplay.core.types.ArgValue;

import java.util.List;

/**
 * 嵌套属性更新的解码结果（nested_property_path::PropertyNesting / UpdateAction）。
 *
 * <p>路径位（cont + 容器索引）走查完毕后，更新命令作用于路径末端（最后一个 dict 键 / 数组元素）：</p>
 * <ul>
 *   <li>{@link SetKey} — FixedDict 末端：设置字段（如 {@code captureLogic.progress = ...}）；</li>
 *   <li>{@link SetElement} — 数组末端：设置单个元素；</li>
 *   <li>{@link SetRange} — 数组切片替换（slice 形式，start..stop 替换为 values）；</li>
 *   <li>{@link RemoveRange} — 数组切片删除。</li>
 * </ul>
 */
public sealed interface NestedUpdate {

    /** FixedDict 字段设置：{@code dict[key] = value}（leaf 字段 key 见此记录）。 */
    record SetKey(
        @JsonProperty("key") String key,
        @JsonProperty("value") ArgValue value
    ) implements NestedUpdate {}

    /** 数组单元素设置：{@code arr[index] = value}。 */
    record SetElement(
        @JsonProperty("index") int index,
        @JsonProperty("value") ArgValue value
    ) implements NestedUpdate {}

    /** 数组切片替换：{@code arr[start..stop] = values}（Python 切片语义）。 */
    record SetRange(
        @JsonProperty("start") int start,
        @JsonProperty("stop") int stop,
        @JsonProperty("values") List<ArgValue> values
    ) implements NestedUpdate {}

    /** 数组切片删除：{@code del arr[start..stop]}。 */
    record RemoveRange(
        @JsonProperty("start") int start,
        @JsonProperty("stop") int stop
    ) implements NestedUpdate {}
}
