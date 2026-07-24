package com.bloxbean.cardano.yano.appchain.eutxo.zk.contracts;

import com.bloxbean.cardano.client.crypto.Blake2bUtil;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoL2Authorization;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoL2Domain;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoL2Transaction;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoRecord;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoValidityTransition;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * Deterministic private prover witness derived from one finalized L2 transition.
 *
 * <p>Jubjub signatures reach the sequencer and prover but are not public
 * Groth16 inputs and are never serialized in the Cardano settlement
 * transaction.</p>
 */
public final class EutxoFinalizedProofWitness {
    private final EutxoValidityTransition transition;
    private final byte[] transactionBody;
    private final byte[] transactionBodyHash;
    private final byte[] signingCommitment;
    private final EutxoL2Domain domain;
    private final List<EutxoL2Authorization> authorizations;
    private final List<EutxoRecord> resolvedInputs;
    private final List<EutxoRecord> createdOutputs;
    private final long validityStart;
    private final long expiry;

    private EutxoFinalizedProofWitness(
            EutxoValidityTransition transition,
            byte[] transactionBody,
            byte[] transactionBodyHash,
            byte[] signingCommitment,
            EutxoL2Domain domain,
            List<EutxoL2Authorization> authorizations,
            long validityStart,
            long expiry
    ) {
        this.transition = Objects.requireNonNull(transition, "transition");
        this.transactionBody = transactionBody.clone();
        this.transactionBodyHash = transactionBodyHash.clone();
        this.signingCommitment = signingCommitment.clone();
        this.domain = Objects.requireNonNull(domain, "domain");
        this.authorizations = List.copyOf(authorizations);
        this.resolvedInputs = transition.resolvedInputs();
        this.createdOutputs = transition.created();
        this.validityStart = validityStart;
        this.expiry = expiry;
    }

    /** Derives the unique proof witness for exact finalized L2 bytes. */
    public static EutxoFinalizedProofWitness derive(
            EutxoValidityTransition transition
    ) {
        Objects.requireNonNull(transition, "transition");
        EutxoL2Transaction transaction =
                EutxoL2Transaction.decode(transition.canonicalTransaction());
        if (!transition.transactionId().equals(transaction.transactionId())) {
            throw new IllegalArgumentException(
                    "finalized L2 transaction id differs from its envelope");
        }
        EutxoL2Domain domain = transaction.domain();
        domain.requireExpected(
                transition.chainId(),
                transition.network(),
                transition.profileDigest(),
                transition.validityProfileDigest(),
                transition.authorizationProfile(),
                transition.authorizationProfileDigest());
        if (!Arrays.equals(
                domain.commitment(), transition.domainCommitment())) {
            throw new IllegalArgumentException(
                    "finalized L2 domain commitment differs");
        }
        var body = transaction.decodedBody();
        long validityStart = body.getValidityStartInterval();
        long expiry = body.getTtl();
        if (validityStart < 0 || expiry < 1
                || validityStart > expiry || expiry != domain.expiry()) {
            throw new IllegalArgumentException(
                    "finalized L2 transaction has an invalid validity interval");
        }
        return new EutxoFinalizedProofWitness(
                transition,
                transaction.transactionBody(),
                Blake2bUtil.blake2bHash256(transaction.transactionBody()),
                transaction.signingCommitment(),
                domain,
                transaction.authorizations(),
                validityStart,
                expiry);
    }

    public EutxoValidityTransition transition() {
        return transition;
    }

    public byte[] transactionBody() {
        return transactionBody.clone();
    }

    public byte[] transactionBodyHash() {
        return transactionBodyHash.clone();
    }

    public byte[] signingCommitment() {
        return signingCommitment.clone();
    }

    public EutxoL2Domain domain() {
        return domain;
    }

    public List<EutxoL2Authorization> authorizations() {
        return authorizations;
    }

    public List<EutxoRecord> resolvedInputs() {
        return resolvedInputs;
    }

    public List<EutxoRecord> createdOutputs() {
        return createdOutputs;
    }

    public long validityStart() {
        return validityStart;
    }

    public long expiry() {
        return expiry;
    }

    /** Exact persisted witness bytes; all derived views are reproducible. */
    public byte[] canonicalBytes() {
        return transition.canonicalBytes();
    }

    /** Same transition commitment accumulated by the runtime validity root. */
    public byte[] transitionDigest() {
        return transition.digest();
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof EutxoFinalizedProofWitness witness
                && Arrays.equals(canonicalBytes(), witness.canonicalBytes());
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(canonicalBytes());
    }
}
