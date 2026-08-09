package com.bloxbean.cardano.yano.appchain.stdlib;

import com.bloxbean.cardano.yaci.core.protocol.appmsg.model.AppMessage;
import com.bloxbean.cardano.yano.api.appchain.AppBlock;
import com.bloxbean.cardano.yano.api.appchain.AppCapabilityManifest;
import com.bloxbean.cardano.yano.api.appchain.AppBlockExecutionContext;
import com.bloxbean.cardano.yano.api.appchain.AppQueryContext;
import com.bloxbean.cardano.yano.api.appchain.AppQueryException;
import com.bloxbean.cardano.yano.api.appchain.AppStateMachine;
import com.bloxbean.cardano.yano.api.appchain.AppStateWriter;
import com.bloxbean.cardano.yano.api.appchain.effects.AppEffectEmitter;
import com.bloxbean.cardano.yano.api.appchain.transition.TransitionContext;
import com.bloxbean.cardano.yano.api.appchain.transition.TransitionDecision;
import com.bloxbean.cardano.yano.api.appchain.transition.TransitionPlans;
import com.bloxbean.cardano.yano.appchain.stdlib.contracts.DocTrailContract;

import java.util.List;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/**
 * Standard-library state machine {@code doc-trail} (ADR app-layer/006 E2.4):
 * append-only per-entity event trails keyed by an external id ({@code productId},
 * {@code caseId}, ...). Each entity accumulates an ordered, tamper-evident list
 * of entry hashes; every entity's trail head is a provable state key.
 * <p>
 * Command (CBOR body): {@code [entityId(tstr), entryHash(bstr), ref(tstr)]}
 * — {@code entryHash} is the app-level hash of the (off-chain) document/event,
 * {@code ref} an optional locator (URL, IPFS CID, doc id). Bodies stay small:
 * documents live off-chain, the trail records their hashes.
 * <p>
 * State per entity ({@code "e/" + entityId}):
 * {@code count(uint), head-hash(bstr32)} where head-hash chains the entries:
 * {@code head_n = blake2b(head_{n-1} ‖ entryHash_n ‖ author)} (genesis head = 32 zero bytes).
 * The full entry list is recoverable from the block history; the head proves
 * the entity's entire ordered trail against the (anchorable) state root.
 * <p>
 * Use cases: Digital Product Passport, supply-chain trails, case/evidence
 * management.
 */
public final class DocTrailStateMachine implements AppStateMachine {

    public static final String ID = "doc-trail";
    public static final String QUERY_HEAD = "head";
    private final DocTrailTransitions transitions = new DocTrailTransitions();

    @Override
    public String id() {
        return ID;
    }

    @Override
    public AppCapabilityManifest capabilityManifest() {
        return StdlibCapabilityManifests.component(
                        ID, DocTrailContract.DEFAULT_TOPIC, List.of(QUERY_HEAD))
                .proofSubject(new AppCapabilityManifest.ProofSubject(
                        "document-head-v1", "", "e/", "state-proof"))
                .build();
    }

    @Override
    public AdmissionResult validate(AppMessage message) {
        try {
            DocTrailTransitions.decodeCommand(message.getBody());
            return AdmissionResult.accept();
        } catch (Exception e) {
            return AdmissionResult.reject("Malformed doc-trail command: " + e.getMessage());
        }
    }

    @Override
    public void apply(AppBlockExecutionContext context, AppStateWriter writer,
                      AppEffectEmitter effects) {
        AppBlock block = context.block();
        int visibleIndex = 0;
        for (AppMessage message : context.messages()) {
            int originalIndex = context.originalMessageIndex(visibleIndex++);
            DocTrailTransitions.Append command;
            try {
                command = DocTrailTransitions.decodeCommand(message.getBody());
            } catch (Exception e) {
                continue;
            }
            TransitionDecision decision = transitions.decide(command,
                    TransitionContext.of(block, originalIndex, message),
                    DocTrailTransitions.facts(writer, command));
            TransitionPlans.commitIfApproved(decision, writer, effects);
        }
    }

    @Override
    public byte[] query(String path, byte[] params, AppQueryContext state) {
        if (!QUERY_HEAD.equals(path)) {
            throw new AppQueryException(AppQueryException.Code.UNSUPPORTED,
                    "unsupported document-trail query");
        }
        try {
            String entityId = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(params)).toString();
            return state.get(DocTrailContract.entityKey(entityId)).orElse(new byte[0]);
        } catch (Exception invalid) {
            throw new AppQueryException(AppQueryException.Code.INVALID_REQUEST,
                    "document head query requires a bounded UTF-8 entity id");
        }
    }

    // ------------------------------------------------------------------
    // Client/helper encoding + queries
    // ------------------------------------------------------------------

    public static byte[] append(String entityId, byte[] entryHash, String ref) {
        return DocTrailContract.append(entityId, entryHash, ref);
    }

    public static byte[] entityKey(String entityId) {
        return DocTrailContract.entityKey(entityId);
    }

    public static Entry decodeEntry(byte[] stateValue) {
        DocTrailTransitions.TrailHead head = DocTrailTransitions.decodeHead(stateValue);
        return new Entry(head.count(), head.headHash());
    }

    /**
     * Recompute an entity's expected head from its ordered (entryHash, author)
     * sequence — lets a verifier confirm a claimed trail against the proven head.
     */
    public static byte[] computeHead(List<byte[]> entryHashes, List<byte[]> authors) {
        return DocTrailContract.computeHead(entryHashes, authors);
    }

    /** Per-entity trail head: number of entries and the running chained hash. */
    public record Entry(long count, byte[] headHash) {
        public Entry {
            headHash = headHash.clone();
        }
        @Override public byte[] headHash() { return headHash.clone(); }
        byte[] encode() {
            return DocTrailTransitions.encodeHead(
                    new DocTrailTransitions.TrailHead(count, headHash));
        }
    }
}
