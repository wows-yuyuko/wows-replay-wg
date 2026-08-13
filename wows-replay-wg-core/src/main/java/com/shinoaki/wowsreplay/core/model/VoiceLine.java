package com.shinoaki.wowsreplay.core.model;

/**
 * 快捷指令解析结果（{@code receive_CommonCMD} 的指令 id + 参数）。
 *
 * <p>{@code line}=指令 id；{@code type}=指令类型名（如 {@code MapPointAttention}/{@code QuickTactic}/
 * {@code Retreat}/{@code Wilco}…）；{@code data}=指令参数，仅带参类型有值（其余为 null）。</p>
 */
public record VoiceLine(int line, String type, VoiceLineData data) {

    /**
     * 指令参数。
     *
     * <ul>
     *   <li>{@code x}/{@code z}：地图坐标（{@code AttentionToSquare} / {@code MapPointAttention}）。</li>
     *   <li>{@code tacticTypeId}：快速战术子类型 id（{@code QuickTactic} 第一个参数）。</li>
     *   <li>{@code targetEntityId}：目标实体 id（{@code QuickTactic}/{@code Retreat}；dumper 输出层再映射为玩家 metaId）。</li>
     * </ul>
     */
    public record VoiceLineData(Float x, Float z, Integer tacticTypeId, Long targetEntityId) {}

    /** 无参数指令。 */
    public static VoiceLine plain(int line, String type) {
        return new VoiceLine(line, type, null);
    }

    /** 坐标类指令（AttentionToSquare / MapPointAttention）。 */
    public static VoiceLine coordinate(int line, String type, float x, float z) {
        return new VoiceLine(line, type, new VoiceLineData(x, z, null, null));
    }

    /** 快速战术指令（战术子类型 id + 目标实体 id）。 */
    public static VoiceLine tactic(int line, int tacticTypeId, long targetEntityId) {
        return new VoiceLine(line, "QuickTactic", new VoiceLineData(null, null, tacticTypeId, targetEntityId));
    }

    /** 撤退指令（目标实体 id）。 */
    public static VoiceLine retreat(int line, long targetEntityId) {
        return new VoiceLine(line, "Retreat", new VoiceLineData(null, null, null, targetEntityId));
    }
}
