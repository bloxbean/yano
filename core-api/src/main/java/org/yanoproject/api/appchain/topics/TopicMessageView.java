package org.yanoproject.api.appchain.topics;

import com.bloxbean.cardano.yaci.core.protocol.appmsg.model.AppMessage;
import org.yanoproject.api.appchain.topics.TopicWire.EffectiveOutcome;
import org.yanoproject.api.appchain.topics.TopicWire.Source;
import org.yanoproject.api.appchain.topics.TopicWriteRules.VerifiedWriter;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * What the topic registry established about one message on a registered topic (ADR app-layer/039 D7a):
 * the verified writer (on a protected topic) and the effective policy it was judged under. Every
 * state-machine hook, at admission and in apply, reads it with {@link #of(AppMessage)}.
 */
public final class TopicMessageView {
    private final VerifiedWriter writer;
    private final EffectiveOutcome outcome;

    TopicMessageView(VerifiedWriter writer, EffectiveOutcome outcome) {
        this.writer = writer;
        this.outcome = Objects.requireNonNull(outcome, "outcome");
    }

    /** The view of a message handed to a machine hook; empty for topics no registry policy governs. */
    public static Optional<TopicMessageView> of(AppMessage message) {
        return message instanceof TopicPayloadMessage payload ? Optional.of(payload.view()) : Optional.empty();
    }

    /** The verified writer on a protected topic; empty on an open-writer topic. */
    public Optional<VerifiedWriter> writer() {
        return Optional.ofNullable(writer);
    }

    /** {@code effectivePolicyDigest} of the outcome the message was judged under (D15). */
    public String effectivePolicyDigest() {
        return TopicWireCodec.outcomeDigest(outcome);
    }

    /** The (entry, revision) pairs the policy resolved from, nearest last. */
    public List<Source> policySources() {
        return outcome.sources();
    }

    /** The schema IR hash the payload satisfied, if the topic has a schema. */
    public Optional<String> schemaHash() {
        String schema = outcome.policy().schema();
        return schema.isEmpty() ? Optional.empty() : Optional.of(schema);
    }

    public EffectiveOutcome outcome() {
        return outcome;
    }
}
