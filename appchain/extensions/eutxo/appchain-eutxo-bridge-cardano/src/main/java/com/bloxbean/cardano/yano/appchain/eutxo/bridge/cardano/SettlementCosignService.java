package com.bloxbean.cardano.yano.appchain.eutxo.bridge.cardano;

import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.ByteString;
import co.nstant.in.cbor.model.DataItem;
import co.nstant.in.cbor.model.UnsignedInteger;
import com.bloxbean.cardano.client.common.cbor.CborSerializationUtil;
import com.bloxbean.cardano.client.crypto.api.SigningProvider;
import com.bloxbean.cardano.client.crypto.config.CryptoConfiguration;
import com.bloxbean.cardano.client.transaction.spec.Transaction;
import com.bloxbean.cardano.client.transaction.spec.TransactionBody;
import com.bloxbean.cardano.client.transaction.spec.TransactionWitnessSet;
import com.bloxbean.cardano.client.transaction.util.TransactionUtil;
import com.bloxbean.cardano.client.util.HexUtil;
import com.bloxbean.cardano.yaci.core.protocol.appmsg.model.AppMessage;
import com.bloxbean.cardano.yano.api.appchain.l1view.BridgeDiffusionHandler;
import com.bloxbean.cardano.yano.api.appchain.signer.SignerProvider;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BiConsumer;
import java.util.function.IntSupplier;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * ADR-UTXO-009 SP-M6: the federation co-sign round for A2 batch settlement,
 * carried over the {@code ~bridge/settlement/*} diffusion channel — the
 * ScriptAnchorService pattern scoped to settlement:
 *
 * <ul>
 *   <li>the OWNER node (single-owner pinning: exactly one node runs the
 *       settlement executor and this service in leader mode) builds the
 *       unsigned body and broadcasts it on {@code ~bridge/settlement/sign},
 *   <li>every ledger member VERIFIES the proposed body against its own view
 *       (the injected verifier — consensus-critical custody check) and
 *       replies with an Ed25519 signature over the body hash on
 *       {@code ~bridge/settlement/sig},
 *   <li>the owner collects replies and hands the signature map to
 *       {@link SettlementCosigner}, whose verification/assembly core
 *       re-checks every witness against the body's required signers.
 * </ul>
 *
 * <p>Signatures are body-hash-specific: any rebuild restarts the round.
 * Duplicate/unordered delivery is tolerated (a reply for an unknown or
 * completed round is dropped). The service implements
 * {@link BatchSettlementExecutor.ThresholdCosigner}, so the executor calls
 * {@link #cosign(byte[], List)} directly on its own thread; the round blocks
 * that thread up to the configured timeout — the effect runtime's retry
 * policy handles rounds that time out (members offline).
 */
public final class SettlementCosignService
        implements BatchSettlementExecutor.ThresholdCosigner, BridgeDiffusionHandler {

    public static final String TOPIC_SIGN = "~bridge/settlement/sign";
    public static final String TOPIC_SIG = "~bridge/settlement/sig";

    private static final int WIRE_VERSION = 1;
    private static final int MAX_BODY_BYTES = 262_144;

    private final BiConsumer<String, byte[]> diffuser;
    private final SignerProvider memberSigner;
    private final Supplier<Set<String>> membersSupplier;
    private final IntSupplier thresholdSupplier;
    private final Predicate<byte[]> bodyVerifier;
    private final boolean leader;
    private final Duration roundTimeout;
    private final SigningProvider signingProvider;

    /** The leader's outstanding round, keyed by body-hash hex. */
    private final Map<String, PendingRound> rounds = new ConcurrentHashMap<>();

    public SettlementCosignService(
            BiConsumer<String, byte[]> diffuser,
            SignerProvider memberSigner,
            Supplier<Set<String>> membersSupplier,
            IntSupplier thresholdSupplier,
            Predicate<byte[]> bodyVerifier,
            boolean leader,
            Duration roundTimeout) {
        this.diffuser = Objects.requireNonNull(diffuser, "diffuser");
        this.memberSigner = Objects.requireNonNull(memberSigner, "memberSigner");
        this.membersSupplier = Objects.requireNonNull(membersSupplier, "membersSupplier");
        this.thresholdSupplier = Objects.requireNonNull(thresholdSupplier, "thresholdSupplier");
        this.bodyVerifier = Objects.requireNonNull(bodyVerifier, "bodyVerifier");
        this.leader = leader;
        this.roundTimeout = Objects.requireNonNull(roundTimeout, "roundTimeout");
        this.signingProvider = CryptoConfiguration.INSTANCE.getSigningProvider();
    }

    // ------------------------------------------------------------------
    // Owner side: the executor's ThresholdCosigner.
    // ------------------------------------------------------------------

    @Override
    public byte[] cosign(byte[] unsignedBodyCbor, List<String> orderedClaimIds)
            throws Exception {
        if (!leader) {
            throw new IllegalStateException(
                    "settlement co-sign rounds start only on the owner node");
        }
        byte[] bodyHash = bodyHash(unsignedBodyCbor);
        String roundKey = HexUtil.encodeHexString(bodyHash);
        Set<String> members = normalizedMembers();
        PendingRound round = new PendingRound(bodyHash, members);
        rounds.put(roundKey, round);
        try {
            // The owner is a member too: contribute our own signature first,
            // then ask the rest.
            round.accept(memberSigner.publicKeyHex()
                            .toLowerCase(Locale.ROOT),
                    memberSigner.sign(bodyHash));
            diffuser.accept(TOPIC_SIGN, encodeSignRequest(unsignedBodyCbor));
            Map<String, byte[]> gathered;
            try {
                gathered = round.future.get(
                        roundTimeout.toMillis(), TimeUnit.MILLISECONDS);
            } catch (TimeoutException timeout) {
                // Hand over whatever arrived — SettlementCosigner fails
                // closed if any required signer is missing.
                gathered = Map.copyOf(round.signatures);
            }
            final Map<String, byte[]> collected = gathered;
            SettlementCosigner assembler = new SettlementCosigner(
                    membersSupplier, thresholdSupplier,
                    (hash, requested, threshold) -> collected);
            return assembler.cosign(unsignedBodyCbor, orderedClaimIds);
        } finally {
            rounds.remove(roundKey);
        }
    }

    // ------------------------------------------------------------------
    // Diffusion receiver: member sign-requests + owner signature replies.
    // ------------------------------------------------------------------

    @Override
    public void onBridgeMessage(AppMessage message) {
        if (message == null || message.getTopic() == null) {
            return;
        }
        switch (message.getTopic()) {
            case TOPIC_SIGN -> onSignRequest(message);
            case TOPIC_SIG -> onSignature(message);
            default -> {
            }
        }
    }

    /**
     * Member side: verify the proposed settlement body against OUR OWN view
     * before signing — the injected verifier is the custody gate; a body we
     * cannot positively verify is never signed.
     */
    private void onSignRequest(AppMessage message) {
        byte[] unsignedBodyCbor = decodeSignRequest(message.getBody());
        if (unsignedBodyCbor == null) {
            return;
        }
        boolean verified;
        try {
            verified = bodyVerifier.test(unsignedBodyCbor);
        } catch (RuntimeException failure) {
            verified = false;
        }
        if (!verified) {
            return;
        }
        try {
            byte[] bodyHash = bodyHash(unsignedBodyCbor);
            diffuser.accept(TOPIC_SIG, encodeSignature(
                    bodyHash, memberSigner.sign(bodyHash)));
        } catch (Exception ignored) {
            // A body we cannot canonicalize is a body we do not sign.
        }
    }

    /** Owner side: collect a member's signature into the outstanding round. */
    private void onSignature(AppMessage message) {
        if (!leader || message.getSender() == null) {
            return;
        }
        SignatureReply reply = decodeSignature(message.getBody());
        if (reply == null) {
            return;
        }
        PendingRound round = rounds.get(HexUtil.encodeHexString(reply.bodyHash()));
        if (round == null) {
            return;
        }
        String senderHex = HexUtil.encodeHexString(message.getSender())
                .toLowerCase(Locale.ROOT);
        if (!round.members.contains(senderHex)) {
            return;
        }
        // Verify before accepting — a forged reply must not stall the round
        // by occupying the member's slot.
        try {
            if (!signingProvider.verify(
                    reply.signature(), round.bodyHash, message.getSender())) {
                return;
            }
        } catch (Exception invalid) {
            return;
        }
        round.accept(senderHex, reply.signature());
    }

    // ------------------------------------------------------------------

    private Set<String> normalizedMembers() {
        Set<String> normalized = new java.util.TreeSet<>();
        for (String member : membersSupplier.get()) {
            if (member != null && !member.isBlank()) {
                normalized.add(member.trim().toLowerCase(Locale.ROOT));
            }
        }
        if (normalized.isEmpty()) {
            throw new IllegalStateException("no settlement members configured");
        }
        return normalized;
    }

    /** Canonical body hash (the txid): decode + re-serialize, never raw bytes. */
    private static byte[] bodyHash(byte[] unsignedBodyCbor) throws Exception {
        TransactionBody body = TransactionBody.deserialize(
                (co.nstant.in.cbor.model.Map) CborSerializationUtil
                        .deserialize(unsignedBodyCbor));
        Transaction shell = Transaction.builder()
                .body(body)
                .witnessSet(new TransactionWitnessSet())
                .build();
        return HexUtil.decodeHexString(TransactionUtil.getTxHash(shell));
    }

    static byte[] encodeSignRequest(byte[] unsignedBodyCbor) {
        try {
            Array array = new Array();
            array.add(new UnsignedInteger(WIRE_VERSION));
            array.add(new ByteString(unsignedBodyCbor));
            return CborSerializationUtil.serialize(array);
        } catch (Exception failure) {
            throw new IllegalStateException(
                    "settlement sign-request encoding failed", failure);
        }
    }

    static byte[] decodeSignRequest(byte[] body) {
        try {
            if (body == null || body.length == 0 || body.length > MAX_BODY_BYTES) {
                return null;
            }
            Array array = (Array) CborSerializationUtil.deserialize(body);
            List<DataItem> items = array.getDataItems();
            if (items.size() != 2
                    || ((UnsignedInteger) items.get(0)).getValue()
                    .longValueExact() != WIRE_VERSION) {
                return null;
            }
            byte[] unsignedBodyCbor = ((ByteString) items.get(1)).getBytes();
            return unsignedBodyCbor.length == 0 ? null : unsignedBodyCbor;
        } catch (Throwable malformed) {
            return null;
        }
    }

    static byte[] encodeSignature(byte[] bodyHash, byte[] signature) {
        try {
            Array array = new Array();
            array.add(new UnsignedInteger(WIRE_VERSION));
            array.add(new ByteString(bodyHash));
            array.add(new ByteString(signature));
            return CborSerializationUtil.serialize(array);
        } catch (Exception failure) {
            throw new IllegalStateException(
                    "settlement signature encoding failed", failure);
        }
    }

    static SignatureReply decodeSignature(byte[] body) {
        try {
            if (body == null || body.length == 0 || body.length > 512) {
                return null;
            }
            Array array = (Array) CborSerializationUtil.deserialize(body);
            List<DataItem> items = array.getDataItems();
            if (items.size() != 3
                    || ((UnsignedInteger) items.get(0)).getValue()
                    .longValueExact() != WIRE_VERSION) {
                return null;
            }
            byte[] bodyHash = ((ByteString) items.get(1)).getBytes();
            byte[] signature = ((ByteString) items.get(2)).getBytes();
            if (bodyHash.length != 32 || signature.length != 64) {
                return null;
            }
            return new SignatureReply(bodyHash, signature);
        } catch (Throwable malformed) {
            return null;
        }
    }

    record SignatureReply(byte[] bodyHash, byte[] signature) {
    }

    private static final class PendingRound {
        private final byte[] bodyHash;
        private final Set<String> members;
        private final Map<String, byte[]> signatures = new ConcurrentHashMap<>();
        private final CompletableFuture<Map<String, byte[]>> future =
                new CompletableFuture<>();

        private PendingRound(byte[] bodyHash, Set<String> members) {
            this.bodyHash = bodyHash.clone();
            this.members = Set.copyOf(members);
        }

        private void accept(String memberHex, byte[] signature) {
            signatures.putIfAbsent(memberHex, signature.clone());
            if (signatures.keySet().containsAll(members)) {
                future.complete(Map.copyOf(signatures));
            }
        }
    }
}
