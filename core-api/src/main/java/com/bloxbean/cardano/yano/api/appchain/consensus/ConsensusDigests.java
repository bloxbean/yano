package com.bloxbean.cardano.yano.api.appchain.consensus;

import com.bloxbean.cardano.client.crypto.Blake2bUtil;
import com.bloxbean.cardano.yano.api.appchain.AppBlock;
import com.bloxbean.cardano.yano.api.appchain.codec.AppBlockCodec;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/** Public domain-separated digests used by certified app-chain consensus. */
public final class ConsensusDigests {
    private static final byte[] PREPARE_DOMAIN =
            "yano-appchain-prepare-v2\0".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] COMMIT_DOMAIN =
            "yano-appchain-commit-v2\0".getBytes(StandardCharsets.US_ASCII);

    private ConsensusDigests() {
    }

    public static byte[] prepare(AppBlock block) {
        return vote(PREPARE_DOMAIN, block);
    }

    public static byte[] commit(AppBlock block) {
        return vote(COMMIT_DOMAIN, block);
    }

    private static byte[] vote(byte[] domain, AppBlock block) {
        Objects.requireNonNull(block, "block");
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            bytes.write(domain);
            try (DataOutputStream out = new DataOutputStream(bytes)) {
                out.writeLong(block.height());
                out.writeLong(block.view());
                out.write(block.consensusContextDigest());
                out.write(AppBlockCodec.blockHash(block));
            }
            return Blake2bUtil.blake2bHash256(bytes.toByteArray());
        } catch (IOException impossible) {
            throw new UncheckedIOException(impossible);
        }
    }
}
