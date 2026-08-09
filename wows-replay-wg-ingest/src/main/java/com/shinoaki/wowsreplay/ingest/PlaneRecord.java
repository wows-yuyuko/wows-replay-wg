package com.shinoaki.wowsreplay.ingest;

/** 中队生命周期事件。 */
public record PlaneRecord(float clock, String action, long planeId, PlaneState state) {
}
