package org.yanoproject.api.appchain.topics;

import com.bloxbean.cardano.yaci.core.util.HexUtil;
import org.yanoproject.api.appchain.schema.CborSchema;
import org.yanoproject.api.appchain.topics.TopicWire.Config;
import org.yanoproject.api.appchain.topics.TopicWire.Delegation;
import org.yanoproject.api.appchain.topics.TopicWire.EffectiveOutcome;
import org.yanoproject.api.appchain.topics.TopicWire.Frame;
import org.yanoproject.api.appchain.topics.TopicWire.KeySet;
import org.yanoproject.api.appchain.topics.TopicWire.OutcomeKind;
import org.yanoproject.api.appchain.topics.TopicWire.ResolvedPolicy;
import org.yanoproject.api.appchain.topics.TopicWire.Signature;
import org.yanoproject.api.appchain.topics.TopicWire.WriteStatement;
import org.yanoproject.api.util.StrictEd25519;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * The write check of ADR app-layer/039 D11 steps 2-6 for one message on an ordinary topic: registry
 * lookup and status, frame decode, writer proof (D7, D8), replay guard (D9), payload size and schema
 * (D10). It is a pure function of the message, the parent state, the block time and the statements of
 * earlier messages in the block, so ingress, the proposer and every follower reach the same verdict.
 */
public final class TopicWriteRules {
    private TopicWriteRules() {
    }

    /** Who wrote a message on a protected topic: the sorted, distinct signer set, or a session key and issuer. */
    public record VerifiedWriter(Kind kind, List<String> signers, String sessionKey, String issuer) {
        public enum Kind { DIRECT, DELEGATED }

        public VerifiedWriter {
            Objects.requireNonNull(kind, "kind");
            signers = List.copyOf(signers);
        }
    }

    /**
     * The verdict. When allowed, {@code payload} is the bytes the machine receives as the body (D7a); on a
     * protected topic {@code writer} and {@code statementHash} are set and the statement is recorded as used.
     */
    public record Result(TopicRefusal refusal, String detail, EffectiveOutcome outcome, byte[] payload,
                         VerifiedWriter writer, String statementHash, long statementNotAfter) {
        static Result refused(TopicRefusal refusal, String detail) {
            return new Result(refusal, detail, null, null, null, null, 0);
        }

        public boolean allowed() {
            return refusal == null;
        }

        public boolean framed() {
            return writer != null;
        }
    }

    /**
     * @param earlierStatements statement hashes of protected writes earlier in the same block (D9)
     */
    public static Result check(String topic, byte[] body, String chainId, String generationId, long blockTime,
                               TopicState state, Set<String> earlierStatements) {
        EffectiveOutcome outcome = state.outcome(topic);
        switch (outcome.kind()) {
            case DISABLED, UNREGISTERED_OPEN -> {
                return new Result(null, null, outcome, body, null, null, 0);
            }
            case UNREGISTERED_REJECT -> {
                return Result.refused(TopicRefusal.TOPIC_UNREGISTERED, "no registry entry covers " + topic);
            }
            default -> {
            }
        }
        ResolvedPolicy policy = outcome.policy();
        if (policy.retired()) {
            return Result.refused(TopicRefusal.TOPIC_RETIRED, topic + " is retired");
        }
        if (policy.writers().open()) {
            return payloadChecks(body, policy, state, new Result(null, null, outcome, body, null, null, 0));
        }
        Frame frame;
        try {
            frame = TopicWireCodec.decodeFrame(body);
        } catch (IllegalArgumentException malformed) {
            return Result.refused(TopicRefusal.TOPIC_FRAME_INVALID, malformed.getMessage());
        }
        Config config = state.config().orElseThrow();
        long proofNotAfter = frame.proof().notAfter();
        if (proofNotAfter < blockTime || proofNotAfter - blockTime > config.settings().maxDelegationMs()) {
            return Result.refused(TopicRefusal.TOPIC_PROOF_EXPIRED, "write proof notAfter outside its window");
        }
        Delegation delegation = frame.proof().delegation();
        String delegationHash = delegation == null ? null
                : TopicWireCodec.delegationHash(chainId, generationId, delegation);
        WriteStatement statement = new WriteStatement(chainId, generationId, topic,
                TopicWireCodec.blake2b256Hex(frame.payload()), frame.proof().nonce(), proofNotAfter, delegationHash);
        byte[] statementBytes = TopicWireCodec.encodeWriteStatement(statement);
        VerifiedWriter writer;
        if (delegation == null) {
            writer = directWriter(frame.proof().signatures(), policy.writers().keys(), statementBytes);
            if (writer == null) {
                return Result.refused(TopicRefusal.TOPIC_WRITER_DENIED, "signatures do not meet the writer policy");
            }
        } else {
            TopicRefusal delegated = checkDelegation(topic, delegation, delegationHash, policy, blockTime, chainId,
                    generationId, state);
            if (delegated != null) {
                return Result.refused(delegated, "delegation is not valid for this write");
            }
            List<Signature> signatures = frame.proof().signatures();
            if (signatures.size() != 1 || !signatures.getFirst().key().equals(delegation.delegate())
                    || !verify(signatures.getFirst(), statementBytes)) {
                return Result.refused(TopicRefusal.TOPIC_WRITER_DENIED, "session key signature does not verify");
            }
            writer = new VerifiedWriter(VerifiedWriter.Kind.DELEGATED, List.of(delegation.delegate()),
                    delegation.delegate(), delegation.issuer());
        }
        String statementHash = TopicWireCodec.blake2b256Hex(statementBytes);
        if (earlierStatements.contains(statementHash) || state.used(statementHash)) {
            return Result.refused(TopicRefusal.TOPIC_PROOF_REPLAYED, "write statement already used");
        }
        return payloadChecks(frame.payload(), policy, state,
                new Result(null, null, outcome, frame.payload(), writer, statementHash, proofNotAfter));
    }

    /** k-of-n distinct writer keys, every signature valid (I20); null when the policy is not met. */
    private static VerifiedWriter directWriter(List<Signature> signatures, KeySet writers, byte[] statement) {
        for (Signature signature : signatures) {
            if (!writers.keys().contains(signature.key()) || !verify(signature, statement)) {
                return null;
            }
        }
        if (signatures.size() < writers.threshold()) {
            return null;
        }
        return new VerifiedWriter(VerifiedWriter.Kind.DIRECT, signatures.stream().map(Signature::key).toList(),
                null, null);
    }

    /** D8 rules 1-4 under the policy active at the block height. */
    private static TopicRefusal checkDelegation(String topic, Delegation delegation, String delegationHash,
                                                ResolvedPolicy policy, long blockTime, String chainId,
                                                String generationId, TopicState state) {
        byte[] signed = TopicWireCodec.delegationSignedBytes(chainId, generationId, delegation);
        // The scope is an entry name, as a revocation's target must be, so every usable delegation is revocable
        boolean valid = policy.delegate().contains(delegation.issuer())
                && TopicNames.isValidEntryName(delegation.scope())
                && TopicNames.covers(delegation.scope(), topic)
                && mayDelegate(state, delegation.scope(), delegation.issuer())
                && delegation.notBefore() <= blockTime && blockTime <= delegation.notAfter()
                && delegation.notAfter() - delegation.notBefore() <= policy.maxDelegationMs()
                && StrictEd25519.verify(HexUtil.decodeHexString(delegation.issuer()), delegation.sig(), signed)
                && !state.revoked(delegationHash);
        return valid ? null : TopicRefusal.TOPIC_DELEGATION_INVALID;
    }

    /**
     * The issuer may delegate the whole scope (D8, I9). Writes and {@code ~issuer} revocations share this rule,
     * so an issuer can always revoke every delegation that is usable.
     */
    static boolean mayDelegate(TopicState state, String scope, String issuer) {
        EffectiveOutcome outcome = state.outcome(scope);
        return outcome.kind() == OutcomeKind.POLICY && outcome.policy().delegate().contains(issuer);
    }

    private static Result payloadChecks(byte[] payload, ResolvedPolicy policy, TopicState state, Result allowed) {
        if (payload.length > policy.maxPayloadBytes()) {
            return Result.refused(TopicRefusal.TOPIC_PAYLOAD_TOO_LARGE, "payload exceeds maxPayloadBytes");
        }
        if (!policy.schema().isEmpty()) {
            byte[] ir = state.schema(policy.schema()).orElse(null);
            if (ir == null || !CborSchema.accepts(ir, payload)) {
                return Result.refused(TopicRefusal.TOPIC_SCHEMA_REJECTED, "payload does not satisfy the topic schema");
            }
        }
        return allowed;
    }

    private static boolean verify(Signature signature, byte[] statement) {
        return StrictEd25519.verify(HexUtil.decodeHexString(signature.key()), signature.sig(), statement);
    }
}
