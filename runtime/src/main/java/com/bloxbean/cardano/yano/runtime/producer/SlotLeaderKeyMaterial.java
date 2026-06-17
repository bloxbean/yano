package com.bloxbean.cardano.yano.runtime.producer;

import com.bloxbean.cardano.client.crypto.BlockProducerKeys;
import com.bloxbean.cardano.yano.api.config.YanoConfig;
import com.bloxbean.cardano.yano.runtime.blockproducer.SlotLeaderBlockProducer;

import java.nio.file.Path;
import java.util.Objects;

/**
 * Loaded block-producer keys plus the derived stake-pool hash used by slot-leader producers.
 */
public record SlotLeaderKeyMaterial(BlockProducerKeys keys, String poolHash) {
    public SlotLeaderKeyMaterial {
        Objects.requireNonNull(keys, "keys");
        Objects.requireNonNull(poolHash, "poolHash");
    }

    public static SlotLeaderKeyMaterial load(YanoConfig config) throws Exception {
        Objects.requireNonNull(config, "config");
        return load(
                Path.of(config.getVrfSkeyFile()),
                Path.of(config.getKesSkeyFile()),
                Path.of(config.getOpCertFile()));
    }

    public static SlotLeaderKeyMaterial load(Path vrfSkeyFile,
                                             Path kesSkeyFile,
                                             Path opCertFile) throws Exception {
        BlockProducerKeys keys = BlockProducerKeys.load(vrfSkeyFile, kesSkeyFile, opCertFile);
        return new SlotLeaderKeyMaterial(keys, SlotLeaderBlockProducer.derivePoolHash(keys.getOpCert()));
    }
}
