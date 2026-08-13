package com.shinoaki.wowsreplay.core.constant;

import com.shinoaki.wowsreplay.core.model.Version;
import com.shinoaki.wowsreplay.core.model.VoiceLine;
import com.shinoaki.wowsreplay.core.spi.GameConstantsProvider;
import lombok.extern.slf4j.Slf4j;

import java.nio.ByteBuffer;
import java.util.Map;

/**
 * 游戏内常量 id↔名称布局的统一管理器。
 *
 * <p>把散落在各层（{@code BattleWorld} / {@code FinishType} / {@code DamageStatCategory} /
 * {@code MinimapExtractor} / {@code PacketDecoder}）的内部硬编码 id→名称映射集中到本类统一维护，
 * 消除"两套并存"的重复布局。所有查找方法都要求携带 {@link Version}——常量布局可能随客户端版本变化，
 * 调用方必须把回放版本传进来。</p>
 *
 * <h2>查找优先级（自上而下）</h2>
 * <ol>
 *   <li><b>本类静态表</b>：规范显示名（对齐 Rust 枚举 Debug 输出），跨版本稳定。
 *       版本只<em>新增</em> id 时本表不含该 id → 落到下一步。</li>
 *   <li><b>{@link GameConstantsProvider}</b>（外部 per-version 数据，如 constants.json）：
 *       版本漂移 / 新增 id 的兜底名；未注入时使用 {@link GameConstantsProvider#empty()}。</li>
 *   <li><b>原始回退串</b>：{@code "Name(id v<版本>)"}，附带回放版本号，保证永远可读、可追溯、不抛异常。</li>
 * </ol>
 *
 * <p><b>关于规范名与外部名不一致</b>：外部数据（constants.json）存的是<b>内部枚举标识</b>
 * （如 {@code BASE}、{@code SCORE_ON_TIMEOUT}、{@code DAMAGE_STATS_ENEMY}），而 Rust 战报输出
 * 用的是 PascalCase 变体名（{@code BaseCaptured}、{@code ScoreOnTimeout}、{@code Enemy}）。
 * 两者并不一一对应（如外部 {@code BASE} → 显示 {@code BaseCaptured}），因此规范显示名以本类静态表为准，
 * 外部名只用于本表未收录（新增/漂移）的 id 兜底。</p>
 *
 * <h2>覆盖布局</h2>
 * <ul>
 *   <li>{@code FINISH_TYPE}（battle.xml / constants.json）— {@link #finishTypeName}</li>
 *   <li>{@code BATTLE_STAGES}（constants.json）— {@link #battleStageName}</li>
 *   <li>{@code DAMAGE_STATS}（constants.json）— {@link #damageStatCategoryName}</li>
 *   <li>VoiceLine 快捷指令（客户端 Python 数据，本地无源）— {@link #voiceLineName}</li>
 * </ul>
 */
@Slf4j
public final class GameConstants {

    private final GameConstantsProvider provider;

    /**
     * 包装外部常量 provider；{@code null} → 空实现（§12.4.3：无 GameConstants 用默认实现）。
     */
    public GameConstants(GameConstantsProvider provider) {
        this.provider = provider != null ? provider : GameConstantsProvider.empty();
    }

    /** 无外部数据源的实例（仅本类静态表 + 原始回退生效）。 */
    public static GameConstants empty() {
        return new GameConstants(null);
    }

    /** 版本号显示串（null 安全）：{@code major.minor.patch.build}，未知时为 {@code "?"}。 */
    private static String ver(Version version) {
        return version != null ? version.toString() : "?";
    }

    // ── FINISH_TYPE ────────────────────────────────────────────────────

    /**
     * {@code FINISH_TYPE} id → 规范显示名。
     *
     * <p>数据源：游戏 {@code Scripts/constants/battle.xml} 的 {@code <enum name="FINISH_TYPE">}
     * （同源 constants.json 的 {@code "FINISH_TYPE"} 段，name→id）。id 与外部一致，但<b>显示名</b>
     * 采用 Rust {@code FinishType} 枚举变体名（PascalCase），保证与 Rust 战报输出一致。
     * 15.6.0 实测取值 0-13，其中 6、7 无定义。5.x 之后 PvE 主线任务新增 10/11。</p>
     *
     * <p>查找链：本表 → {@code provider.finishTypeName} → {@code "FinishType(id v<版本>)"}。</p>
     *
     * @param id      原始 FINISH_TYPE id（BattleLogic {@code battleResult.finishReason}）
     * @param version 回放版本（透传给 provider，供版本化外部数据查名；未知 id 回退串会带上版本号）
     */
    public String finishTypeName(int id, Version version) {
        String known = FINISH_TYPE_NAMES.get(id);
        if (known != null) return known;
        return provider.finishTypeName(id, version).orElse("FinishType(" + id + " v" + ver(version) + ")");
    }

    private static final Map<Integer, String> FINISH_TYPE_NAMES = Map.ofEntries(
        Map.entry(0, "Unknown"),
        Map.entry(1, "Extermination"),
        Map.entry(2, "BaseCaptured"),
        Map.entry(3, "Timeout"),
        Map.entry(4, "Failure"),
        Map.entry(5, "Technical"),
        Map.entry(8, "Score"),
        Map.entry(9, "ScoreOnTimeout"),
        Map.entry(10, "PveMainTaskSucceeded"),
        Map.entry(11, "PveMainTaskFailed"),
        Map.entry(12, "ScoreZero"),
        Map.entry(13, "ScoreExcess")
    );

    // ── BATTLE_STAGES ──────────────────────────────────────────────────

    /**
     * {@code BATTLE_STAGES} id → 阶段名（0=Waiting..4=Ended）。
     *
     * <p>数据源：constants.json {@code "BATTLE_STAGES"}（同源 common.xml）。
     * 外部名与显示名仅大小写差异（WAITING/BATTLE/RESULTS/FINISHING/ENDED），
     * 本表取 Rust {@code BattleStage} Debug 形式。对应 BattleLogic {@code battleStage} 属性。</p>
     *
     * <p>查找链：本表 → {@code provider.battleStageName(id, version)} → {@code "Stage(id v<版本>)"}。</p>
     *
     * @param id      原始阶段 id（BattleLogic {@code battleStage} 属性）
     * @param version 回放版本（provider 签名要求，阶段常量随版本变化；未知 id 回退串会带上版本号）
     */
    public String battleStageName(int id, Version version) {
        String known = BATTLE_STAGE_NAMES.get(id);
        if (known != null) return known;
        return provider.battleStageName(id, version).orElse("Stage(" + id + " v" + ver(version) + ")");
    }

    private static final Map<Integer, String> BATTLE_STAGE_NAMES = Map.ofEntries(
        Map.entry(0, "Waiting"),
        Map.entry(1, "Battle"),
        Map.entry(2, "Results"),
        Map.entry(3, "Finishing"),
        Map.entry(4, "Ended")
    );

    // ── DAMAGE_STATS ───────────────────────────────────────────────────

    /**
     * {@code DAMAGE_STATS} categoryId → 伤害类别名（{@code receiveDamageStat} pickle 的 categoryId）。
     *
     * <p>数据源：constants.json {@code "DAMAGE_STATS"}（DAMAGE_STATS_ENEMY=0, ALLY=1, SPOT=2, AGRO=3）。
     * 显示名 = {@code DamageStatCategory} 枚举常量名（服务端权威自我伤害统计的武器类别）。</p>
     *
     * <p>查找链：本表 → {@code provider.damageStatCategoryName} → {@code "DamageStatCategory(id v<版本>)"}。</p>
     *
     * @param id      原始类别 id
     * @param version 回放版本（透传给 provider；未知 id 回退串会带上版本号）
     */
    public String damageStatCategoryName(int id, Version version) {
        String known = DAMAGE_STAT_CATEGORY_NAMES.get(id);
        if (known != null) return known;
        return provider.damageStatCategoryName(id, version).orElse("DamageStatCategory(" + id + " v" + ver(version) + ")");
    }

    private static final Map<Integer, String> DAMAGE_STAT_CATEGORY_NAMES = Map.ofEntries(
        Map.entry(0, "Enemy"),
        Map.entry(1, "Ally"),
        Map.entry(2, "Spot"),
        Map.entry(3, "Agro")
    );

    // ── VoiceLine（快捷指令）───────────────────────────────────────────

    /**
     * VoiceLine 快捷指令 id + 参数 → {@link VoiceLine}（>=0.12.8 布局，参数从 blob 中消费）。
     *
     * <p><b>数据源说明</b>：快捷指令（AttentionToSquare / QuickTactic / Wilco …）来自客户端
     * Python UI 代码（快速指令菜单），本地游戏数据（constants.json / constants/*.xml / scripts）
     * 中<b>没有</b>对应常量表，因此本布局只能内部维护，无外部兜底。</p>
     *
     * <p>两个布局的区别在<b>参数来源</b>：新版（>=0.12.8，本方法）参数内嵌在 blob 里逐项读取；
     * 旧版（{@code voiceLine(int, Version, int, long)}）参数由调用方作为整数传入。
     * 调用方按回放版本选择方法（docs §10.2：0.12.8 起参数格式变化）。</p>
     *
     * <p>注意：本方法<b>会消费 {@code buf}</b> 的位置（读取指令附加参数）。</p>
     *
     * @param line    指令 id（blob 前 2 字节 LE short）
     * @param version 回放版本（>=0.12.8 使用本布局）
     * @param buf     指令 blob（小端），附加参数从此读取
     */
    public VoiceLine voiceLine(int line, Version version, ByteBuffer buf) {
        return switch (line) {
            case 1 -> VoiceLine.coordinate(line, "AttentionToSquare", buf.getShort(), buf.getShort());
            case 2 -> {
                int tacticTypeId = buf.getShort();
                long target = buf.getLong();
                yield VoiceLine.tactic(line, tacticTypeId, target);
            }
            case 3 -> VoiceLine.plain(line, "RequestingSupport");
            case 5 -> VoiceLine.plain(line, "Wilco");
            case 6 -> VoiceLine.plain(line, "Negative");
            case 7 -> VoiceLine.plain(line, "WellDone");
            case 8 -> VoiceLine.plain(line, "FairWinds");
            case 9 -> VoiceLine.plain(line, "Curses");
            case 10 -> VoiceLine.plain(line, "DefendTheBase");
            case 11 -> VoiceLine.plain(line, "ProvideAntiAircraft");
            case 12 -> {
                buf.getShort();
                long target = buf.getLong();
                yield VoiceLine.retreat(line, target);
            }
            case 13 -> VoiceLine.plain(line, "IntelRequired");
            case 14 -> VoiceLine.plain(line, "SetSmokeScreen");
            case 15 -> VoiceLine.plain(line, "UsingRadar");
            case 16 -> VoiceLine.plain(line, "UsingHydroSearch");
            case 17 -> VoiceLine.plain(line, "FollowMe");
            case 18 -> VoiceLine.coordinate(line, "MapPointAttention", buf.getFloat(), buf.getFloat());
            case 19 -> VoiceLine.plain(line, "UsingSubmarineLocator");
            default -> {
                log.warn("未知快捷指令 line id: {}（版本 {}，可能为 WG 新增指令，需在 GameConstants 补映射）", line, version);
                yield new VoiceLine(line, "UnknownVoiceLine", null);
            }
        };
    }

    /**
     * VoiceLine 快捷指令 id + 参数 → {@link VoiceLine}（&lt;0.12.8 旧布局，参数由调用方传入）。
     *
     * <p>与 {@link #voiceLine(int, Version, ByteBuffer)} 相同的 id→类型布局，仅参数来源不同：
     * 旧版 {@code receive_CommonCMD} 的参数位直接是整数（isGlobal, senderId, line, arg, arg2），
     * 无 blob 消费。见上述新版方法的布局说明。</p>
     *
     * @param line    指令 id
     * @param version 回放版本（&lt;0.12.8 使用本布局）
     * @param a       指令附加参数（短型参数位）
     * @param b       指令附加参数（长型参数位）
     */
    public VoiceLine voiceLine(int line, Version version, int a, long b) {
        return switch (line) {
            case 1 -> VoiceLine.coordinate(line, "AttentionToSquare", a, b);
            case 2 -> VoiceLine.tactic(line, a, b);
            case 3 -> VoiceLine.plain(line, "RequestingSupport");
            case 5 -> VoiceLine.plain(line, "Wilco");
            case 6 -> VoiceLine.plain(line, "Negative");
            case 7 -> VoiceLine.plain(line, "WellDone");
            case 8 -> VoiceLine.plain(line, "FairWinds");
            case 9 -> VoiceLine.plain(line, "Curses");
            case 10 -> VoiceLine.plain(line, "DefendTheBase");
            case 11 -> VoiceLine.plain(line, "ProvideAntiAircraft");
            case 12 -> VoiceLine.retreat(line, b);
            case 13 -> VoiceLine.plain(line, "IntelRequired");
            case 14 -> VoiceLine.plain(line, "SetSmokeScreen");
            case 15 -> VoiceLine.plain(line, "UsingRadar");
            case 16 -> VoiceLine.plain(line, "UsingHydroSearch");
            case 17 -> VoiceLine.plain(line, "FollowMe");
            case 18 -> VoiceLine.coordinate(line, "MapPointAttention", a, b);
            case 19 -> VoiceLine.plain(line, "UsingSubmarineLocator");
            default -> {
                log.warn("未知快捷指令 line id: {}（版本 {}，可能为 WG 新增指令，需在 GameConstants 补映射）", line, version);
                yield new VoiceLine(line, "UnknownVoiceLine", null);
            }
        };
    }
}
