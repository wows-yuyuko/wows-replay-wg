package com.shinoaki.wowsreplay.core.packet;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.shinoaki.wowsreplay.core.model.EntityId;

import java.util.List;

/**
 * 0x23(新版)/ 0x22(旧版): 嵌套属性更新。用于更新复杂类型的子属性。
 *
 * <p>{@code update} 是位流路径 + 类型化叶子值解码后的结构（
 * {@code PropertyUpdatePacket.update_cmd}）；{@code path} 是路径片段（dict 键 / "[N]" 数组索引），
 * 供摄入层按路径直接应用。载荷非 pickle（见 docs/capture-point-audit.md §3）。</p>
 */
public record PropertyUpdatePacket(
    @JsonProperty("entity_id") EntityId entityId,
    @JsonProperty("property") String property,
    @JsonProperty("update_cmd") Object updateCmd,
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    @JsonProperty("path") List<String> path,
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonProperty("update") NestedUpdate update
) {}
