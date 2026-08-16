package com.shinoaki.wowsreplay.ship.model;

import java.util.List;
import java.util.Map;

/**
 * 模块槽里的一个可选模块。
 *
 * @param name        模块名（{@code IDS_...} 键，需经 lang.json 本地化）
 * @param components  组件类型 -> 组件名数组（如 {@code "hull" -> ["A_Hull"]}）
 */
public record ModuleOption(
        int index,
        String name,
        long costXp,
        long costCr,
        Map<String, List<String>> components
) {}
