package com.bloxbean.cardano.yano.api.appchain;

import com.bloxbean.cardano.yaci.core.protocol.appmsg.model.AppMessage;
import com.bloxbean.cardano.yano.api.appchain.l1view.L1Observation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Immutable, block-scoped input to one deterministic state-machine transition.
 *
 * <p>The context contains only facts encoded in the app block. L1 observations
 * are decoded once after runtime admission and retain their original global
 * message indexes. It never exposes a live L1 node or ledger-state handle.</p>
 */
public final class AppBlockExecutionContext {
    private final AppBlock block;
    private final List<Integer> visibleMessageIndexes;
    private final List<SequencedL1Observation> l1Observations;
    private final Map<Integer, SequencedL1Observation> l1ObservationsByIndex;

    private AppBlockExecutionContext(
            AppBlock block,
            List<Integer> visibleMessageIndexes,
            List<SequencedL1Observation> l1Observations
    ) {
        // Only the full-view factory introduces a block. Routed views share
        // that already-snapshotted value and therefore cannot observe caller
        // mutation or multiply materialize large message bodies.
        this.block = Objects.requireNonNull(block, "block");
        this.visibleMessageIndexes = List.copyOf(Objects.requireNonNull(
                visibleMessageIndexes, "visibleMessageIndexes"));
        int previous = -1;
        for (int index : this.visibleMessageIndexes) {
            if (index <= previous || index >= this.block.messages().size()) {
                throw new IllegalArgumentException(
                        "Visible message indexes must be ordered, unique, and inside the block");
            }
            previous = index;
        }
        this.l1Observations = List.copyOf(Objects.requireNonNull(
                l1Observations, "l1Observations"));
        Map<Integer, SequencedL1Observation> indexed = new LinkedHashMap<>();
        for (SequencedL1Observation observation : this.l1Observations) {
            if (observation.originalMessageIndex() >= block.messages().size()
                    || Collections.binarySearch(this.visibleMessageIndexes,
                    observation.originalMessageIndex()) < 0) {
                throw new IllegalArgumentException("L1 observation message index is outside block");
            }
            if (indexed.put(observation.originalMessageIndex(), observation) != null) {
                throw new IllegalArgumentException("Duplicate L1 observation message index");
            }
        }
        this.l1ObservationsByIndex = Map.copyOf(indexed);
    }

    /**
     * Build the deterministic execution view after runtime proposal/catch-up
     * verification. Every reserved L1 envelope must be canonical and match its
     * observer topic; malformed inputs fail closed.
     */
    public static AppBlockExecutionContext fromValidatedBlock(AppBlock block) {
        AppBlock safeBlock = snapshot(Objects.requireNonNull(block, "block"));
        List<SequencedL1Observation> observations = new ArrayList<>();
        List<Integer> indexes = new ArrayList<>(safeBlock.messages().size());
        for (int index = 0; index < safeBlock.messages().size(); index++) {
            indexes.add(index);
            AppMessage message = safeBlock.messages().get(index);
            String topic = message.getTopic();
            if (topic == null || !topic.startsWith(L1Observation.TOPIC_PREFIX)) {
                continue;
            }
            L1Observation observation = L1Observation.decode(message.getBody());
            if (observation == null || !topic.equals(observation.topic())) {
                throw new IllegalArgumentException(
                        "Invalid L1 observation at app-block message index " + index);
            }
            observations.add(new SequencedL1Observation(
                    index, message.getMessageId(), observation));
        }
        return new AppBlockExecutionContext(safeBlock, indexes, observations);
    }

    public AppBlock block() {
        return snapshot(block);
    }

    /** Messages visible to this execution route, in their global block order. */
    public List<AppMessage> messages() {
        return visibleMessageIndexes.stream()
                .map(index -> snapshot(block.messages().get(index)))
                .toList();
    }

    /** Original global block index for one position in {@link #messages()}. */
    public int originalMessageIndex(int visibleMessageIndex) {
        return visibleMessageIndexes.get(visibleMessageIndex);
    }

    /**
     * Create a restricted route over this same globally authenticated block.
     * Indexes are global, ordered, unique, and must already be visible here.
     */
    public AppBlockExecutionContext routeToMessageIndexes(List<Integer> originalIndexes) {
        List<Integer> indexes = List.copyOf(Objects.requireNonNull(
                originalIndexes, "originalIndexes"));
        int previous = -1;
        for (int index : indexes) {
            if (index <= previous) {
                throw new IllegalArgumentException(
                        "Routed message indexes must be ordered, unique, and inside the current view");
            }
            previous = index;
        }
        if (!new LinkedHashSet<>(visibleMessageIndexes).containsAll(indexes)) {
            throw new IllegalArgumentException("Routed messages are outside the current execution view");
        }
        List<SequencedL1Observation> observations = l1Observations.stream()
                .filter(observation -> Collections.binarySearch(
                        indexes, observation.originalMessageIndex()) >= 0)
                .toList();
        return new AppBlockExecutionContext(block, indexes, observations);
    }

    public List<SequencedL1Observation> l1Observations() {
        return l1Observations;
    }

    public Optional<SequencedL1Observation> l1ObservationAt(int originalMessageIndex) {
        return Optional.ofNullable(l1ObservationsByIndex.get(originalMessageIndex));
    }

    private static AppBlock snapshot(AppBlock block) {
        List<AppMessage> messages = block.messages().stream()
                .map(AppBlockExecutionContext::snapshot)
                .toList();
        List<FinalityCert.Signature> signatures = block.cert().signatures().stream()
                .map(signature -> new FinalityCert.Signature(
                        signature.signer().clone(), signature.signature().clone()))
                .toList();
        return new AppBlock(
                block.version(),
                block.chainId(),
                block.height(),
                block.consensusContextDigest().clone(),
                block.view(),
                block.prevHash().clone(),
                block.l1Slot(),
                block.l1BlockHash().clone(),
                block.timestamp(),
                block.messagesRoot().clone(),
                block.stateRoot().clone(),
                messages,
                block.proposer().clone(),
                block.justification().clone(),
                new FinalityCert(block.cert().scheme(), signatures));
    }

    private static AppMessage snapshot(AppMessage message) {
        return AppMessage.builder()
                .version(message.getVersion())
                .messageId(message.getMessageId().clone())
                .chainId(message.getChainId())
                .topic(message.getTopic())
                .sender(message.getSender().clone())
                .senderSeq(message.getSenderSeq())
                .expiresAt(message.getExpiresAt())
                .body(message.getBody().clone())
                .authScheme(message.getAuthScheme())
                .authProof(message.getAuthProof().clone())
                .build();
    }
}
