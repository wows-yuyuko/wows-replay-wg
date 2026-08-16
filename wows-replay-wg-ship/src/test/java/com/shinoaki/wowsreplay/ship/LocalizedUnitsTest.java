package com.shinoaki.wowsreplay.ship;

import com.shinoaki.wowsreplay.core.data.LangProvider;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class LocalizedUnitsTest {

    private static final String LANG = """
            {
              "en": {"IDS_KNOT": "knots", "IDS_SECOND": "s", "IDS_KILOMETER": "km",
                      "IDS_METER": "m", "IDS_METER_SECOND": "m/s", "IDS_MILLIMETER": "mm", "IDS_KILOGRAMM": "kg"},
              "zh_sg": {"IDS_KNOT": "节", "IDS_SECOND": "秒", "IDS_KILOMETER": "公里",
                         "IDS_METER": "米", "IDS_METER_SECOND": "米/秒", "IDS_MILLIMETER": "毫米", "IDS_KILOGRAMM": "千克"}
            }
            """;

    @Test
    void latinUnitsCarryLeadingSpace() {
        var units = LocalizedUnits.fromLang(LangProvider.fromJson(LANG), LangProvider.Lang.EN);
        assertEquals(" knots", units.knots());
        assertEquals(" s", units.seconds());
        assertEquals(" km", units.kilometer());
        assertEquals(" m/s", units.meterPerSecond());
    }

    @Test
    void cjkUnitsAreBare() {
        var units = LocalizedUnits.fromLang(LangProvider.fromJson(LANG), LangProvider.Lang.ZH_SG);
        assertEquals("节", units.knots());
        assertEquals("秒", units.seconds());
        assertEquals("公里", units.kilometer());
        assertEquals("米/秒", units.meterPerSecond());
    }

    @Test
    void missingKeysFallBackToEnglish() {
        var units = LocalizedUnits.fromLang(LangProvider.EMPTY);
        assertEquals(" knots", units.knots());
        assertEquals(" m/s", units.meterPerSecond());
    }
}
