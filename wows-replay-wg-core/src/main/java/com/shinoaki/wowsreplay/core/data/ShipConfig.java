package com.shinoaki.wowsreplay.core.data;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.shinoaki.wowsreplay.core.model.Version;
import lombok.extern.slf4j.Slf4j;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 玩家船只装载配置解析（对标 Rust {@code wowsunpack::data::ship_config::ShipConfig}）。
 *
 * <p>Vehicle 实体的 {@code shipConfig} 属性是二进制 blob（big-endian 头 + little-endian 各槽段）。
 * 解析出 ship_params_id / modernization / abilities(消耗品) / exteriors / units 等原始 id，
 * 以及 v13.2+ 额外字段 / supply_state / color_schemes / exp（仅解析不输出，见字段注释）。</p>
 *
 * <p>{@code commander_skills} / {@code commander_skills_id} <b>不是 blob 字段</b>——它们来自
 * 同一 Vehicle EntityCreate 的 {@code crewModifiersCompactParams} 属性（舰长参数），由装配层
 * {@code BattleReportBuilder} 在 {@link #parse} 之后通过 {@link #withCommander} 附加，
 * 保留原始值（skill-type id 数组 / paramsId 原值），不做名称解析。</p>
 *
 * <p>blob 布局（little-endian，v13.2+ 多一个 u32）：</p>
 * <pre>
 *   version(u32) ship_params_id(u32) element_count(u32)
 *   unit_count(u32) units[unit_count]
 *   [v13.2+ 额外 u32（源码注释 _unk；实测均为 0，非贴花）]
 *   modernization: count + ids
 *   exteriors: count + ids
 *   supply_state(u32)（实测 0/2）
 *   color_schemes: count + (外观物品 id, 配色方案 id) 对
 *   abilities: count + ids
 *   ensigns: count + ids
 *   ecoboosts: count + ids
 *   naval_flag(u32)
 *   is_owned(u32) exp(u32) last_boarded_crew(u32)
 * </pre>
 */
@Slf4j
public record ShipConfig(
    /** GameParams 舰船模板 ID（wowsinfo.ships 索引；同型号船共享，非战斗内 meta id）。 */
    @JsonProperty("ship_params_id") long shipParamsId,
    /** 升级品槽（ModernizationSlots）物品 GameParams id。 */
    @JsonProperty("modernization") List<Long> modernization,
    /** 消耗品槽（AbilitySlots）物品 GameParams id（= vehicle.consumables）。 */
    @JsonProperty("abilities") List<Long> abilities,
    /** 单位/模块槽（UNIT_TYPE_NAMES，固定 14：船体/引擎/火控/武器/飞机等）GameParams id。 */
    @JsonProperty("units") List<Long> units,
    /** 外观槽（ExteriorSlots：信号旗/涂装/皮肤/旗帜等）物品 GameParams id。 */
    @JsonProperty("exteriors") List<Long> exteriors,
    /** 舰旗槽（EnsignSlots）物品 GameParams id。 */
    @JsonProperty("ensigns") List<Long> ensigns,
    /** 经济加成槽（EcoboostSlots）物品 GameParams id。 */
    @JsonProperty("ecoboosts") List<Long> ecoboosts,
    /** 海军旗（NationFlags 索引）。 */
    @JsonProperty("naval_flag") Long navalFlag,
    /** 上次上船的舰长/船员 GameParams id（Commander 参数模板 ID）。 */
    @JsonProperty("last_boarded_crew") Long lastBoardedCrew,
    /** 舰长已学技能（crewModifiersCompactParams.learnedSkills 的 6 舰种 skill-type id 数组，原始值）。
     *  非 shipConfig blob 字段——由装配层在 parse 后经 {@link #withCommander} 附加（同一 EntityCreate）。 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonProperty("commander_skills") CommanderSkills commanderSkills,
    /** 舰长 id（crewModifiersCompactParams.paramsId 原值，u32 不转换，保真）。
     *  非 shipConfig blob 字段——由装配层在 parse 后经 {@link #withCommander} 附加（同一 EntityCreate）。 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonProperty("commander_skills_id") Long commanderSkillsId,
    /** v13.2+ 额外 u32（units 槽之后；源码注释 _unk，非贴花——贴花在 exteriors 段编码）。仅供解析，不输出。 */
    @JsonIgnore Long extraV132,
    /** 补给状态（用途未知，通常 0）。仅供解析，不输出。 */
    @JsonIgnore Long supplyState,
    /** 外观槽位配色方案：(外观/涂装物品 GameParams id, 配色方案 id) 映射表。仅供解析，不输出。 */
    @JsonIgnore List<ColorScheme> colorSchemes,
    /** 精英经验/舰船经验值（isOwned 与 last_boarded_crew 之间；Rust 参考实现漏读此字段）。仅供解析，不输出。 */
    @JsonIgnore Long exp
) {
    /** 外观→配色映射：记录「哪个外观/涂装物品用了哪个配色方案」（第一值为物品 GameParams id，非槽位序号）。 */
    public record ColorScheme(long itemId, long scheme) {}

    /** 消耗品（对标 Rust {@code ShipConfig::abilities}）。 */
    public List<Long> consumables() { return abilities; }

    /** 附加舰长信息（来自同一 Vehicle EntityCreate 的 crewModifiersCompactParams），返回新实例。
     *  仅在 shipConfig blob 解析成功且船员参数存在时由装配层调用；缺失时保持 null。 */
    public ShipConfig withCommander(CommanderSkills commanderSkills, Long commanderSkillsId) {
        return new ShipConfig(shipParamsId, modernization, abilities, units, exteriors,
            ensigns, ecoboosts, navalFlag, lastBoardedCrew, commanderSkills, commanderSkillsId,
            extraV132, supplyState, colorSchemes, exp);
    }

    /**
     * 从二进制 blob 解析；早期版本截断的 blob 在字节耗尽处停止（对标 Rust take_section）。
     */
    public static ShipConfig parse(byte[] blob, Version version) {
        if (blob == null) return null;
        var buf = ByteBuffer.wrap(blob).order(ByteOrder.LITTLE_ENDIAN);

        readU32(buf, 0L); // blob version
        long shipParamsId = readU32(buf, 0L);
        readU32(buf, 0L); // element count

        long unitCount = readU32(buf, 0L);
        var units = readIds(buf, unitCount);

        Long extraV132 = null;
        if (version != null && version.isAtLeast(new Version(13, 2, 0, 0))) {
            extraV132 = readU32Opt(buf); // v13.2+ 额外字段（源码注释 _unk，非贴花）
        }

        var modernization = readSection(buf);
        var exteriors = readSection(buf);
        Long supplyState = readU32Opt(buf);

        long colorSchemeCount = readU32(buf, 0L);
        var colorSchemes = new ArrayList<ColorScheme>();
        for (long i = 0; i < colorSchemeCount; i++) {
            colorSchemes.add(new ColorScheme(readU32(buf, 0L), readU32(buf, 0L)));
        }

        var abilities = readSection(buf);
        var ensigns = readSection(buf);
        var ecoboosts = readSection(buf);

        Long navalFlag = readU32Opt(buf);
        Long isOwned = readU32Opt(buf);
        Long exp = readU32Opt(buf);
        Long lastBoardedCrew = readU32Opt(buf);
        if (isOwned == null) lastBoardedCrew = null; // 全格式尾缺失

        // 末尾检测：解析完仍有剩余字节 → 疑似 WG 新增未识别字段/布局变化，打 WARN 提示核对。
        if (buf.remaining() > 0) {
            log.warn("shipConfig blob 解析后仍有 {} 字节未识别（疑似新增字段或布局变化，ship_params_id={}, blob={}B）",
                buf.remaining(), shipParamsId, blob.length);
        }

        return new ShipConfig(shipParamsId, modernization, abilities, units, exteriors,
            ensigns, ecoboosts, navalFlag, lastBoardedCrew, null, null,
            extraV132, supplyState, colorSchemes, exp);
    }

    /**
     * 逆向诊断用的完整字节级分解（与 {@link #parse} 同一布局逻辑，额外记录每个 u32 的偏移/数值与
     * 解析剩余字节）。输出给外部逆向项目对照游戏脚本（ship_params_id → XML 配置）还原 blob 结构。
     *
     * <p>返回字段：header/各槽段具名值 + {@code consumed_bytes}/{@code unparsed_size}/
     * {@code unparsed_hex}（当前解析器未识别的尾部字节）+ {@code u32s} 原始 4 字节序列
     * （{@code off}/{@code hex}/{@code val}，逆推布局用）。</p>
     */
    public static Map<String, Object> hexDump(byte[] blob, Version version) {
        var m = new LinkedHashMap<String, Object>();
        if (blob == null) {
            m.put("size", 0);
            return m;
        }
        m.put("size", blob.length);
        m.put("hex", HexFormat.of().formatHex(blob));

        var buf = ByteBuffer.wrap(blob).order(ByteOrder.LITTLE_ENDIAN);

        m.put("header_version", readU32(buf, 0L));
        m.put("ship_params_id", readU32(buf, 0L));
        m.put("element_count", readU32(buf, 0L));

        long unitCount = readU32(buf, 0L);
        m.put("unit_count", unitCount);
        m.put("units", readIds(buf, unitCount));

        if (version != null && version.isAtLeast(new Version(13, 2, 0, 0))) {
            m.put("extra_v132", readU32(buf, 0L));
        }

        m.put("modernization", readSection(buf));
        m.put("exteriors", readSection(buf));
        m.put("supply_state", readU32(buf, 0L));

        long colorSchemeCount = readU32(buf, 0L);
        m.put("color_scheme_count", colorSchemeCount);
        var colorSchemes = new ArrayList<List<Long>>();
        for (long i = 0; i < colorSchemeCount; i++) {
            colorSchemes.add(List.of(readU32(buf, 0L), readU32(buf, 0L)));
        }
        m.put("color_schemes", colorSchemes);

        m.put("abilities", readSection(buf));
        m.put("ensigns", readSection(buf));
        m.put("ecoboosts", readSection(buf));

        m.put("naval_flag", readU32(buf, 0L));
        m.put("is_owned", readU32(buf, 0L));
        m.put("exp", readU32(buf, 0L));
        m.put("last_boarded_crew", readU32(buf, 0L));

        int consumed = blob.length - buf.remaining();
        m.put("consumed_bytes", consumed);
        m.put("unparsed_size", buf.remaining());
        m.put("unparsed_hex", buf.remaining() > 0
            ? HexFormat.of().formatHex(blob, consumed, blob.length) : "");

        var u32s = new ArrayList<Map<String, Object>>();
        var raw = ByteBuffer.wrap(blob).order(ByteOrder.LITTLE_ENDIAN);
        while (raw.remaining() >= 4) {
            int off = raw.position();
            long v = raw.getInt() & 0xFFFFFFFFL;
            var e = new LinkedHashMap<String, Object>();
            e.put("off", off);
            e.put("hex", String.format("%08x", v));
            e.put("val", v);
            u32s.add(e);
        }
        m.put("u32s", u32s);
        return m;
    }

    /** 读 count(u32) + count 个 id(u32)；字节不足时返回已读部分。 */
    private static List<Long> readSection(ByteBuffer buf) {
        if (buf.remaining() < 4) return new ArrayList<>();
        long count = buf.getInt() & 0xFFFFFFFFL;
        return readIds(buf, count);
    }

    private static List<Long> readIds(ByteBuffer buf, long count) {
        var out = new ArrayList<Long>();
        for (long i = 0; i < count; i++) {
            if (buf.remaining() < 4) break;
            out.add(buf.getInt() & 0xFFFFFFFFL);
        }
        return out;
    }

    private static long readU32(ByteBuffer buf, long fallback) {
        return buf.remaining() >= 4 ? (buf.getInt() & 0xFFFFFFFFL) : fallback;
    }

    private static Long readU32Opt(ByteBuffer buf) {
        return buf.remaining() >= 4 ? (buf.getInt() & 0xFFFFFFFFL) : null;
    }
}
