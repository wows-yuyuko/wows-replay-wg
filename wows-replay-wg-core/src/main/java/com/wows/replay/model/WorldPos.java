package com.wows.replay.model;

/**
 * 2D world position (x, z) used primarily for minimap and plane coordinates.
 */
public record WorldPos(float x, float z) {

    public static WorldPos fromVec3(Vec3 v) {
        return new WorldPos(v.x(), v.z());
    }
}
