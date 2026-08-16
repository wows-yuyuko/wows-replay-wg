package com.shinoaki.wowsreplay.ship;

import com.shinoaki.wowsreplay.core.data.LangProvider;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ShipConfigServiceTest {

    private static final String WOWSINFO = """
            {
              "version": "15.7.0.0.13015811",
              "ships": {
                "3246831056": {
                  "name": "IDS_PSDD001", "description": "IDS_PSDD001_DESCR", "year": "IDS_PSDD001_YEAR",
                  "paperShip": false, "id": 3246831056, "index": "PSDD001", "tier": 5,
                  "region": "USA", "type": "Destroyer", "regionID": "IDS_USA", "typeID": "IDS_DESTROYER",
                  "group": "special", "costXP": 13500, "costGold": 0, "costCR": 1650000,
                  "consumables": [[{"name": "PCY001_CrashCrew", "type": "Default"}]],
                  "nextShips": [3246831057],
                  "permoflages": ["4293521392"],
                  "modules": {
                    "_Hull": [
                      {"cost": {"costCR": 0, "costXP": 0}, "index": 0, "components": {"hull": ["A_Hull"]}, "name": "IDS_HULL_STOCK"},
                      {"cost": {"costCR": 12000, "costXP": 5000}, "index": 1, "components": {"hull": ["B_Hull"]}, "name": "IDS_HULL_TOP"}
                    ],
                    "_Artillery": [
                      {"cost": {"costCR": 0, "costXP": 0}, "index": 0, "components": {"artillery": ["A_Artillery"]}, "name": "IDS_ART_STOCK"}
                    ]
                  },
                  "components": {
                    "A_Hull": {"health": 6400},
                    "B_Hull": {"health": 7000},
                    "A_Artillery": {"reload": 5.0}
                  }
                },
                "3246831057": {
                  "name": "IDS_PSDD002", "id": 3246831057, "index": "PSDD002", "tier": 6,
                  "region": "USA", "type": "Destroyer", "group": "special", "modules": {}, "components": {}
                }
              },
              "abilities": {
                "PCY001_CrashCrew": {
                  "nation": "Common", "name": "IDS_CRASHCREW", "description": "IDS_CRASHCREW_DESC",
                  "id": 4293042096, "icon": "PCY001_CrashCrew", "filter": "CRASHCREW",
                  "type": "IDS_CRASHCREW_TYPE",
                  "abilities": {"Destroyer": {"reloadTime": 80, "workTime": 10, "numConsumables": -1}}
                }
              },
              "exteriors": {
                "4293521392": {"type": "MSkin", "id": 4293521392, "name": "IDS_CAMO1", "icon": "PAEM001"}
              },
              "modernizations": {
                "4290957232": {"slot": 0, "id": 4290957232, "name": "IDS_MOD1", "icon": "PCM003"}
              },
              "skills": {
                "5": {"skillType": 5, "name": "IDS_SKILL5", "description": "IDS_SKILL5_DESC", "icon": "skill5"}
              },
              "alias": {"3246831056": {"alias": "测试船"}}
            }
            """;

    private static final String LANG = """
            {
              "en": {
                "IDS_PSDD001": "Test Destroyer", "IDS_USA": "U.S.A.", "IDS_DESTROYER": "Destroyer",
                "IDS_CRASHCREW": "Damage Control", "IDS_CRASHCREW_DESC": "Repairs damage",
                "IDS_CRASHCREW_TYPE": "Damage Control Party", "IDS_CAMO1": "Camo 1",
                "IDS_MOD1": "Mod 1", "IDS_SKILL5": "Skill 5",
                "IDS_HULL_STOCK": "Hull A", "IDS_HULL_TOP": "Hull B", "IDS_ART_STOCK": "Gun A",
                "IDS_PSDD002": "Next Ship", "IDS_KNOT": "knots", "IDS_SECOND": "s"
              },
              "zh_sg": {
                "IDS_PSDD001": "测试驱逐舰", "IDS_USA": "美国", "IDS_DESTROYER": "驱逐舰",
                "IDS_CRASHCREW": "损害管制", "IDS_HULL_STOCK": "船体A",
                "IDS_KNOT": "节", "IDS_SECOND": "秒"
              }
            }
            """;

    private static ShipConfigService service() {
        return ShipConfigService.fromJson(WOWSINFO, LANG);
    }

    @Test
    void parsesShipModulesAndComponents() {
        ShipData data = service().data();
        assertEquals("15.7.0.0.13015811", data.version());

        var ship = data.ship(3246831056L);
        assertNotNull(ship);
        assertEquals("PSDD001", ship.index());
        assertEquals(5, ship.tier());
        assertTrue(ship.premium());
        assertEquals("测试船", data.aliases().get(3246831056L));

        assertEquals(2, ship.modules().size());
        var hull = ship.moduleSlot("hull");
        assertNotNull(hull);
        assertEquals("_Hull", hull.key());
        assertEquals(2, hull.options().size());
        assertEquals(1, hull.options().get(1).index());

        assertEquals(6400, ship.component("A_Hull").path("health").asInt());
        assertNotNull(data.abilityByName("PCY001_CrashCrew"));
    }

    @Test
    void localizesShipView() {
        var view = service().view(3246831056L, LangProvider.Lang.EN);
        assertNotNull(view);
        assertEquals("Test Destroyer", view.name());
        assertEquals("U.S.A.", view.nationName());
        assertEquals("Destroyer", view.shipTypeName());

        // 模块槽：hull 有 2 个可选模块，名字已本地化
        var hull = view.modules().get(0);
        assertEquals("hull", hull.slot());
        assertEquals("Hull", hull.label());
        assertEquals(2, hull.options().size());
        assertEquals("Hull B", hull.options().get(1).name());
        assertEquals(12000, hull.options().get(1).costCr());

        // 组件分组：hull 选项引用 A_Hull / B_Hull，定义来自组件库
        var groups = hull.options().get(0).components();
        assertEquals("hull", groups.get(0).type());
        assertEquals(6400, groups.get(0).components().get(0).definition().path("health").asInt());

        // 消耗品本地化 + 参数
        assertEquals(1, view.consumables().size());
        var c = view.consumables().get(0);
        assertEquals("Damage Control", c.name());
        assertEquals(80.0, c.reloadS());

        // 下一级 + 涂装
        assertEquals(1, view.nextShips().size());
        assertEquals("Next Ship", view.nextShips().get(0).name());
        assertEquals("Camo 1", view.camos().get(0).name());
    }

    @Test
    void localizesWithChineseAndByIndex() {
        var view = service().view(3246831056L, LangProvider.Lang.ZH_SG);
        assertEquals("测试驱逐舰", view.name());
        assertEquals("美国", view.nationName());
        assertEquals("驱逐舰", view.shipTypeName());

        var byIndex = service().viewByIndex("PSDD001", LangProvider.Lang.ZH_SG);
        assertTrue(byIndex.isPresent());
        assertEquals("测试驱逐舰", byIndex.get().name());
    }

    @Test
    void resolvesShipComponents() {
        var resolved = service().resolveShip(3246831056L, Map.of("hull", "A_Hull", "artillery", "A_Missing"));
        assertEquals(3246831056L, resolved.shipId());
        assertEquals("A_Hull", resolved.slots().get("hull").component());
        assertNotNull(resolved.slots().get("hull").definition());
        // 组件库找不到定义 -> definition 为 null
        assertEquals("A_Missing", resolved.slots().get("artillery").component());
        assertNull(resolved.slots().get("artillery").definition());
    }

    @Test
    void fromFilesIsTheEntryPoint(@TempDir Path dir) throws Exception {
        // 调用入口：给定 wowsinfo.json + lang.json 两个文件即可构建服务并查询
        Path wowsinfo = dir.resolve("wowsinfo.json");
        Path lang = dir.resolve("lang.json");
        Files.writeString(wowsinfo, WOWSINFO);
        Files.writeString(lang, LANG);

        ShipConfigService service = ShipConfigService.fromFiles(wowsinfo, lang);
        var view = service.view(3246831056L, LangProvider.Lang.ZH_SG);
        assertNotNull(view);
        assertEquals("测试驱逐舰", view.name());
        assertEquals(2, view.modules().size());
    }

    @Test
    void loadsFromWowsinfoDirectory() throws Exception {
        // 真实数据：从 temp/wowsinfo 目录加载 wowsinfo.json + lang.json
        Path dir = locateWowsinfoDir();
        Assumptions.assumeTrue(dir != null, "未找到 temp/wowsinfo 目录，跳过真实数据测试");

        ShipConfigService service = ShipConfigService.fromDirectory(dir);
        assertTrue(service.data().ships().size() > 1000);
        assertFalse(service.data().version().isBlank());

        var view = service.viewByIndex("PASA002", LangProvider.Lang.ZH_SG);
        assertTrue(view.isPresent());
        assertEquals("PASA002", view.get().index());
        assertFalse(view.get().name().isBlank());
        assertFalse(view.get().modules().isEmpty());
    }

    /** 从当前工作目录向上查找 temp/wowsinfo。 */
    private static Path locateWowsinfoDir() {
        Path dir = Path.of("").toAbsolutePath();
        while (dir != null) {
            Path candidate = dir.resolve("temp").resolve("wowsinfo");
            if (Files.isDirectory(candidate)) return candidate;
            dir = dir.getParent();
        }
        return null;
    }
}
