package com.wows.replay.ingest;

import com.wows.replay.decode.DecodedPayload;

/** Torpedo record with optional maneuver data. */
public record TorpedoRecord(float clock, DecodedPayload.TorpedoData data,
                            boolean hasManeuver, float targetYaw, float speedCoef) {
    public TorpedoRecord(float clock, DecodedPayload.TorpedoData data) {
        this(clock, data, false, 0f, 0f);
    }

    public TorpedoRecord withManeuver(float yaw, float coef) {
        return new TorpedoRecord(clock, data, true, yaw, coef);
    }
}
