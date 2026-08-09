package com.shinoaki.wowsreplay.ingest;

import com.shinoaki.wowsreplay.core.decode.DecodedPayload;

/** Artillery salvo wrapped with clock for minimap output. */
public record ArtillerySalvo(float clock, DecodedPayload.ArtillerySalvo salvo, int avatarId) {
}
