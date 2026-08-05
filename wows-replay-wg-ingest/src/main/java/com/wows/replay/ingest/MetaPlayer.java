package com.wows.replay.ingest;

/**
 * meta 花名册条目：从 {@code ReplayMeta.vehicles[]} 预抽取，arena 名册到达后
 * 回填 {@code accountId}（accountDBID）与 {@code entityId}。
 */
public class MetaPlayer {
    public final long metaId;   // 战斗内 meta id（= meta.vehicles[].id，同 arena 玩家 id 字段）
    public final String name;
    public final int relation;  // 0=自己, 1=同队, 2=敌方
    public final long shipId;   // shipParamsId (GameParamId)
    /** 账号 ID（accountDBID），arena 名册匹配后填充。 */
    public long accountId;
    /** 战斗内实体 id（Avatar/Vehicle entity_id），arena 名册匹配后填充。 */
    public int entityId;

    public MetaPlayer(long metaId, String name, int relation, long shipId) {
        this.metaId = metaId;
        this.name = name;
        this.relation = relation;
        this.shipId = shipId;
    }
}
