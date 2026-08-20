package com.shinoaki.wowsreplay.ingest;

/**
 * 勋带事件。
 *
 * @param clock    事件时钟（秒）
 * @param ribbonId 勋带类型 id（回放原始值）
 * @param name     勋带枚举名（来自 constants.json RIBBONS 表，如 {@code RIBBON_CITADEL}；
 *                 未知 id 回退 {@code "ribbon_" + id}；本地化显示文本暂不做）
 */
public record RibbonEvent(float clock, int ribbonId, String name) {

    public RibbonEvent {
        if (name == null) name = "ribbon_" + ribbonId;
    }
}
