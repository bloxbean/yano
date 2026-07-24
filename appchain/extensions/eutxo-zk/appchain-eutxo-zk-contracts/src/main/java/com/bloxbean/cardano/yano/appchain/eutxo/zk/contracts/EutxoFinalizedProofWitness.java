package com.bloxbean.cardano.yano.appchain.eutxo.zk.contracts;

import com.bloxbean.cardano.client.address.Address;
import com.bloxbean.cardano.client.crypto.Blake2bUtil;
import com.bloxbean.cardano.client.crypto.KeyGenUtil;
import com.bloxbean.cardano.client.crypto.config.CryptoConfiguration;
import com.bloxbean.cardano.client.transaction.spec.Transaction;
import com.bloxbean.cardano.client.transaction.spec.VkeyWitness;
import com.bloxbean.cardano.client.transaction.util.TransactionUtil;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoRecord;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoTransactionDomain;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoValidityTransition;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Deterministic prover witness derived from one finalized runtime transition.
 *
 * <p>This boundary contains no owner secret and never asks a wallet for a
 * private key. Cardano VKeys and signatures are public transaction witness
 * data. Host verification here is an early queue-safety check only; the live
 * validity circuit must independently constrain the same verification.</p>
 */
public final class EutxoFinalizedProofWitness {
    private final EutxoValidityTransition transition;
    private final byte[] transactionBody;
    private final byte[] transactionBodyHash;
    private final EutxoTransactionDomain domain;
    private final List<Authorization> authorizations;
    private final List<EutxoRecord> resolvedInputs;
    private final List<EutxoRecord> createdOutputs;
    private final long validityStart;
    private final long expiry;

    private EutxoFinalizedProofWitness(
            EutxoValidityTransition transition,
            byte[] transactionBody,
            byte[] transactionBodyHash,
            EutxoTransactionDomain domain,
            List<Authorization> authorizations,
            long validityStart,
            long expiry
    ) {
        this.transition = Objects.requireNonNull(transition, "transition");
        this.transactionBody = transactionBody.clone();
        this.transactionBodyHash = transactionBodyHash.clone();
        this.domain = Objects.requireNonNull(domain, "domain");
        this.authorizations = List.copyOf(authorizations);
        this.resolvedInputs = transition.resolvedInputs();
        this.createdOutputs = transition.created();
        this.validityStart = validityStart;
        this.expiry = expiry;
    }

    /**
     * Derives the unique proof witness for exact finalized transaction bytes.
     */
    public static EutxoFinalizedProofWitness derive(
            EutxoValidityTransition transition
    ) {
        Objects.requireNonNull(transition, "transition");
        try {
            byte[] transactionCbor = transition.canonicalTransaction();
            Transaction transaction = Transaction.deserialize(transactionCbor);
            byte[] body = TransactionUtil.extractTransactionBodyFromTx(
                    transactionCbor);
            byte[] bodyHash = Blake2bUtil.blake2bHash256(body);
            if (!transition.transactionId().equals(
                    HexFormat.of().formatHex(bodyHash))) {
                throw new IllegalArgumentException(
                        "finalized transaction body hash does not match its id");
            }
            EutxoTransactionDomain domain =
                    EutxoTransactionDomain.from(transaction);
            domain.requireExpected(
                    transition.chainId(),
                    transition.network(),
                    transition.profileDigest(),
                    transition.validityProfileDigest());
            if (!Arrays.equals(
                    domain.commitment(),
                    transition.domainCommitment())) {
                throw new IllegalArgumentException(
                        "finalized transaction domain commitment differs");
            }
            List<Authorization> authorizations = authorizations(
                    transaction, transition.resolvedInputs(), bodyHash);
            long validityStart =
                    transaction.getBody().getValidityStartInterval();
            long expiry = transaction.getBody().getTtl();
            if (validityStart < 0 || expiry < 1
                    || validityStart > expiry
                    || expiry != domain.expiry()) {
                throw new IllegalArgumentException(
                        "finalized transaction has an invalid validity interval");
            }
            return new EutxoFinalizedProofWitness(
                    transition, body, bodyHash, domain,
                    authorizations, validityStart, expiry);
        } catch (IllegalArgumentException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new IllegalArgumentException(
                    "finalized transaction cannot produce a proof witness",
                    failure);
        }
    }

    private static List<Authorization> authorizations(
            Transaction transaction,
            List<EutxoRecord> resolvedInputs,
            byte[] bodyHash
    ) {
        List<VkeyWitness> transactionWitnesses =
                transaction.getWitnessSet() == null
                        ? null
                        : transaction.getWitnessSet().getVkeyWitnesses();
        if (transactionWitnesses == null
                || transactionWitnesses.isEmpty()) {
            throw new IllegalArgumentException(
                    "finalized transaction has no VKey witnesses");
        }
        Map<String, Authorization> byCredential =
                new LinkedHashMap<>();
        for (VkeyWitness witness : transactionWitnesses) {
            if (witness == null
                    || witness.getVkey() == null
                    || witness.getVkey().length != 32
                    || witness.getSignature() == null
                    || witness.getSignature().length != 64
                    || !CryptoConfiguration.INSTANCE.getSigningProvider()
                    .verify(witness.getSignature(), bodyHash,
                            witness.getVkey())) {
                throw new IllegalArgumentException(
                        "finalized transaction contains an invalid VKey witness");
            }
            String credential = KeyGenUtil.getKeyHash(
                    witness.getVkey());
            Authorization authorization = new Authorization(
                    credential,
                    witness.getVkey(),
                    witness.getSignature(),
                    inputIndexes(resolvedInputs, credential));
            if (authorization.inputIndexes().isEmpty()) {
                throw new IllegalArgumentException(
                        "finalized transaction contains an unrelated VKey witness");
            }
            if (byCredential.putIfAbsent(
                    credential, authorization) != null) {
                throw new IllegalArgumentException(
                        "finalized transaction repeats a VKey witness");
            }
        }
        for (int index = 0; index < resolvedInputs.size(); index++) {
            String credential = paymentCredential(
                    resolvedInputs.get(index));
            if (!byCredential.containsKey(credential)) {
                throw new IllegalArgumentException(
                        "finalized input " + index
                                + " has no matching VKey witness");
            }
        }
        return List.copyOf(byCredential.values());
    }

    private static List<Integer> inputIndexes(
            List<EutxoRecord> inputs,
            String credential
    ) {
        List<Integer> indexes = new ArrayList<>();
        for (int index = 0; index < inputs.size(); index++) {
            if (credential.equals(paymentCredential(inputs.get(index)))) {
                indexes.add(index);
            }
        }
        return List.copyOf(indexes);
    }

    private static String paymentCredential(EutxoRecord input) {
        Address address = new Address(input.address());
        if (!address.isPubKeyHashInPaymentPart()) {
            throw new IllegalArgumentException(
                    "direct validity profile requires key-controlled inputs");
        }
        return HexFormat.of().formatHex(
                address.getPaymentCredentialHash()
                        .orElseThrow(() -> new IllegalArgumentException(
                                "input has no payment credential")));
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

    public EutxoTransactionDomain domain() {
        return domain;
    }

    public List<Authorization> authorizations() {
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
                && Arrays.equals(
                canonicalBytes(), witness.canonicalBytes());
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(canonicalBytes());
    }

    public record Authorization(
            String paymentCredential,
            byte[] vkey,
            byte[] signature,
            List<Integer> inputIndexes
    ) {
        public Authorization {
            if (paymentCredential == null
                    || !paymentCredential.matches("[0-9a-f]{56}")) {
                throw new IllegalArgumentException(
                        "invalid payment credential");
            }
            vkey = exact(vkey, 32, "VKey");
            signature = exact(signature, 64, "signature");
            inputIndexes = List.copyOf(
                    Objects.requireNonNull(inputIndexes, "inputIndexes"));
            if (inputIndexes.isEmpty()
                    || inputIndexes.stream().anyMatch(
                    index -> index == null || index < 0)) {
                throw new IllegalArgumentException(
                        "authorization input indexes are invalid");
            }
        }

        @Override
        public byte[] vkey() {
            return vkey.clone();
        }

        @Override
        public byte[] signature() {
            return signature.clone();
        }

        private static byte[] exact(
                byte[] value,
                int length,
                String label
        ) {
            Objects.requireNonNull(value, label);
            if (value.length != length) {
                throw new IllegalArgumentException(
                        label + " must contain " + length + " bytes");
            }
            return value.clone();
        }
    }
}
