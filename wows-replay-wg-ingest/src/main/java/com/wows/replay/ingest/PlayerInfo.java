package com.wows.replay.ingest;

/** 战斗内玩家信息（players 表，keyed by 战斗内 meta id）。 */
public class PlayerInfo {
    public String username;
    public int entityId;
    public int teamId = -1;
    public int relation;
    /** 账号 ID（accountDBID），arena 名册到达后填充；0 表示未知。 */
    public long accountId;

    public PlayerInfo(String u, int e, int r) {
        username = u;
        entityId = e;
        relation = r;
    }
}
