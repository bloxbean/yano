package com.bloxbean.cardano.yano.api.appchain.consensus;

import com.bloxbean.cardano.client.crypto.Blake2bUtil;
import com.bloxbean.cardano.yano.api.appchain.AppBlock;
import com.bloxbean.cardano.yano.api.appchain.AppBlockHeader;
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

    public static byte[] commit(AppBlockHeader header) {
        Objects.requireNonNull(header, "header");
        return vote(COMMIT_DOMAIN, header.height(), header.view(), header.consensusContextDigest(),
                header.blockHash(), header.valueHash());
    }

    private static byte[] vote(byte[] domain, AppBlock block) {
        Objects.requireNonNull(block, "block");
        return vote(domain, block.height(), block.view(), block.consensusContextDigest(),
                AppBlockCodec.blockHash(block), AppBlockCodec.valueHash(block));
    }

    private static byte[] vote(byte[] domain, long height, long view, byte[] context,
                               byte[] blockHash, byte[] valueHash) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            bytes.write(domain);
            try (DataOutputStream out = new DataOutputStream(bytes)) {
                out.writeLong(height);
                out.writeLong(view);
                out.write(context);
                out.write(blockHash);
                out.write(valueHash);
            }
            return Blake2bUtil.blake2bHash256(bytes.toByteArray());
        } catch (IOException impossible) {
            throw new UncheckedIOException(impossible);
        }
    }
}
