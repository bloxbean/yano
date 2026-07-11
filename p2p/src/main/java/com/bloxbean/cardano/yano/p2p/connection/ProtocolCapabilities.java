package com.bloxbean.cardano.yano.p2p.connection;

import com.bloxbean.cardano.yaci.core.protocol.handshake.messages.AcceptVersion;
import com.bloxbean.cardano.yaci.core.protocol.handshake.messages.N2NVersionData;

public record ProtocolCapabilities(
        Long negotiatedVersion,
        boolean chainSync,
        boolean blockFetch,
        boolean txSubmission,
        boolean keepAlive,
        boolean peerSharing,
        boolean query) {

    public static ProtocolCapabilities unknown() {
        return new ProtocolCapabilities(null, false, false, false, false, false, false);
    }

    public static ProtocolCapabilities from(AcceptVersion acceptVersion) {
        if (acceptVersion == null) {
            return unknown();
        }
        boolean peerSharing = false;
        boolean query = false;
        if (acceptVersion.getVersionData() instanceof N2NVersionData versionData) {
            peerSharing = versionData.getPeerSharing() != null && versionData.getPeerSharing() > 0;
            query = Boolean.TRUE.equals(versionData.getQuery());
        }
        return new ProtocolCapabilities(
                acceptVersion.getVersionNumber(),
                true,
                true,
                true,
                true,
                peerSharing,
                query);
    }
}
