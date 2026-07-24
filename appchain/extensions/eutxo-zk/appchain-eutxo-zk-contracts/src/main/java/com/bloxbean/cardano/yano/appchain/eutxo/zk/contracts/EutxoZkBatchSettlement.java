package com.bloxbean.cardano.yano.appchain.eutxo.zk.contracts;

import com.bloxbean.cardano.client.crypto.Blake2bUtil;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoL2Transaction;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/** L1-bound public values appended to one Jubjub-authorized proof statement. */
public record EutxoZkBatchSettlement(
        BigInteger settlementContext,
        BigInteger batchDataCommitment,
        BigInteger withdrawalLovelace
) {
    private static final BigInteger BLS12_381_SCALAR_FIELD =
            new BigInteger(
                    "52435875175126190479447740508185965837690552500527637822603658699938581184513");

    public EutxoZkBatchSettlement {
        requireScalar(settlementContext, "settlementContext");
        requireScalar(batchDataCommitment, "batchDataCommitment");
        requireScalar(withdrawalLovelace, "withdrawalLovelace");
    }

    public static EutxoZkBatchSettlement forTransactions(
            EutxoZkBatchProfile profile,
            String verificationKeyDigest,
            List<EutxoL2Transaction> transactions,
            BigInteger withdrawalLovelace
    ) {
        Objects.requireNonNull(profile, "profile");
        transactions = List.copyOf(Objects.requireNonNull(
                transactions, "transactions"));
        if (transactions.isEmpty()) {
            throw new IllegalArgumentException(
                    "settlement requires at least one transaction");
        }
        var domain = transactions.getFirst().domain();
        if (transactions.stream().anyMatch(transaction ->
                !domain.chainId().equals(transaction.domain().chainId())
                        || !domain.network().equals(
                        transaction.domain().network()))) {
            throw new IllegalArgumentException(
                    "settlement batch mixes L2 domains");
        }
        EutxoZkBatchManifest manifest = new EutxoZkBatchManifest(
                transactions.stream()
                        .map(EutxoL2Transaction::transactionId)
                        .toList());
        return new EutxoZkBatchSettlement(
                contextScalar(
                        domain.chainId(), domain.network(), profile,
                        verificationKeyDigest),
                manifest.commitmentScalar(),
                Objects.requireNonNull(
                        withdrawalLovelace, "withdrawalLovelace"));
    }

    /**
     * Derives every settlement scalar from the exact finalized transition
     * inventory. Callers cannot supply a withdrawal total independently.
     */
    public static EutxoZkBatchSettlement forFinalized(
            EutxoZkBatchProfile profile,
            String verificationKeyDigest,
            List<EutxoFinalizedProofWitness> finalized
    ) {
        finalized = List.copyOf(Objects.requireNonNull(
                finalized, "finalized"));
        if (finalized.isEmpty()) {
            throw new IllegalArgumentException(
                    "settlement requires at least one finalized transition");
        }
        List<EutxoL2Transaction> transactions = finalized.stream()
                .map(EutxoFinalizedProofWitness::transition)
                .map(transition -> EutxoL2Transaction.decode(
                        transition.canonicalTransaction()))
                .toList();
        BigInteger withdrawal = finalized.stream()
                .map(EutxoFinalizedProofWitness::transition)
                .map(transition -> transition.withdrawalLovelace())
                .reduce(BigInteger.ZERO, BigInteger::add);
        return forTransactions(
                profile,
                verificationKeyDigest,
                transactions,
                withdrawal);
    }

    /**
     * Fails closed when host-supplied settlement metadata does not describe
     * the exact ordered transaction inventory and deployment identity.
     *
     * <p>This is a trusted-prover development-profile check. A future
     * hardened profile must constrain the same relationship in-circuit.</p>
     */
    public void requireMatches(
            EutxoZkBatchProfile profile,
            String verificationKeyDigest,
            List<EutxoL2Transaction> transactions
    ) {
        EutxoZkBatchSettlement expected = forTransactions(
                profile,
                verificationKeyDigest,
                transactions,
                withdrawalLovelace);
        if (!equals(expected)) {
            throw new IllegalArgumentException(
                    "settlement metadata does not match the ordered batch");
        }
    }

    public void requireMatchesFinalized(
            EutxoZkBatchProfile profile,
            String verificationKeyDigest,
            List<EutxoFinalizedProofWitness> finalized
    ) {
        EutxoZkBatchSettlement expected = forFinalized(
                profile, verificationKeyDigest, finalized);
        if (!equals(expected)) {
            throw new IllegalArgumentException(
                    "settlement metadata does not match the finalized batch");
        }
    }

    public static BigInteger contextScalar(
            String chainId,
            String network,
            EutxoZkBatchProfile profile,
            String verificationKeyDigest
    ) {
        if (chainId == null || chainId.isBlank()
                || network == null || network.isBlank()
                || verificationKeyDigest == null
                || !verificationKeyDigest.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(
                    "invalid b16 settlement identity");
        }
        String canonical = String.join("\n",
                "yano:eutxo:zk-batch-settlement:v1",
                chainId,
                network,
                profile.id(),
                profile.digest(),
                verificationKeyDigest);
        return new BigInteger(1, Blake2bUtil.blake2bHash256(
                canonical.getBytes(StandardCharsets.UTF_8)))
                .mod(BLS12_381_SCALAR_FIELD);
    }

    public static EutxoZkBatchSettlement decodeManifest(
            String chainId,
            String network,
            EutxoZkBatchProfile profile,
            String verificationKeyDigest,
            byte[] manifest,
            BigInteger withdrawalLovelace
    ) {
        EutxoZkBatchManifest decoded =
                EutxoZkBatchManifest.decode(manifest);
        return new EutxoZkBatchSettlement(
                contextScalar(
                        chainId, network, profile,
                        verificationKeyDigest),
                decoded.commitmentScalar(),
                withdrawalLovelace);
    }

    public String digestHex() {
        String canonical = settlementContext + "\n"
                + batchDataCommitment + "\n"
                + withdrawalLovelace;
        return HexFormat.of().formatHex(Blake2bUtil.blake2bHash256(
                canonical.getBytes(StandardCharsets.US_ASCII)));
    }

    private static void requireScalar(
            BigInteger value,
            String label
    ) {
        Objects.requireNonNull(value, label);
        if (value.signum() < 0 || value.bitLength() > 255) {
            throw new IllegalArgumentException(
                    label + " is outside the scalar envelope");
        }
    }
}
