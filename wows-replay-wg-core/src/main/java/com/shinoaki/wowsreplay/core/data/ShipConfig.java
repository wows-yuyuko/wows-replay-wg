package com.shinoaki.wowsreplay.core.data;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.shinoaki.wowsreplay.core.model.Version;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

/**
 * 玩家船只装载配置解析（对标 Rust {@code wowsunpack::data::ship_config::ShipConfig}）。
 *
 * <p>Vehicle 实体的 {@code shipConfig} 属性是二进制 blob（big-endian 头 + little-endian 各槽段）。
 * 解析出 ship_params_id / modernization / abilities(消耗品) / exteriors / units 等原始 id。</p>
 *
 * <p>blob 布局（little-endian，v13.2+ 多一个 u32）：</p>
 * <pre>
 *   version(u32) ship_params_id(u32) element_count(u32)
 *   unit_count(u32) units[unit_count]
 *   [v13.2+ 额外 u32]
 *   modernization: count + ids
 *   exteriors: count + ids
 *   supply_state(u32)
 *   color_schemes: count + (slot, scheme) 对
 *   abilities: count + ids
 *   ensigns: count + ids
 *   ecoboosts: count + ids
 *   naval_flag(u32)
 *   is_owned(u32) last_boarded_crew(u32)
 * </pre>
 */
public record ShipConfig(
    @JsonProperty("ship_params_id") long shipParamsId,
    @JsonProperty("modernization") List<Long> modernization,
    @JsonProperty("abilities") List<Long> abilities,
    @JsonProperty("units") List<Long> units,
    @JsonProperty("exteriors") List<Long> exteriors,
    @JsonProperty("ensigns") List<Long> ensigns,
    @JsonProperty("ecoboosts") List<Long> ecoboosts,
    @JsonProperty("naval_flag") Long navalFlag,
    @JsonProperty("last_boarded_crew") Long lastBoardedCrew,
    /** 舰长已学技能（原始 skill-type id，6 舰种数组），非 shipConfig blob 字段，由装配层附加。 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonProperty("commander_skills") CommanderSkills commanderSkills,
    /** 舰长 id（crewModifiersCompactParams.paramsId 原值），非 shipConfig blob 字段，由装配层附加。 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonProperty("commander_skills_id") Long commanderSkillsId
) {
    /** 消耗品（对标 Rust {@code ShipConfig::abilities}）。 */
    public List<Long> consumables() { return abilities; }

    /** 附加舰长信息（commander_skills / commander_skills_id），返回新实例。 */
    public ShipConfig withCommander(CommanderSkills commanderSkills, Long commanderSkillsId) {
        return new ShipConfig(shipParamsId, modernization, abilities, units, exteriors,
            ensigns, ecoboosts, navalFlag, lastBoardedCrew, commanderSkills, commanderSkillsId);
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

        if (version != null && version.isAtLeast(new Version(13, 2, 0, 0))) {
            readU32(buf, 0L); // v13.2+ 额外字段
        }

        var modernization = readSection(buf);
        var exteriors = readSection(buf);
        readU32(buf, 0L); // supply_state

        long colorSchemeCount = readU32(buf, 0L);
        for (long i = 0; i < colorSchemeCount; i++) {
            readU32(buf, 0L);
            readU32(buf, 0L);
        }

        var abilities = readSection(buf);
        var ensigns = readSection(buf);
        var ecoboosts = readSection(buf);

        Long navalFlag = readU32Opt(buf);
        Long isOwned = readU32Opt(buf);
        Long lastBoardedCrew = readU32Opt(buf);
        if (isOwned == null) lastBoardedCrew = null; // 全格式尾缺失

        return new ShipConfig(shipParamsId, modernization, abilities, units, exteriors,
            ensigns, ecoboosts, navalFlag, lastBoardedCrew, null, null);
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
