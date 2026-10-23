package org.yanoproject.api.appchain.topics;

import org.yanoproject.api.appchain.topics.TopicWire.ActiveRevision;
import org.yanoproject.api.appchain.topics.TopicWire.Config;
import org.yanoproject.api.appchain.topics.TopicWire.EffectiveOutcome;
import org.yanoproject.api.appchain.topics.TopicWire.EntryRecord;
import org.yanoproject.api.appchain.topics.TopicWire.KeySet;
import org.yanoproject.api.appchain.topics.TopicWire.OutcomeKind;
import org.yanoproject.api.appchain.topics.TopicWire.Policy;
import org.yanoproject.api.appchain.topics.TopicWire.ResolvedPolicy;
import org.yanoproject.api.appchain.topics.TopicWire.Source;
import org.yanoproject.api.appchain.topics.TopicWire.Writers;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Resolves the effective outcome of a topic (ADR app-layer/039 D3, D14, D15). It is a pure function
 * of the committed configuration and the committed entry records of the topic's applicable names,
 * so members at ingress, the proposer, followers and offline proof verifiers all compute the same
 * outcome from the same parent root.
 */
public final class TopicPolicyResolver {
    private TopicPolicyResolver() {
    }

    /**
     * @param topic             the message topic
     * @param config            the committed configuration, or null when topics are not enabled
     * @param maxMessageBytes   the chain's consensus-profile message bound (the default payload bound)
     * @param entries           the committed record of each applicable name that has one
     */
    public static EffectiveOutcome resolve(String topic, Config config, long maxMessageBytes,
                                           Map<String, EntryRecord> entries) {
        Objects.requireNonNull(topic, "topic");
        Objects.requireNonNull(entries, "entries");
        if (config == null) {
            return new EffectiveOutcome(OutcomeKind.DISABLED, null, null, List.of());
        }
        Writers writers = Writers.OPEN;
        List<String> delegate = List.of();
        long maxDelegationMs = config.settings().maxDelegationMs();
        String schema = "";
        long maxPayloadBytes = maxMessageBytes;
        boolean retired = false;
        List<Source> sources = new ArrayList<>();
        for (String name : TopicNames.applicableNames(topic)) {
            EntryRecord record = entries.get(name);
            ActiveRevision active = record != null ? record.active() : null;
            if (active == null) {
                continue; // absent, or a pending creation that governs nothing yet
            }
            Policy policy = active.content().policy();
            if (policy.writers() != null) {
                writers = policy.writers();
            }
            if (policy.delegate() != null) {
                delegate = policy.delegate();
            }
            if (policy.maxDelegationMs() != null) {
                maxDelegationMs = policy.maxDelegationMs();
            }
            if (policy.schema() != null) {
                schema = policy.schema();
            }
            if (policy.maxPayloadBytes() != null) {
                maxPayloadBytes = policy.maxPayloadBytes();
            }
            retired |= active.content().retired();
            sources.add(new Source(name, active.revision()));
        }
        long configRevision = config.revision();
        if (sources.isEmpty()) {
            OutcomeKind kind = config.settings().unregisteredReject()
                    ? OutcomeKind.UNREGISTERED_REJECT : OutcomeKind.UNREGISTERED_OPEN;
            return new EffectiveOutcome(kind, null, configRevision, List.of());
        }
        ResolvedPolicy resolved = new ResolvedPolicy(writers, effectiveDelegate(writers, delegate),
                Math.min(maxDelegationMs, config.settings().maxDelegationMs()), schema,
                Math.min(maxPayloadBytes, maxMessageBytes), retired);
        return new EffectiveOutcome(OutcomeKind.POLICY, resolved, configRevision, List.copyOf(sources));
    }

    /**
     * Delegation stands in for one writer (D8): it applies only to a 1-of-n writer set, and only
     * keys that are writers may issue delegations.
     */
    private static List<String> effectiveDelegate(Writers writers, List<String> delegate) {
        KeySet keys = writers.keys();
        if (keys == null || keys.threshold() != 1) {
            return List.of();
        }
        return delegate.stream().filter(keys.keys()::contains).toList();
    }
}
