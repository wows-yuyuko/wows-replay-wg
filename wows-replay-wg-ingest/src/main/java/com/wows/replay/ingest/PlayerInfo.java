package com.wows.replay.ingest;

/** 战斗内玩家信息（players 表，keyed by 战斗内 meta id）。 */
public class PlayerInfo {
    public String username;
    public int entityId;
    public int teamId = -1;
    public int relation;

    public PlayerInfo(String u, int e, int r) {
        username = u;
        entityId = e;
        relation = r;
    }
}
