package com.bloxbean.cardano.yano.appchain.eutxo.bridge.cardano;

import com.bloxbean.cardano.client.common.cbor.CborSerializationUtil;
import com.bloxbean.cardano.client.crypto.Blake2bUtil;
import com.bloxbean.cardano.client.crypto.api.SigningProvider;
import com.bloxbean.cardano.client.crypto.config.CryptoConfiguration;
import com.bloxbean.cardano.client.transaction.spec.Transaction;
import com.bloxbean.cardano.client.transaction.spec.TransactionBody;
import com.bloxbean.cardano.client.transaction.spec.TransactionWitnessSet;
import com.bloxbean.cardano.client.transaction.spec.VkeyWitness;
import com.bloxbean.cardano.client.transaction.util.TransactionUtil;
import com.bloxbean.cardano.client.util.HexUtil;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

/**
 * ADR-UTXO-009 SP-M3: the federation-threshold {@link
 * BatchSettlementExecutor.ThresholdCosigner} for the A2 Settle transaction.
 *
 * <p>The SP-M2 vault counts approving members via
 * {@code ContextsLib.signedBy} over the root-thread member keys, so the
 * built body already lists exactly those members in its {@code
 * required_signers}. This cosigner attaches one verified Ed25519 vkey witness
 * per required signer and refuses to emit a partially-witnessed transaction:
 * every listed signer must witness (a Cardano ledger invariant), and the
 * count must meet the governed threshold. Signatures are over the body hash
 * (the txid), so adding witnesses never changes the body — a post-assembly
 * hash check guards against serialization drift.
 *
 * <p>The p2p round that gathers member signatures over the app channel is the
 * injected {@link PartialSignatureCollector} (the coordinator/leader wiring
 * lands with the SP-M6 devnet, mirroring {@code ScriptAnchorService}); the
 * verification and assembly here are pure and independently testable.
 */
public final class SettlementCosigner implements BatchSettlementExecutor.ThresholdCosigner {

    /**
     * Gathers member witness signatures over the co-signed body hash. The
     * returned map is {@code memberPublicKeyHex -> Ed25519 signature over
     * bodyHash}; the cosigner re-verifies every entry, so a collector need
     * not (and cannot be trusted to) pre-filter.
     */
    public interface PartialSignatureCollector {
        Map<String, byte[]> collect(byte[] bodyHash, Set<String> memberKeysHex, int threshold)
                throws Exception;
    }

    private final Supplier<Set<String>> membersSupplier;
    private final IntSupplier thresholdSupplier;
    private final PartialSignatureCollector collector;
    private final SigningProvider signingProvider;

    public SettlementCosigner(
            Supplier<Set<String>> membersSupplier,
            IntSupplier thresholdSupplier,
            PartialSignatureCollector collector) {
        this.membersSupplier = Objects.requireNonNull(membersSupplier, "membersSupplier");
        this.thresholdSupplier = Objects.requireNonNull(thresholdSupplier, "thresholdSupplier");
        this.collector = Objects.requireNonNull(collector, "collector");
        this.signingProvider = CryptoConfiguration.INSTANCE.getSigningProvider();
    }

    @Override
    public byte[] cosign(byte[] unsignedBodyCbor, List<String> orderedClaimIds) throws Exception {
        Transaction tx = wrap(unsignedBodyCbor);
        byte[] bodyHash = HexUtil.decodeHexString(TransactionUtil.getTxHash(tx));
        Set<String> members = normalize(membersSupplier.get());
        int threshold = thresholdSupplier.getAsInt();
        if (threshold < 1 || threshold > members.size()) {
            throw new IllegalStateException("invalid settlement threshold " + threshold
                    + " for " + members.size() + " members");
        }
        Map<String, byte[]> collected = collector.collect(bodyHash, members, threshold);
        return assemble(tx, bodyHash, collected == null ? Map.of() : collected,
                members, threshold, signingProvider);
    }

    /**
     * Verify the collected signatures, attach a witness for every {@code
     * required_signer} the body commits to, and serialize — or throw if any
     * required signer is missing/invalid or the threshold is unmet.
     */
    static byte[] assemble(
            Transaction tx,
            byte[] bodyHash,
            Map<String, byte[]> collected,
            Set<String> members,
            int threshold,
            SigningProvider signingProvider
    ) throws Exception {
        // Index the collected signatures by member key hash (28-byte
        // blake2b-224), keeping only members with a valid signature over the
        // co-signed body hash. A non-member, malformed, or forged signature
        // is silently dropped — the required-signer check below fails closed.
        Map<String, byte[]> validSigByKeyHash = new LinkedHashMap<>();
        Map<String, byte[]> pubKeyByKeyHash = new LinkedHashMap<>();
        for (Map.Entry<String, byte[]> entry : collected.entrySet()) {
            String memberHex = entry.getKey() == null
                    ? "" : entry.getKey().toLowerCase(Locale.ROOT);
            byte[] signature = entry.getValue();
            if (!members.contains(memberHex) || signature == null || signature.length != 64) {
                continue;
            }
            byte[] publicKey = HexUtil.decodeHexString(memberHex);
            if (publicKey.length != 32
                    || !signingProvider.verify(signature, bodyHash, publicKey)) {
                continue;
            }
            String keyHash = HexUtil.encodeHexString(Blake2bUtil.blake2bHash224(publicKey));
            validSigByKeyHash.put(keyHash, signature);
            pubKeyByKeyHash.put(keyHash, publicKey);
        }

        List<byte[]> requiredSigners = tx.getBody().getRequiredSigners();
        if (requiredSigners == null || requiredSigners.isEmpty()) {
            throw new IllegalStateException(
                    "settlement body declares no required signers to co-sign");
        }
        if (requiredSigners.size() < threshold) {
            throw new IllegalStateException("settlement body commits to "
                    + requiredSigners.size() + " signers, below threshold " + threshold);
        }

        List<VkeyWitness> vkeyWitnesses = tx.getWitnessSet().getVkeyWitnesses();
        if (vkeyWitnesses == null) {
            vkeyWitnesses = new ArrayList<>();
            tx.getWitnessSet().setVkeyWitnesses(vkeyWitnesses);
        }
        int attached = 0;
        for (byte[] required : requiredSigners) {
            String keyHash = HexUtil.encodeHexString(required);
            byte[] signature = validSigByKeyHash.get(keyHash);
            if (signature == null) {
                throw new IllegalStateException(
                        "settlement co-sign is missing a valid witness for required signer "
                                + keyHash);
            }
            vkeyWitnesses.add(VkeyWitness.builder()
                    .vkey(pubKeyByKeyHash.get(keyHash))
                    .signature(signature)
                    .build());
            attached++;
        }
        if (attached < threshold) {
            throw new IllegalStateException("settlement co-sign attached " + attached
                    + " witnesses, below threshold " + threshold);
        }

        // Witnesses sign the body hash; attaching them must not change it.
        String assembledHash = TransactionUtil.getTxHash(tx);
        if (!assembledHash.equals(HexUtil.encodeHexString(bodyHash))) {
            throw new IllegalStateException("assembled settlement tx hash " + assembledHash
                    + " differs from co-signed body hash "
                    + HexUtil.encodeHexString(bodyHash));
        }
        return tx.serialize();
    }

    private static Transaction wrap(byte[] unsignedBodyCbor) throws Exception {
        TransactionBody body = TransactionBody.deserialize(
                (co.nstant.in.cbor.model.Map) CborSerializationUtil.deserialize(unsignedBodyCbor));
        return Transaction.builder()
                .body(body)
                .witnessSet(new TransactionWitnessSet())
                .build();
    }

    private static Set<String> normalize(Set<String> members) {
        Objects.requireNonNull(members, "members");
        Set<String> normalized = new java.util.TreeSet<>();
        for (String member : members) {
            if (member != null && !member.isBlank()) {
                normalized.add(member.trim().toLowerCase(Locale.ROOT));
            }
        }
        if (normalized.isEmpty()) {
            throw new IllegalStateException("no settlement members configured");
        }
        return normalized;
    }
}
