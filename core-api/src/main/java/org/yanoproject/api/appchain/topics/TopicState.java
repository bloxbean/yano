package org.yanoproject.api.appchain.topics;

import org.yanoproject.api.appchain.AppStateReader;
import org.yanoproject.api.appchain.topics.TopicWire.Config;
import org.yanoproject.api.appchain.topics.TopicWire.EffectiveOutcome;
import org.yanoproject.api.appchain.topics.TopicWire.EntryContent;
import org.yanoproject.api.appchain.topics.TopicWire.EntryRecord;
import org.yanoproject.api.appchain.topics.TopicWire.GovernanceApproval;
import org.yanoproject.api.appchain.topics.TopicWire.QueuedRevision;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Typed reads of the topic registry from one state view (a committed parent root or a candidate). */
public final class TopicState {
    private final AppStateReader reader;
    private final long maxMessageBytes;

    /** @param maxMessageBytes the chain's consensus-profile message bound (the default payload bound) */
    public TopicState(AppStateReader reader, long maxMessageBytes) {
        this.reader = Objects.requireNonNull(reader, "reader");
        if (maxMessageBytes < 1) {
            throw new IllegalArgumentException("maxMessageBytes must be positive");
        }
        this.maxMessageBytes = maxMessageBytes;
    }

    /** The committed configuration; empty when topics are not enabled on this chain. */
    public Optional<Config> config() {
        return reader.get(TopicKeys.config()).map(TopicWireCodec::decodeConfig);
    }

    public Optional<EntryRecord> entry(String name) {
        return reader.get(TopicKeys.entry(name)).map(TopicWireCodec::decodeEntryRecord);
    }

    /** Records of the names that can govern {@code topic} (D3), shortest first; absent names omitted. */
    public Map<String, EntryRecord> applicableEntries(String topic) {
        Map<String, EntryRecord> entries = new LinkedHashMap<>();
        for (String name : TopicNames.applicableNames(topic)) {
            entry(name).ifPresent(record -> entries.put(name, record));
        }
        return entries;
    }

    public EffectiveOutcome outcome(String topic) {
        // Without a configuration topics are disabled; no entry read is needed
        Optional<Config> config = config();
        return TopicPolicyResolver.resolve(topic, config.orElse(null), maxMessageBytes,
                config.isPresent() ? applicableEntries(topic) : Map.of());
    }

    public Optional<QueuedRevision> queued(String name, long revision) {
        return reader.get(TopicKeys.queued(name, revision)).map(TopicWireCodec::decodeQueuedRevision);
    }

    /**
     * The content of an entry's head: its latest queued revision while any is pending, else its active
     * revision (revision numbers are never reused, so after a cancellation the head may exceed both).
     */
    public Optional<EntryContent> headContent(EntryRecord record) {
        if (record.pending() == 0) {
            return Optional.ofNullable(record.active()).map(TopicWire.ActiveRevision::content);
        }
        return queued(record.name(), record.head()).map(QueuedRevision::content);
    }

    /** Entries in the registry, pending creations included. */
    public long count() {
        return reader.get(TopicKeys.count()).map(TopicKeys::u64).orElse(0L);
    }

    public boolean hasSchema(String irHash) {
        return reader.get(TopicKeys.schema(irHash)).isPresent();
    }

    public Optional<byte[]> schema(String irHash) {
        return reader.get(TopicKeys.schema(irHash));
    }

    public Optional<TopicWire.Receipt> receipt(String messageId) {
        return reader.get(TopicKeys.receipt(messageId)).map(TopicWireCodec::decodeReceipt);
    }

    public boolean used(String statementHash) {
        return reader.get(TopicKeys.used(statementHash)).isPresent();
    }

    public boolean revoked(String delegationHash) {
        return reader.get(TopicKeys.revoked(delegationHash)).isPresent();
    }

    public List<GovernanceApproval> governancePending() {
        return reader.get(TopicKeys.governance()).map(TopicWireCodec::decodeGovernancePending).orElse(List.of());
    }
}
