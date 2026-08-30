package com.shinoaki.wowsreplay.core.model;

import java.util.Map;

/**
 * 快捷指令解析结果（{@code receive_CommonCMD}）。
 *
 * <p>对应 WG 客户端 {@code CommonQuickCommands.QuickCommands}（scripts.zip 15.7）：
 * 指令 blob 格式为 {@code commandType(H) sendToAll(?) [按命令类的附加参数]}。</p>
 *
 * <ul>
 *   <li>{@code line}=commandType（{@link #COMMAND_TYPE_NAMES}，QuickCommandType 1–20）。</li>
 *   <li>{@code type}=指令类型名（如 {@code tactic}/{@code map_point_attention}/{@code back}…）。</li>
 *   <li>{@code data}=指令附加参数，按命令类取不同字段（其余为 null）：
 *     <ul>
 *       <li>RectangleAttentionCommand → {@code row}/{@code column}（2×short）</li>
 *       <li>MapPointQuickCommand → {@code x}/{@code y}（2×float）</li>
 *       <li>TargetQuickCommand → {@code targetType}/{@code targetId}（short + long）</li>
 *       <li>EmptyQuickCommand → 无</li>
 *     </ul>
 *   </li>
 * </ul>
 */
public record VoiceLine(int line, String type, VoiceLineData data) {

    /** 指令附加参数。 */
    public record VoiceLineData(Integer row, Integer column, Float x, Float y,
                                Integer targetType, Long targetId) {}

    /** QuickCommandType（commandType 1–20）→ 枚举名。 */
    public static final Map<Integer, String> COMMAND_TYPE_NAMES = Map.ofEntries(
            Map.entry(1, "map_rect_attention"),        // 标记矩形区域（RectangleAttentionCommand，row/column 方格）
            Map.entry(2, "tactic"),                    // 战术指令（标记/集火目标，QCmd_Tactic）
            Map.entry(3, "need_support"),              // 请求支援
            Map.entry(4, "sos"),                       // 求救
            Map.entry(5, "aye_aye"),                   // 收到/遵命
            Map.entry(6, "no_way"),                    // 否定/不行
            Map.entry(7, "good_game"),                 // 干得好/好局
            Map.entry(8, "good_luck"),                 // 好运/顺风
            Map.entry(9, "caramba"),                   // 惊讶/咒骂
            Map.entry(10, "thank_you"),                // 谢谢
            Map.entry(11, "need_air_defence"),         // 请求防空支援
            Map.entry(12, "back"),                     // 撤退/回来
            Map.entry(13, "need_vision"),              // 请求视野/情报
            Map.entry(14, "need_smoke"),               // 请求拉烟
            Map.entry(15, "using_rls"),                // 使用雷达（无线电定位）
            Map.entry(16, "using_sonar"),              // 使用声纳/水听
            Map.entry(17, "follow_me"),                // 跟我来
            Map.entry(18, "map_point_attention"),      // 标记地图坐标点（MapPointQuickCommand，x/y）
            Map.entry(19, "using_submarine_locator"),  // 使用反潜定位
            Map.entry(20, "any")                       // 任意/通用
    );

    /** ENTITY_TYPES（targetType 枚举）→ 名。 */
    public static final Map<Integer, String> ENTITY_TYPES = Map.ofEntries(
            Map.entry(-1, "INVALID"),
            Map.entry(0, "SHIP"),
            Map.entry(1, "PLANE"),
            Map.entry(2, "TORPEDO"),
            Map.entry(3, "BUILDING"),
            Map.entry(11, "CAPTURE_POINT"),
            Map.entry(12, "PLAYER"),
            Map.entry(13, "EPICENTER"),
            Map.entry(14, "SCENARIO_OBJECT"),
            Map.entry(15, "DROP_ZONE"),
            Map.entry(16, "ATTENTION_POINT"),
            Map.entry(17, "KEY_OBJECT"),
            Map.entry(18, "INTERACTIVE_ZONE"),
            Map.entry(27, "PINATA_SHIP"),
            Map.entry(28, "STARTREK_SCRAP"),
            Map.entry(29, "MISSILE"),
            Map.entry(99, "NAVPOINT"),
            Map.entry(100, "EMPTY")
    );

    /** commandType → 指令类型名；未知返回 {@code "UnknownVoiceLine"}。 */
    public static String commandTypeName(int line) {
        return COMMAND_TYPE_NAMES.getOrDefault(line, "UnknownVoiceLine");
    }

    /** targetType id → 名；未知返回 {@code "UNKNOWN_" + id}。 */
    public static String entityTypeName(int targetType) {
        return ENTITY_TYPES.getOrDefault(targetType, "UNKNOWN_" + targetType);
    }

    /** EmptyQuickCommand（无附加参数）。 */
    public static VoiceLine empty(int line) {
        return new VoiceLine(line, commandTypeName(line), null);
    }

    /** RectangleAttentionCommand（row/column，2×short）。 */
    public static VoiceLine rect(int line, int row, int column) {
        return new VoiceLine(line, commandTypeName(line),
                new VoiceLineData(row, column, null, null, null, null));
    }

    /** MapPointQuickCommand（x/y，2×float）。 */
    public static VoiceLine mapPoint(int line, float x, float y) {
        return new VoiceLine(line, commandTypeName(line),
                new VoiceLineData(null, null, x, y, null, null));
    }

    /** TargetQuickCommand（targetType + targetId）。 */
    public static VoiceLine target(int line, int targetType, long targetId) {
        return new VoiceLine(line, commandTypeName(line),
                new VoiceLineData(null, null, null, null, targetType, targetId));
    }
}
