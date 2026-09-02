package com.bloxbean.cardano.yano.app.archive;

import com.bloxbean.cardano.client.crypto.Blake2bUtil;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import com.bloxbean.cardano.yano.api.config.YanoConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Set;

/** Resolves the Shelley genesis component of a projection archive identity. */
final class ProjectionGenesisIdentity {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Set<Long> PUBLIC_NETWORK_MAGICS = Set.of(
            764_824_073L, 1_097_911_063L, 1L, 2L, 4L);

    private ProjectionGenesisIdentity() {
    }

    static String resolve(YanoConfig config) {
        if (config.getShelleyGenesisHash() != null && !config.getShelleyGenesisHash().isBlank()) {
            return config.getShelleyGenesisHash().toLowerCase(Locale.ROOT);
        }
        if (config.getShelleyGenesisFile() == null || config.getShelleyGenesisFile().isBlank()) {
            throw new IllegalArgumentException("Shelley genesis hash or file is required for projection identity");
        }

        try {
            byte[] genesis = Files.readAllBytes(Path.of(config.getShelleyGenesisFile()));
            if (isLocalNetwork(genesis)) {
                genesis = withoutMutableSystemStart(genesis);
            }
            return HexUtil.encodeHexString(Blake2bUtil.blake2bHash256(genesis));
        } catch (Exception e) {
            throw new IllegalStateException("cannot compute Shelley genesis hash for projection identity", e);
        }
    }

    private static boolean isLocalNetwork(byte[] genesis) throws Exception {
        long networkMagic = MAPPER.readTree(genesis).path("networkMagic").asLong(Long.MIN_VALUE);
        if (networkMagic == Long.MIN_VALUE) {
            throw new IllegalArgumentException("Shelley genesis networkMagic is required");
        }
        return !PUBLIC_NETWORK_MAGICS.contains(networkMagic);
    }

    /**
     * Local producers replace {@code systemStart} on their first boot. It identifies the launch,
     * not the devnet definition, so exclude only that field from their durable archive identity.
     */
    private static byte[] withoutMutableSystemStart(byte[] genesis) throws Exception {
        ObjectNode root = (ObjectNode) MAPPER.readTree(genesis);
        root.remove("systemStart");
        return MAPPER.writeValueAsBytes(root);
    }
}
