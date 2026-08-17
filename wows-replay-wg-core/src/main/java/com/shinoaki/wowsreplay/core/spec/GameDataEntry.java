package com.shinoaki.wowsreplay.core.spec;

import com.shinoaki.wowsreplay.core.JsonConstantsProvider;
import com.shinoaki.wowsreplay.core.data.WowsInfo;
import com.shinoaki.wowsreplay.core.spi.EntitySpecProvider;

import java.nio.charset.StandardCharsets;

/**
 * 某个游戏版本的完整缓存数据：constants.json、实体规范、wowsinfo.json 同时加载。
 *
 * <p>{@link GameDataCache#gameData} 的返回值；同一 major.minor.patch 的所有 build 共享同一份。</p>
 */
public record GameDataEntry(
        JsonConstantsProvider constants,
        EntitySpecProvider entitySpecs,
        WowsInfo wowsInfo) {

    /** 未匹配到游戏数据目录时的空条目（不参与缓存）。 */
    public static final GameDataEntry EMPTY = new GameDataEntry(
            new JsonConstantsProvider("{}".getBytes(StandardCharsets.UTF_8)),
            EntitySpecProvider.empty(),
            WowsInfo.EMPTY);
}
