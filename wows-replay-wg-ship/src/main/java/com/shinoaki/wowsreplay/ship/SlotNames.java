package com.shinoaki.wowsreplay.ship;

import java.util.List;
import java.util.Map;

/**
 * 模块槽位与组件类型的规范名/显示名映射（对齐 libwowsinfo {@code module_slots}）。
 */
public final class SlotNames {

    private SlotNames() {}

    /** 已知模块槽位的展示顺序；未知槽位按出现顺序追加到末尾。 */
    public static final List<String> SLOT_ORDER = List.of(
            "_Hull", "_Artillery", "_PrimaryWeapons", "_SecondaryWeapons",
            "_Torpedoes", "_Sonar", "_Suo", "_FlightControl", "_Engine",
            "_Fighter", "_TorpedoBomber", "_DiveBomber", "_SkipBomber", "_Abilities");

    private static final Map<String, String> SLOT_LABEL = Map.ofEntries(
            Map.entry("_Hull", "hull"),
            Map.entry("_Artillery", "artillery"),
            Map.entry("_PrimaryWeapons", "primary_weapons"),
            Map.entry("_SecondaryWeapons", "secondary_weapons"),
            Map.entry("_Torpedoes", "torpedoes"),
            Map.entry("_Sonar", "sonar"),
            Map.entry("_Suo", "fire_control"),
            Map.entry("_FlightControl", "flight_control"),
            Map.entry("_Engine", "engine"),
            Map.entry("_Fighter", "fighter"),
            Map.entry("_TorpedoBomber", "torpedo_bomber"),
            Map.entry("_DiveBomber", "dive_bomber"),
            Map.entry("_SkipBomber", "skip_bomber"),
            Map.entry("_Abilities", "abilities"));

    private static final Map<String, String> SLOT_DISPLAY = Map.ofEntries(
            Map.entry("_Hull", "Hull"),
            Map.entry("_Artillery", "Main Battery"),
            Map.entry("_PrimaryWeapons", "Primary Weapons"),
            Map.entry("_SecondaryWeapons", "Secondary Weapons"),
            Map.entry("_Torpedoes", "Torpedoes"),
            Map.entry("_Sonar", "Sonar"),
            Map.entry("_Suo", "Fire Control"),
            Map.entry("_FlightControl", "Flight Control"),
            Map.entry("_Engine", "Engine"),
            Map.entry("_Fighter", "Fighter"),
            Map.entry("_TorpedoBomber", "Torpedo Bombers"),
            Map.entry("_DiveBomber", "Dive Bombers"),
            Map.entry("_SkipBomber", "Skip Bombers"),
            Map.entry("_Abilities", "Abilities"));

    private static final Map<String, String> COMPONENT_TYPE_DISPLAY = Map.ofEntries(
            Map.entry("abilities", "Abilities"),
            Map.entry("airArmament", "Air Armament"),
            Map.entry("airDefense", "Air Defense"),
            Map.entry("airSupport", "Air Support"),
            Map.entry("artillery", "Artillery"),
            Map.entry("atba", "ATBA"),
            Map.entry("axisLaser", "Axis Laser"),
            Map.entry("chargeLasers", "Charge Lasers"),
            Map.entry("depthCharges", "Depth Charges"),
            Map.entry("directors", "Fire Control Directors"),
            Map.entry("diveBomber", "Dive Bomber"),
            Map.entry("engine", "Engine"),
            Map.entry("fighter", "Fighter"),
            Map.entry("finders", "Finders"),
            Map.entry("fireControl", "Fire Control"),
            Map.entry("flightControl", "Flight Control"),
            Map.entry("hull", "Hull"),
            Map.entry("innateSkills", "Innate Skills"),
            Map.entry("missiles", "Missiles"),
            Map.entry("phaserLasers", "Phaser Lasers"),
            Map.entry("pinger", "Pinger"),
            Map.entry("radars", "Radars"),
            Map.entry("skipBomber", "Skip Bomber"),
            Map.entry("specials", "Specials"),
            Map.entry("torpedoBomber", "Torpedo Bomber"),
            Map.entry("torpedoes", "Torpedoes"),
            Map.entry("visualCustomizations", "Visual Customizations"),
            Map.entry("waves", "Waves"),
            Map.entry("wcs", "WCS"));

    /** 模块槽位键 -> 规范化标识。 */
    public static String label(String key) {
        return SLOT_LABEL.getOrDefault(key, key);
    }

    /** 模块槽位键 -> 显示名。 */
    public static String display(String key) {
        return SLOT_DISPLAY.getOrDefault(key, key);
    }

    /** 组件类型键 -> 显示名。 */
    public static String componentTypeDisplay(String type) {
        return COMPONENT_TYPE_DISPLAY.getOrDefault(type, type);
    }
}
