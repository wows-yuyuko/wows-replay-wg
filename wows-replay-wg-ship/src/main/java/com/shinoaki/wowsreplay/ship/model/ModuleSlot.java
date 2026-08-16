package com.shinoaki.wowsreplay.ship.model;

import java.util.List;

/**
 * 一个可研发模块槽（{@code ships.<id>.modules.<slotKey>}）。
 *
 * @param key      wowsinfo 原始槽位键（如 {@code "_Hull"}）
 * @param label    规范化标识（如 {@code "hull"}）
 * @param options  该槽位的可选模块（0 号通常是白板）
 */
public record ModuleSlot(
        String key,
        String label,
        List<ModuleOption> options
) {}
