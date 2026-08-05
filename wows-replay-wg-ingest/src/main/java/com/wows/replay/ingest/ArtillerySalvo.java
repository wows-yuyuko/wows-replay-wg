package com.wows.replay.ingest;

import com.wows.replay.decode.DecodedPayload;

/** Artillery salvo wrapped with clock for minimap output. */
public record ArtillerySalvo(float clock, DecodedPayload.ArtillerySalvo salvo, int avatarId) {
}
