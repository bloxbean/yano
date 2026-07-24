package com.bloxbean.cardano.yano.appchain.eutxo.zk.zeroj;

import com.bloxbean.cardano.yano.appchain.eutxo.zk.contracts.EutxoKeyPaymentBatch;
import com.bloxbean.cardano.yano.appchain.eutxo.zk.contracts.EutxoZkProfile;
import com.bloxbean.cardano.yano.appchain.eutxo.zk.contracts.EutxoZkSettlementPublicInputs;
import com.bloxbean.cardano.zeroj.circuit.CircuitBuilder;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/**
 * Z3 settlement-bound form of the Z1 bounded payment circuit.
 *
 * <p>The extra public inputs prevent a proof from being replayed under
 * another chain/bridge/profile/VK context or for different published batch
 * and withdrawal commitments.</p>
 */
public final class EutxoKeyPaymentSettlementCircuit {
    private EutxoKeyPaymentSettlementCircuit() {
    }

    public static CircuitBuilder circuit() {
        return EutxoKeyPaymentBatchCircuit.settlementCircuit();
    }

    public static EutxoZkSettlementPublicInputs publicInputs(
            String chainId,
            long bridgeEpoch,
            String verificationKeyDigest,
            byte[] previousRoot,
            EutxoKeyPaymentBatch batch,
            byte[] batchDataCommitment,
            byte[] withdrawalCommitment
    ) {
        if (chainId == null || chainId.isBlank() || bridgeEpoch < 0) {
            throw new IllegalArgumentException("invalid settlement identity");
        }
        requireDigest(batchDataCommitment, "batchDataCommitment");
        requireDigest(withdrawalCommitment, "withdrawalCommitment");
        byte[] keyDigest;
        try {
            keyDigest = HexFormat.of().parseHex(verificationKeyDigest);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(
                    "invalid verification-key digest", exception);
        }
        requireDigest(keyDigest, "verificationKeyDigest");
        var batchInputs = EutxoKeyPaymentBatchCircuit.publicInputs(
                previousRoot, batch);
        return new EutxoZkSettlementPublicInputs(
                batchInputs.previousRoot(),
                batchInputs.nextRoot(),
                batchInputs.transitionDigest(),
                batchInputs.ownerCommitment(),
                batchInputs.batchSize(),
                ZerojScalars.scalar(settlementContext(
                        chainId, bridgeEpoch, keyDigest)),
                ZerojScalars.scalar(batchDataCommitment),
                ZerojScalars.scalar(withdrawalCommitment));
    }

    static java.math.BigInteger[] witness(
            EutxoZkSettlementPublicInputs inputs,
            EutxoKeyPaymentBatch batch
    ) {
        return EutxoKeyPaymentBatchCircuit.witness(inputs, batch);
    }

    public static java.math.BigInteger commitmentScalar(byte[] commitment) {
        requireDigest(commitment, "commitment");
        return ZerojScalars.scalar(commitment);
    }

    public static byte[] settlementContext(
            String chainId,
            long bridgeEpoch,
            byte[] verificationKeyDigest
    ) {
        Objects.requireNonNull(chainId, "chainId");
        requireDigest(verificationKeyDigest, "verificationKeyDigest");
        byte[] chain = chainId.getBytes(StandardCharsets.UTF_8);
        byte[] profile = EutxoZkProfile.Z3_VALIDITY_SETTLEMENT
                .digestHex().getBytes(StandardCharsets.US_ASCII);
        ByteBuffer canonical = ByteBuffer.allocate(
                4 + chain.length + Long.BYTES
                        + profile.length + verificationKeyDigest.length);
        canonical.putInt(chain.length).put(chain).putLong(bridgeEpoch)
                .put(profile).put(verificationKeyDigest);
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest(canonical.array());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    private static void requireDigest(byte[] value, String label) {
        Objects.requireNonNull(value, label);
        if (value.length != 32) {
            throw new IllegalArgumentException(label + " must contain 32 bytes");
        }
    }
}
