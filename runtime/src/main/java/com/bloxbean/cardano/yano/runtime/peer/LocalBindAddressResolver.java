package com.bloxbean.cardano.yano.runtime.peer;

import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.Optional;

/**
 * Resolves the concrete local interface address used to reach a remote peer.
 */
public final class LocalBindAddressResolver {
    private LocalBindAddressResolver() {
    }

    public static Optional<String> resolveForRemote(String remoteHost, int remotePort) {
        if (remoteHost == null || remoteHost.isBlank() || remotePort <= 0 || remotePort > 65_535) {
            return Optional.empty();
        }

        try {
            InetAddress remote = InetAddress.getByName(remoteHost.trim());
            try (DatagramSocket socket = new DatagramSocket()) {
                socket.connect(remote, remotePort);
                InetAddress local = socket.getLocalAddress();
                if (local == null || local.isAnyLocalAddress()) {
                    return Optional.empty();
                }
                return Optional.of(local.getHostAddress());
            }
        } catch (Exception e) {
            return Optional.empty();
        }
    }
}
