package com.shinoaki.wowsreplay.ship;

import com.shinoaki.wowsreplay.core.data.LangProvider;
import com.shinoaki.wowsreplay.core.data.ShipConfig;
import com.shinoaki.wowsreplay.core.data.WowsInfo;
import com.shinoaki.wowsreplay.ship.model.AbilityInfo;
import com.shinoaki.wowsreplay.ship.model.ModuleOption;
import com.shinoaki.wowsreplay.ship.model.ModuleSlot;
import com.shinoaki.wowsreplay.ship.model.ShipInfo;
import com.shinoaki.wowsreplay.ship.view.ShipConfigView;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 战舰配置解析器（独立模块）。
 *
 * <p>从 wowsinfo.json 加载完整战舰配置，结合 lang.json 本地化，提供两种查询：</p>
 * <ul>
 *   <li>{@link #view} / {@link #viewByIndex} —— 查看战舰详细配置（本地化模块树 + 组件 + 消耗品）；</li>
 *   <li>{@link #resolveShip} / {@link #resolveShipConfig} —— 解析实际组件 / 回放 shipConfig blob。</li>
 * </ul>
 */
public final class ShipConfigService {

    private final ShipData data;
    private final LangProvider lang;

    /** 从两个文件构建（wowsinfo.json + lang.json）。 */
    public static ShipConfigService fromFiles(Path wowsinfoJson, Path langJson) throws IOException {
        return new ShipConfigService(
                ShipData.fromJson(Files.readString(wowsinfoJson)),
                LangProvider.fromJson(Files.readString(langJson)));
    }

    /** 从目录构建：读取 {@code <dir>/wowsinfo.json} 与 {@code <dir>/lang.json}。 */
    public static ShipConfigService fromDirectory(Path wowsinfoDir) throws IOException {
        return fromFiles(wowsinfoDir.resolve("wowsinfo.json"), wowsinfoDir.resolve("lang.json"));
    }

    /** 从文本构建。 */
    public static ShipConfigService fromJson(String wowsinfoJson, String langJson) {
        return new ShipConfigService(
                ShipData.fromJson(wowsinfoJson),
                LangProvider.fromJson(langJson));
    }

    public ShipConfigService(ShipData data, LangProvider lang) {
        this.data = data;
        this.lang = lang;
    }

    public ShipData data() {
        return data;
    }

    public LangProvider lang() {
        return lang;
    }

    // ── 查看详细配置 ─────────────────────────────────────────────

    /** 按 ship id 查看本地化配置（默认语言）；未找到返回 null。 */
    public ShipConfigView view(long shipId) {
        return view(shipId, LangProvider.DEFAULT_LANG);
    }

    /** 按 ship id 查看指定语言的配置。 */
    public ShipConfigView view(long shipId, LangProvider.Lang langCode) {
        ShipInfo ship = data.ship(shipId);
        return ship == null ? null : buildView(ship, langCode);
    }

    /** 按舰船索引（如 "PASD001"）查看。 */
    public Optional<ShipConfigView> viewByIndex(String index) {
        return viewByIndex(index, LangProvider.DEFAULT_LANG);
    }

    /** 按舰船索引查看指定语言。 */
    public Optional<ShipConfigView> viewByIndex(String index, LangProvider.Lang langCode) {
        return data.shipByIndex(index).map(s -> buildView(s, langCode));
    }

    private ShipConfigView buildView(ShipInfo ship, LangProvider.Lang langCode) {
        return new ShipConfigView(
                ship.id(),
                ship.index(),
                lang.get(langCode, ship.name()),
                lang.get(langCode, ship.description()),
                lang.get(langCode, ship.year()),
                ship.tier(),
                ship.region(),
                lang.get(langCode, ship.regionId()),
                ship.type(),
                lang.get(langCode, ship.typeId()),
                ship.group(),
                ship.premium(),
                ship.special(),
                ship.costCr(),
                ship.costGold(),
                ship.costXp(),
                emptyToNull(data.aliases().get(ship.id())),
                buildModules(ship, langCode),
                buildConsumables(ship, langCode),
                buildNextShips(ship, langCode),
                buildCamos(ship, langCode));
    }

    private List<ShipConfigView.ModuleSlotView> buildModules(ShipInfo ship, LangProvider.Lang langCode) {
        var out = new ArrayList<ShipConfigView.ModuleSlotView>();
        for (ModuleSlot slot : ship.modules()) {
            var options = new ArrayList<ShipConfigView.ModuleOptionView>();
            for (ModuleOption option : slot.options()) {
                options.add(new ShipConfigView.ModuleOptionView(
                        option.index(),
                        lang.get(langCode, option.name()),
                        option.costXp(),
                        option.costCr(),
                        buildComponentGroups(ship, option)));
            }
            out.add(new ShipConfigView.ModuleSlotView(
                    slot.label(), SlotNames.display(slot.key()), options));
        }
        return out;
    }

    private List<ShipConfigView.ComponentGroupView> buildComponentGroups(ShipInfo ship, ModuleOption option) {
        var out = new ArrayList<ShipConfigView.ComponentGroupView>();
        for (var e : option.components().entrySet()) {
            var comps = new ArrayList<ShipConfigView.ComponentView>();
            for (String name : e.getValue()) {
                comps.add(new ShipConfigView.ComponentView(name, ship.component(name)));
            }
            out.add(new ShipConfigView.ComponentGroupView(
                    e.getKey(), SlotNames.componentTypeDisplay(e.getKey()), comps));
        }
        return out;
    }

    private List<ShipConfigView.ConsumableView> buildConsumables(ShipInfo ship, LangProvider.Lang langCode) {
        var out = new ArrayList<ShipConfigView.ConsumableView>();
        for (List<ShipInfo.ConsumableSlot> slot : ship.consumables()) {
            for (ShipInfo.ConsumableSlot consumable : slot) {
                AbilityInfo ability = data.abilityByName(consumable.name());
                if (ability == null) {
                    // 无定义时仍输出原始键，保证槽位不丢失
                    out.add(new ShipConfigView.ConsumableView(
                            consumable.name(), consumable.name(), "", consumable.type(), "", "", 0, 0, 0, -1));
                    continue;
                }
                JsonNode params = ability.paramsFor(ship.type());
                out.add(new ShipConfigView.ConsumableView(
                        ability.key(),
                        lang.get(langCode, ability.name()),
                        lang.get(langCode, ability.description()),
                        consumable.type(),
                        lang.get(langCode, ability.type()),
                        ability.icon(),
                        params != null ? params.path("reloadTime").asDouble(0.0) : 0.0,
                        params != null ? params.path("workTime").asDouble(0.0) : 0.0,
                        params != null ? params.path("workPreparationTime").asDouble(0.0) : 0.0,
                        params != null ? params.path("numConsumables").asLong(-1L) : -1L));
            }
        }
        return out;
    }

    private List<ShipConfigView.NextShipView> buildNextShips(ShipInfo ship, LangProvider.Lang langCode) {
        var out = new ArrayList<ShipConfigView.NextShipView>();
        for (long id : ship.nextShips()) {
            ShipInfo next = data.ship(id);
            out.add(next == null
                    ? new ShipConfigView.NextShipView(id, null, null, 0)
                    : new ShipConfigView.NextShipView(id, next.index(), lang.get(langCode, next.name()), next.tier()));
        }
        return out;
    }

    private List<ShipConfigView.CamoView> buildCamos(ShipInfo ship, LangProvider.Lang langCode) {
        var out = new ArrayList<ShipConfigView.CamoView>();
        for (String key : ship.permoflages()) {
            WowsInfo.ExteriorInfo ext = data.wowsInfo().exterior(keyToLong(key));
            out.add(new ShipConfigView.CamoView(key, ext == null ? key : lang.get(langCode, ext.name())));
        }
        return out;
    }

    // ── 解析回放 shipConfig ──────────────────────────────────────

    /**
     * 解析实际基础配置：把回放的 shipComponents（slot 名 -> 组件名）映射到该舰 components 库里的定义。
     */
    public ResolvedShip resolveShip(long shipId, Map<String, String> shipComponents) {
        var slots = new LinkedHashMap<String, ResolvedShip.ResolvedSlot>();
        ShipInfo ship = data.ship(shipId);
        if (ship != null) {
            for (var e : shipComponents.entrySet()) {
                String name = e.getValue();
                slots.put(e.getKey(), new ResolvedShip.ResolvedSlot(name, ship.component(name)));
            }
        }
        return new ResolvedShip(shipId, slots);
    }

    /**
     * 解析回放 shipConfig blob（{@link ShipConfig}）为本地化配置。
     */
    public ResolvedShipConfig resolveShipConfig(ShipConfig config) {
        return resolveShipConfig(config, LangProvider.DEFAULT_LANG);
    }

    public ResolvedShipConfig resolveShipConfig(ShipConfig config, LangProvider.Lang langCode) {
        if (config == null) return null;
        ShipInfo ship = data.ship(config.shipParamsId());
        return new ResolvedShipConfig(
                config.shipParamsId(),
                ship == null ? null : ship.index(),
                ship == null ? null : lang.get(langCode, ship.name()),
                mapModernizations(config.modernization(), langCode),
                mapConsumables(config.consumables(), langCode),
                mapExteriors(config.exteriors(), langCode),
                mapUnits(config.units()),
                mapCommanderSkills(config, langCode),
                config.ensigns(),
                config.ecoboosts());
    }

    private List<ResolvedShipConfig.NamedItem> mapModernizations(List<Long> ids, LangProvider.Lang langCode) {
        var out = new ArrayList<ResolvedShipConfig.NamedItem>();
        for (Long id : ids) {
            WowsInfo.Modernizations m = data.wowsInfo().modernization(id);
            out.add(new ResolvedShipConfig.NamedItem(
                    String.valueOf(id),
                    m == null ? null : lang.get(langCode, m.name()),
                    m == null ? null : m.icon()));
        }
        return out;
    }

    private List<ResolvedShipConfig.NamedItem> mapConsumables(List<Long> ids, LangProvider.Lang langCode) {
        var out = new ArrayList<ResolvedShipConfig.NamedItem>();
        for (Long id : ids) {
            WowsInfo.Abilities a = data.wowsInfo().consumable(id);
            out.add(new ResolvedShipConfig.NamedItem(
                    String.valueOf(id),
                    a == null ? null : lang.get(langCode, a.name()),
                    a == null ? null : a.icon()));
        }
        return out;
    }

    private List<ResolvedShipConfig.NamedItem> mapExteriors(List<Long> ids, LangProvider.Lang langCode) {
        var out = new ArrayList<ResolvedShipConfig.NamedItem>();
        for (Long id : ids) {
            WowsInfo.ExteriorInfo x = data.wowsInfo().exterior(id);
            out.add(new ResolvedShipConfig.NamedItem(
                    String.valueOf(id),
                    x == null ? null : lang.get(langCode, x.name()),
                    x == null ? null : x.icon()));
        }
        return out;
    }

    /** units 为 GameParams id，wowsinfo 组件库按名称索引、无 id 映射，这里仅保留原始 id。 */
    private List<ResolvedShipConfig.NamedItem> mapUnits(List<Long> ids) {
        var out = new ArrayList<ResolvedShipConfig.NamedItem>();
        for (Long id : ids) {
            out.add(new ResolvedShipConfig.NamedItem(String.valueOf(id), null, null));
        }
        return out;
    }

    private List<ResolvedShipConfig.NamedItem> mapCommanderSkills(ShipConfig config, LangProvider.Lang langCode) {
        if (config.commanderSkills() == null) return List.of();
        String type = data.wowsInfo().shipType(config.shipParamsId());
        List<Integer> ids = switch (type == null ? "" : type) {
            case "AirCarrier" -> config.commanderSkills().aircraftCarrier();
            case "Battleship" -> config.commanderSkills().battleship();
            case "Cruiser" -> config.commanderSkills().cruiser();
            case "Destroyer" -> config.commanderSkills().destroyer();
            case "Auxiliary" -> config.commanderSkills().auxiliary();
            case "Submarine" -> config.commanderSkills().submarine();
            default -> List.of();
        };
        var out = new ArrayList<ResolvedShipConfig.NamedItem>();
        for (Integer skillType : ids) {
            WowsInfo.Skills s = data.wowsInfo().skill(skillType);
            out.add(new ResolvedShipConfig.NamedItem(
                    String.valueOf(skillType),
                    s == null ? null : lang.get(langCode, s.name()),
                    s == null ? null : s.icon()));
        }
        return out;
    }

    // ── 工具 ─────────────────────────────────────────────────────

    private static String emptyToNull(String s) {
        return s == null || s.isEmpty() ? null : s;
    }

    private static long keyToLong(String key) {
        try {
            return Long.parseLong(key);
        } catch (NumberFormatException e) {
            return 0L;
        }
    }
}
