package org.yanoproject.ledger.rules;

import com.bloxbean.cardano.client.util.HexUtil;

import org.yanoproject.ledger.rules.TxValidationRequest.Origin;

import java.util.Arrays;
import java.util.Objects;

/**
 * Provenance of a successful full validation; the mempool keeps one per transaction and passes it
 * back as {@link TxValidationRequest#previous()} (ADR-056 §2, §6). The validator alone decides
 * whether it allows re-application.
 *
 * <p>All arrays are copied on the way in and out.</p>
 *
 * @param txCbor                   the validated transaction bytes
 * @param txId                     the transaction id (blake2b-256 of the original body bytes)
 * @param validatedProtocolMajor   the protocol major version it was fully validated under
 * @param validatedEpoch           the epoch it was fully validated in
 * @param validatedPhase2EnvDigest the {@link ValidationEnv#phase2EnvDigest()} it was validated with
 * @param phase2Valid              the phase-2 verdict
 * @param origin                   where the verdict came from
 */
public record ValidatedTx(byte[] txCbor, byte[] txId, int validatedProtocolMajor, long validatedEpoch,
                          byte[] validatedPhase2EnvDigest, boolean phase2Valid, Origin origin) {

    public ValidatedTx {
        txCbor = Objects.requireNonNull(txCbor, "txCbor").clone();
        txId = Objects.requireNonNull(txId, "txId").clone();
        if (txId.length != 32) {
            throw new IllegalArgumentException("txId must be 32 bytes: " + txId.length);
        }
        validatedPhase2EnvDigest = Objects.requireNonNull(validatedPhase2EnvDigest, "validatedPhase2EnvDigest")
                .clone();
        Objects.requireNonNull(origin, "origin");
    }

    @Override
    public byte[] txCbor() {
        return txCbor.clone();
    }

    @Override
    public byte[] txId() {
        return txId.clone();
    }

    @Override
    public byte[] validatedPhase2EnvDigest() {
        return validatedPhase2EnvDigest.clone();
    }

    /** @return the transaction id as lowercase hex */
    public String txIdHex() {
        return HexUtil.encodeHexString(txId);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof ValidatedTx other
                && validatedProtocolMajor == other.validatedProtocolMajor
                && validatedEpoch == other.validatedEpoch
                && phase2Valid == other.phase2Valid
                && origin == other.origin
                && Arrays.equals(txId, other.txId)
                && Arrays.equals(txCbor, other.txCbor)
                && Arrays.equals(validatedPhase2EnvDigest, other.validatedPhase2EnvDigest);
    }

    @Override
    public int hashCode() {
        return Objects.hash(Arrays.hashCode(txId), validatedProtocolMajor, validatedEpoch,
                Arrays.hashCode(validatedPhase2EnvDigest), phase2Valid, origin);
    }

    @Override
    public String toString() {
        return "ValidatedTx[txId=" + txIdHex() + ", pv=" + validatedProtocolMajor + ", epoch=" + validatedEpoch
                + ", phase2Valid=" + phase2Valid + ", origin=" + origin + "]";
    }
}
