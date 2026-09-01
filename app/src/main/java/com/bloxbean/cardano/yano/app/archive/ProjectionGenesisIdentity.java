package com.bloxbean.cardano.yano.app.archive;

import com.bloxbean.cardano.client.crypto.Blake2bUtil;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import com.bloxbean.cardano.yano.api.config.YanoConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/** Resolves the Shelley genesis component of a projection archive identity. */
final class ProjectionGenesisIdentity {
    private static final ObjectMapper MAPPER = new ObjectMapper();

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
            if (config.isDevMode() && config.isEnableBlockProducer()) {
                genesis = withoutMutableSystemStart(genesis);
            }
            return HexUtil.encodeHexString(Blake2bUtil.blake2bHash256(genesis));
        } catch (Exception e) {
            throw new IllegalStateException("cannot compute Shelley genesis hash for projection identity", e);
        }
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
