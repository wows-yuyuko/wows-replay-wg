package com.wows.replay.ingest;

/** 控制点实时状态（占用/入侵者/进度等）。 */
public class CapturePointState {
    /** 对应 InteractiveZone 实体 id（componentsState 更新定位用），-1 表示未知。 */
    public int entityId = -1;
    public int index;
    public long teamId = -1;
    public long invaderTeam = -1;
    public float progress;
    /** 占领速度倍率（componentsState.captureLogic.captureSpeed）。 */
    public float captureSpeed;
    public boolean hasInvaders;
    public boolean bothInside;
    public boolean isEnabled = true;
    public float[] position;
    public float radius;
}
