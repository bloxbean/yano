package org.yanoproject.api.appchain.topics;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Records of the topic-registry v1 wire formats (ADR app-layer/039). The normative CDDL is
 * {@code META-INF/yano/topics/v1/topics.cddl}; {@link TopicWireCodec} encodes and strictly decodes them.
 *
 * <p>Keys, hashes and nonces are lowercase hex strings so the records compare by value and sort
 * bytewise; payloads, schema IR and signatures stay byte arrays and are copied defensively.</p>
 */
public final class TopicWire {
    public static final int MAX_KEYS = 16;
    /** Governance approvers are members: at most the app-chain membership bound. */
    public static final int MAX_APPROVERS = 32;
    public static final String AUTHORITY_REGISTRARS = "~registrars";
    public static final String AUTHORITY_CLAIM = "~claim";
    public static final String AUTHORITY_ISSUER = "~issuer";

    private static final Pattern KEY = Pattern.compile("[0-9a-f]{64}");
    private static final Pattern NONCE = Pattern.compile("[0-9a-f]{32}");

    private TopicWire() {
    }

    // ------------------------------------------------------------------ key sets and policies

    /** A k-of-n key set: keys sorted bytewise and unique, {@code 1 <= threshold <= n <= 16}. */
    public record KeySet(int threshold, List<String> keys) {
        public KeySet {
            keys = sortedKeys(keys, 1, "key set");
            if (threshold < 1 || threshold > keys.size()) {
                throw new IllegalArgumentException("key-set threshold must be in [1, " + keys.size() + "]");
            }
        }
    }

    /** Writers of a policy: {@code keys == null} means open (anyone may write). */
    public record Writers(KeySet keys) {
        public static final Writers OPEN = new Writers(null);

        public boolean open() {
            return keys == null;
        }
    }

    /**
     * A policy as stored on one entry; {@code null} fields inherit from the nearest ancestor that
     * sets them. {@code schema} is a 64-hex IR hash, {@code ""} for explicitly none, or null.
     */
    public record Policy(Writers writers, List<String> delegate, Long maxDelegationMs, String schema,
                         Long maxPayloadBytes) {
        public static final Policy INHERIT = new Policy(null, null, null, null, null);

        public Policy {
            delegate = delegate == null ? null : sortedKeys(delegate, 0, "delegate");
            if (maxDelegationMs != null && maxDelegationMs < 1) {
                throw new IllegalArgumentException("maxDelegationMs must be positive");
            }
            if (schema != null && !schema.isEmpty()) {
                schema = requireHash(schema, "schema");
            }
            if (maxPayloadBytes != null && maxPayloadBytes < 1) {
                throw new IllegalArgumentException("maxPayloadBytes must be positive");
            }
        }
    }

    /** The policy governing a topic after resolution (D3, D15). {@code schema} is {@code ""} for none. */
    public record ResolvedPolicy(Writers writers, List<String> delegate, long maxDelegationMs, String schema,
                                 long maxPayloadBytes, boolean retired) {
        public ResolvedPolicy {
            Objects.requireNonNull(writers, "writers");
            delegate = sortedKeys(delegate, 0, "delegate");
            schema = schema.isEmpty() ? "" : requireHash(schema, "schema");
            if (maxDelegationMs < 1 || maxPayloadBytes < 1) {
                throw new IllegalArgumentException("resolved policy limits must be positive");
            }
        }
    }

    // ------------------------------------------------------------------ configuration

    /**
     * Chain-level topic configuration. {@code governanceWindowBlocks} is the approval window of topic
     * governance (ADR-008.3 accounting); it is consensus state, never a node-local setting.
     */
    public record Settings(boolean governance, KeySet registrars, boolean openClaims, boolean unregisteredReject,
                           long maxDelegationMs, long maxCommandMs, long maxPendingPerEntry, long maxEntries,
                           long maxEntriesPerNamespace, long activationLag, long governanceWindowBlocks) {
        public static final long DEFAULT_GOVERNANCE_WINDOW_BLOCKS = 600;
        /** Retention bound of credentials and receipts (365 days), so no setting pins an expiry queue (D9). */
        public static final long MAX_RETENTION_MS = 31_536_000_000L;
        /** Activation-lag bound in blocks, so {@code height + lag} can never overflow. */
        public static final long MAX_ACTIVATION_LAG = 1_000_000;

        public Settings {
            if (maxDelegationMs < 1 || maxCommandMs < 1 || maxPendingPerEntry < 1 || maxEntries < 1
                    || maxEntriesPerNamespace < 1 || activationLag < 1 || governanceWindowBlocks < 1) {
                throw new IllegalArgumentException("topic settings limits must be positive");
            }
            if (maxDelegationMs > MAX_RETENTION_MS || maxCommandMs > MAX_RETENTION_MS) {
                throw new IllegalArgumentException("topic retention settings exceed " + MAX_RETENTION_MS + " ms");
            }
            if (activationLag > MAX_ACTIVATION_LAG) {
                throw new IllegalArgumentException("topic activation lag exceeds " + MAX_ACTIVATION_LAG + " blocks");
            }
        }
    }

    /**
     * The committed configuration. {@code registrarsSince} is the revision at which the current registrar set
     * took effect: the {@code ~registrars} authority epoch (D5a), unchanged by other configuration changes.
     */
    public record Config(long revision, long registrarsSince, Settings settings) {
        public Config {
            Objects.requireNonNull(settings, "settings");
            if (revision < 1 || registrarsSince < 1 || registrarsSince > revision) {
                throw new IllegalArgumentException("config revisions must satisfy 1 <= registrarsSince <= revision");
            }
        }
    }

    // ------------------------------------------------------------------ registry state

    public record EntryContent(KeySet owners, Policy policy, boolean retired) {
        public EntryContent {
            Objects.requireNonNull(owners, "owners");
            Objects.requireNonNull(policy, "policy");
        }
    }

    /**
     * The revision in force. {@code ownersSince} is the revision at which the current owners took effect: the
     * entry's authority epoch (D5a), which policy changes that keep the owners do not advance.
     */
    public record ActiveRevision(long revision, long activeFrom, long ownersSince, EntryContent content) {
        public ActiveRevision {
            Objects.requireNonNull(content, "content");
            if (revision < 1 || activeFrom < 1 || ownersSince < 1 || ownersSince > revision) {
                throw new IllegalArgumentException("invalid active revision");
            }
        }
    }

    /** An entry record; {@code active == null} is a pending creation that reserves the name. */
    public record EntryRecord(String name, ActiveRevision active, long head, long pending, long subtree) {
        public EntryRecord {
            Objects.requireNonNull(name, "name");
            if (head < 1 || pending < 0 || subtree < 0
                    || active != null && active.revision() > head
                    || active == null && pending < 1) {
                throw new IllegalArgumentException("inconsistent entry record head metadata");
            }
        }
    }

    /** A pending revision; {@code messageId} is the command whose receipt reports its outcome. */
    public record QueuedRevision(long revision, long activation, String authority, long authorityEpoch,
                                 EntryContent content, String messageId) {
        public QueuedRevision {
            Objects.requireNonNull(authority, "authority");
            Objects.requireNonNull(content, "content");
            messageId = requireHash(messageId, "messageId");
            if (revision < 1 || activation < 1 || authorityEpoch < 0) {
                throw new IllegalArgumentException("invalid queued revision");
            }
        }
    }

    public record ExpiryItem(int kind, String hash) {
        public static final int USED = 0;
        public static final int REVOKED = 1;
        public static final int RECEIPT = 2;

        public ExpiryItem {
            if (kind != USED && kind != REVOKED && kind != RECEIPT) {
                throw new IllegalArgumentException("unknown expiry item kind");
            }
            hash = requireHash(hash, "expiry hash");
        }
    }

    /**
     * The header of the items one block added to an expiry queue (each item has its own key): pruned once
     * block time passes {@code maxNotAfter}; {@code next} is the next batch's height, 0 for none.
     */
    public record ExpiryBatch(long maxNotAfter, long next, long count, long pruned) {
        public ExpiryBatch {
            if (next < 0 || count < 1 || pruned < 0 || pruned >= count) {
                throw new IllegalArgumentException("invalid expiry batch header");
            }
        }
    }

    /**
     * An activation ticket: an entry name, or {@link TopicKeys#CONFIG_HISTORY_NAME}. {@code messageId} is the
     * command that queued the revision: a ticket left behind by a cancelled creation never matches a later
     * creation that reuses the freed name and revision number.
     */
    public record Activation(String name, long revision, String messageId) {
        public Activation {
            Objects.requireNonNull(name, "name");
            messageId = requireHash(messageId, "messageId");
            if (revision < 1) {
                throw new IllegalArgumentException("activation revision must be positive");
            }
        }
    }

    /** The outcome of a registry or governance command: ACCEPTED or a refusal code, and its activation. */
    public record Receipt(String code, long activation) {
        public static final String ACCEPTED = "ACCEPTED";

        public Receipt {
            Objects.requireNonNull(code, "code");
            if (activation < 0) {
                throw new IllegalArgumentException("activation must be nonnegative");
            }
        }
    }

    public record GovernanceApproval(String commandHash, long firstSeen, List<String> approvers) {
        public GovernanceApproval {
            commandHash = requireHash(commandHash, "command hash");
            approvers = sortedKeys(approvers, 1, MAX_APPROVERS, "approvers");
            if (firstSeen < 1) {
                throw new IllegalArgumentException("approval height must be positive");
            }
        }
    }

    // ------------------------------------------------------------------ commands

    public sealed interface Operation permits Create, Update, Rotate, Retire, Revoke {
    }

    public record Create(KeySet owners, Policy policy, byte[] schemaIr) implements Operation {
        public Create {
            Objects.requireNonNull(owners, "owners");
            Objects.requireNonNull(policy, "policy");
            schemaIr = schemaIr == null ? null : schemaIr.clone();
        }

        @Override
        public byte[] schemaIr() {
            return schemaIr == null ? null : schemaIr.clone();
        }
    }

    public record Update(Policy policy, byte[] schemaIr) implements Operation {
        public Update {
            Objects.requireNonNull(policy, "policy");
            schemaIr = schemaIr == null ? null : schemaIr.clone();
        }

        @Override
        public byte[] schemaIr() {
            return schemaIr == null ? null : schemaIr.clone();
        }
    }

    public record Rotate(KeySet owners) implements Operation {
        public Rotate {
            Objects.requireNonNull(owners, "owners");
        }
    }

    public record Retire() implements Operation {
    }

    public record Revoke(Delegation delegation) implements Operation {
        public Revoke {
            Objects.requireNonNull(delegation, "delegation");
        }
    }

    public record CommandStatement(String chainId, String generationId, String target, String authority,
                                   Long expected, String nonce, long notAfter, Operation operation) {
        public CommandStatement {
            Objects.requireNonNull(chainId, "chainId");
            generationId = requireHash(generationId, "generationId");
            Objects.requireNonNull(target, "target");
            Objects.requireNonNull(authority, "authority");
            nonce = requireNonce(nonce);
            Objects.requireNonNull(operation, "operation");
            if (expected != null && expected < 1 || notAfter < 0) {
                throw new IllegalArgumentException("invalid command statement");
            }
        }
    }

    /** A signature over a statement; {@code key} is 64 lowercase hex characters. */
    public record Signature(String key, byte[] sig) {
        public Signature {
            key = requireKey(key, "signature key");
            if (sig == null || sig.length != 64) {
                throw new IllegalArgumentException("signature must be 64 bytes");
            }
            sig = sig.clone();
        }

        @Override
        public byte[] sig() {
            return sig.clone();
        }
    }

    public record RegistryCommand(CommandStatement statement, List<Signature> signatures) {
        public RegistryCommand {
            Objects.requireNonNull(statement, "statement");
            signatures = sortedSignatures(signatures);
        }
    }

    public sealed interface GovernanceOperation permits GovCreate, GovRecover, GovRetire, GovConfigure {
    }

    public record GovCreate(String name, KeySet owners, Policy policy, byte[] schemaIr)
            implements GovernanceOperation {
        public GovCreate {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(owners, "owners");
            Objects.requireNonNull(policy, "policy");
            schemaIr = schemaIr == null ? null : schemaIr.clone();
        }

        @Override
        public byte[] schemaIr() {
            return schemaIr == null ? null : schemaIr.clone();
        }
    }

    public record GovRecover(String name, KeySet owners) implements GovernanceOperation {
        public GovRecover {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(owners, "owners");
        }
    }

    public record GovRetire(String name) implements GovernanceOperation {
        public GovRetire {
            Objects.requireNonNull(name, "name");
        }
    }

    public record GovConfigure(Settings settings) implements GovernanceOperation {
        public GovConfigure {
            Objects.requireNonNull(settings, "settings");
        }
    }

    public record GovernanceCommand(String chainId, String generationId, String nonce,
                                    GovernanceOperation operation) {
        public GovernanceCommand {
            Objects.requireNonNull(chainId, "chainId");
            generationId = requireHash(generationId, "generationId");
            nonce = requireNonce(nonce);
            Objects.requireNonNull(operation, "operation");
        }
    }

    // ------------------------------------------------------------------ writes

    public record Delegation(String delegate, String scope, long notBefore, long notAfter, String issuer,
                             byte[] sig) {
        public Delegation {
            delegate = requireKey(delegate, "delegate");
            Objects.requireNonNull(scope, "scope");
            issuer = requireKey(issuer, "issuer");
            if (notBefore < 0 || notAfter < notBefore) {
                throw new IllegalArgumentException("delegation window must satisfy 0 <= notBefore <= notAfter");
            }
            if (sig == null || sig.length != 64) {
                throw new IllegalArgumentException("delegation signature must be 64 bytes");
            }
            sig = sig.clone();
        }

        @Override
        public byte[] sig() {
            return sig.clone();
        }
    }

    public record WriteStatement(String chainId, String generationId, String topic, String payloadHash,
                                 String nonce, long notAfter, String delegationHash) {
        public WriteStatement {
            Objects.requireNonNull(chainId, "chainId");
            generationId = requireHash(generationId, "generationId");
            Objects.requireNonNull(topic, "topic");
            payloadHash = requireHash(payloadHash, "payloadHash");
            nonce = requireNonce(nonce);
            delegationHash = delegationHash == null ? null : requireHash(delegationHash, "delegationHash");
            if (notAfter < 0) {
                throw new IllegalArgumentException("notAfter must be nonnegative");
            }
        }
    }

    public record WriterProof(String nonce, long notAfter, List<Signature> signatures, Delegation delegation) {
        public WriterProof {
            nonce = requireNonce(nonce);
            signatures = sortedSignatures(signatures);
            if (notAfter < 0) {
                throw new IllegalArgumentException("notAfter must be nonnegative");
            }
        }
    }

    public record Frame(byte[] payload, WriterProof proof) {
        public Frame {
            payload = Objects.requireNonNull(payload, "payload").clone();
            Objects.requireNonNull(proof, "proof");
        }

        @Override
        public byte[] payload() {
            return payload.clone();
        }
    }

    // ------------------------------------------------------------------ effective outcome

    /** Outcome kinds with their frozen wire codes (D15). */
    public enum OutcomeKind {
        DISABLED(0), POLICY(1), UNREGISTERED_OPEN(2), UNREGISTERED_REJECT(3);

        private final int code;

        OutcomeKind(int code) {
            this.code = code;
        }

        public int code() {
            return code;
        }
    }

    public record Source(String name, long revision) {
        public Source {
            Objects.requireNonNull(name, "name");
            if (revision < 1) {
                throw new IllegalArgumentException("source revision must be positive");
            }
        }
    }

    public record EffectiveOutcome(OutcomeKind kind, ResolvedPolicy policy, Long configRevision,
                                   List<Source> sources) {
        public EffectiveOutcome {
            Objects.requireNonNull(kind, "kind");
            sources = List.copyOf(sources);
            if ((kind == OutcomeKind.POLICY) != (policy != null)
                    || (kind == OutcomeKind.DISABLED) != (configRevision == null)
                    || kind != OutcomeKind.POLICY && !sources.isEmpty()) {
                throw new IllegalArgumentException("inconsistent effective outcome");
            }
        }
    }

    // ------------------------------------------------------------------ validation helpers

    static String requireKey(String value, String what) {
        String normalized = Objects.requireNonNull(value, what).toLowerCase(Locale.ROOT);
        if (!KEY.matcher(normalized).matches()) {
            throw new IllegalArgumentException(what + " must be 32 bytes of hex");
        }
        return normalized;
    }

    static String requireHash(String value, String what) {
        return requireKey(value, what);
    }

    static String requireNonce(String value) {
        String normalized = Objects.requireNonNull(value, "nonce").toLowerCase(Locale.ROOT);
        if (!NONCE.matcher(normalized).matches()) {
            throw new IllegalArgumentException("nonce must be 16 bytes of hex");
        }
        return normalized;
    }

    /** Sorted, duplicate-free key list; a list given out of order or with a repeat is refused. */
    static List<String> sortedKeys(List<String> keys, int minimum, String what) {
        return sortedKeys(keys, minimum, MAX_KEYS, what);
    }

    static List<String> sortedKeys(List<String> keys, int minimum, int maximum, String what) {
        Objects.requireNonNull(keys, what);
        if (keys.size() < minimum || keys.size() > maximum) {
            throw new IllegalArgumentException(what + " must hold " + minimum + ".." + maximum + " keys");
        }
        List<String> normalized = new ArrayList<>(keys.size());
        for (String key : keys) {
            String next = requireKey(key, what);
            if (!normalized.isEmpty() && normalized.get(normalized.size() - 1).compareTo(next) >= 0) {
                throw new IllegalArgumentException(what + " keys must be sorted and unique");
            }
            normalized.add(next);
        }
        return List.copyOf(normalized);
    }

    static List<Signature> sortedSignatures(List<Signature> signatures) {
        Objects.requireNonNull(signatures, "signatures");
        if (signatures.isEmpty() || signatures.size() > MAX_KEYS) {
            throw new IllegalArgumentException("signatures must hold 1.." + MAX_KEYS + " entries");
        }
        Set<String> seen = new HashSet<>();
        String previous = null;
        for (Signature signature : signatures) {
            Objects.requireNonNull(signature, "signature");
            if (!seen.add(signature.key()) || previous != null && previous.compareTo(signature.key()) > 0) {
                throw new IllegalArgumentException("signatures must be sorted by key and unique");
            }
            previous = signature.key();
        }
        return List.copyOf(signatures);
    }
}
