package com.shinoaki.wowsreplay.core.webfunction;

import com.shinoaki.wowsreplay.core.JsonMapper;
import com.shinoaki.wowsreplay.ingest.report.BattleReport;
import tools.jackson.databind.JsonNode;

import java.util.function.Function;

public class ReplayWebFunction implements Function<BattleReport, JsonNode> {
    @Override
    public JsonNode apply(BattleReport jsonNode) {
        //统计两边队伍总伤害 总潜在伤害 总点亮伤害
        //计算单人 总伤害 潜在伤害 点亮伤害 承受伤害
        //计算单人数据详细(那种武器造成的占比) 总伤害详细 承受伤害详细 点亮伤害详细
        return null;
    }


    private JsonNode playersPublicInfo(String battleResults) {
        var battleResultsNode = JsonMapper.readTree(battleResults);
        var playersPublicInfoNode = battleResultsNode.path("playersPublicInfo");
        if (playersPublicInfoNode.isMissingNode()) {
            return battleResultsNode;
        }
        return null;
    }
}