package org.yanoproject.api.appchain.topics;

import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.ByteString;
import co.nstant.in.cbor.model.DataItem;
import co.nstant.in.cbor.model.SimpleValue;
import co.nstant.in.cbor.model.SimpleValueType;
import co.nstant.in.cbor.model.UnicodeString;
import co.nstant.in.cbor.model.UnsignedInteger;
import com.bloxbean.cardano.client.crypto.Blake2bUtil;
import com.bloxbean.cardano.yaci.core.util.CborSerializationUtil;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import org.yanoproject.api.appchain.codec.internal.CborStructurePreflight;
import org.yanoproject.api.appchain.topics.TopicWire.ActiveRevision;
import org.yanoproject.api.appchain.topics.TopicWire.CommandStatement;
import org.yanoproject.api.appchain.topics.TopicWire.Config;
import org.yanoproject.api.appchain.topics.TopicWire.Create;
import org.yanoproject.api.appchain.topics.TopicWire.Delegation;
import org.yanoproject.api.appchain.topics.TopicWire.EffectiveOutcome;
import org.yanoproject.api.appchain.topics.TopicWire.EntryContent;
import org.yanoproject.api.appchain.topics.TopicWire.EntryRecord;
import org.yanoproject.api.appchain.topics.TopicWire.ExpiryBatch;
import org.yanoproject.api.appchain.topics.TopicWire.ExpiryItem;
import org.yanoproject.api.appchain.topics.TopicWire.Frame;
import org.yanoproject.api.appchain.topics.TopicWire.GovConfigure;
import org.yanoproject.api.appchain.topics.TopicWire.GovCreate;
import org.yanoproject.api.appchain.topics.TopicWire.GovRecover;
import org.yanoproject.api.appchain.topics.TopicWire.GovRetire;
import org.yanoproject.api.appchain.topics.TopicWire.GovernanceApproval;
import org.yanoproject.api.appchain.topics.TopicWire.GovernanceCommand;
import org.yanoproject.api.appchain.topics.TopicWire.GovernanceOperation;
import org.yanoproject.api.appchain.topics.TopicWire.KeySet;
import org.yanoproject.api.appchain.topics.TopicWire.Operation;
import org.yanoproject.api.appchain.topics.TopicWire.OutcomeKind;
import org.yanoproject.api.appchain.topics.TopicWire.Policy;
import org.yanoproject.api.appchain.topics.TopicWire.QueuedRevision;
import org.yanoproject.api.appchain.topics.TopicWire.RegistryCommand;
import org.yanoproject.api.appchain.topics.TopicWire.ResolvedPolicy;
import org.yanoproject.api.appchain.topics.TopicWire.Retire;
import org.yanoproject.api.appchain.topics.TopicWire.Revoke;
import org.yanoproject.api.appchain.topics.TopicWire.Rotate;
import org.yanoproject.api.appchain.topics.TopicWire.Settings;
import org.yanoproject.api.appchain.topics.TopicWire.Signature;
import org.yanoproject.api.appchain.topics.TopicWire.Source;
import org.yanoproject.api.appchain.topics.TopicWire.Update;
import org.yanoproject.api.appchain.topics.TopicWire.WriteStatement;
import org.yanoproject.api.appchain.topics.TopicWire.WriterProof;
import org.yanoproject.api.appchain.topics.TopicWire.Writers;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Function;

/**
 * Deterministic CBOR codec for the topic-registry v1 formats. Every decoder runs a bounded
 * structural preflight, reads exact types (no tags, floats or maps), and requires that
 * re-encoding reproduces the input bytes.
 */
public final class TopicWireCodec {
    /** Upper bound of any topic-registry structure on the wire or in state. */
    private static final int MAX_BYTES = 1 << 20;
    /** Upper bound of an entry record or the configuration record (D15 {@code max-entry-record-bytes}). */
    public static final int MAX_RECORD_BYTES = 4_096;
    private static final CborStructurePreflight.Limits LIMITS =
            new CborStructurePreflight.Limits(MAX_BYTES, 16, 4_096, 512, MAX_BYTES);

    static final String COMMAND_DOMAIN = "yano-topic-command-v1";
    static final String GOVERNANCE_DOMAIN = "yano-topic-governance-v1";
    static final String DELEGATION_DOMAIN = "yano-topic-delegation-v1";
    static final String WRITE_DOMAIN = "yano-topic-write-v1";
    static final String OUTCOME_DOMAIN = "yano-topic-outcome-v1";

    private TopicWireCodec() {
    }

    // ------------------------------------------------------------------ public encode / decode

    public static byte[] encodeConfig(Config config) {
        return requireRecordBound(bytes(array(uint(1), uint(config.revision()), uint(config.registrarsSince()),
                settings(config.settings()))));
    }

    public static Config decodeConfig(byte[] bytes) {
        requireRecordBound(bytes);
        return decode(bytes, item -> {
            List<DataItem> fields = fields(item, 4);
            version(fields.get(0));
            return new Config(longValue(fields.get(1)), longValue(fields.get(2)), settings(fields.get(3)));
        }, TopicWireCodec::encodeConfig);
    }

    /** Encode an entry record; a record above {@link #MAX_RECORD_BYTES} is refused when written (D15). */
    public static byte[] encodeEntryRecord(EntryRecord record) {
        return requireRecordBound(bytes(array(uint(1), text(record.name()),
                record.active() == null ? nil() : activeRevision(record.active()),
                uint(record.head()), uint(record.pending()), uint(record.subtree()))));
    }

    public static EntryRecord decodeEntryRecord(byte[] bytes) {
        requireRecordBound(bytes);
        return decode(bytes, item -> {
            List<DataItem> fields = fields(item, 6);
            version(fields.get(0));
            return new EntryRecord(text(fields.get(1)),
                    isNull(fields.get(2)) ? null : activeRevision(fields.get(2)),
                    longValue(fields.get(3)), longValue(fields.get(4)), longValue(fields.get(5)));
        }, TopicWireCodec::encodeEntryRecord);
    }

    public static byte[] encodeActiveRevision(ActiveRevision revision) {
        return bytes(activeRevision(revision));
    }

    public static byte[] encodeQueuedRevision(QueuedRevision queued) {
        return bytes(array(uint(queued.revision()), uint(queued.activation()), text(queued.authority()),
                uint(queued.authorityEpoch()), content(queued.content()), hash32(queued.messageId())));
    }

    public static QueuedRevision decodeQueuedRevision(byte[] bytes) {
        return decode(bytes, item -> {
            List<DataItem> fields = fields(item, 6);
            return new QueuedRevision(longValue(fields.get(0)), longValue(fields.get(1)), text(fields.get(2)),
                    longValue(fields.get(3)), content(fields.get(4)), hex(fixed(fields.get(5), 32)));
        }, TopicWireCodec::encodeQueuedRevision);
    }

    public static byte[] encodeExpiryBatch(ExpiryBatch batch) {
        return bytes(array(uint(batch.maxNotAfter()), uint(batch.next()), uint(batch.count()),
                uint(batch.pruned())));
    }

    public static ExpiryBatch decodeExpiryBatch(byte[] bytes) {
        return decode(bytes, item -> {
            List<DataItem> fields = fields(item, 4);
            return new ExpiryBatch(longValue(fields.get(0)), longValue(fields.get(1)), longValue(fields.get(2)),
                    longValue(fields.get(3)));
        }, TopicWireCodec::encodeExpiryBatch);
    }

    public static byte[] encodeExpiryItem(ExpiryItem item) {
        return bytes(array(uint(item.kind()), hash32(item.hash())));
    }

    public static ExpiryItem decodeExpiryItem(byte[] bytes) {
        return decode(bytes, item -> {
            List<DataItem> fields = fields(item, 2);
            return new ExpiryItem(intValue(fields.get(0)), hex(fixed(fields.get(1), 32)));
        }, TopicWireCodec::encodeExpiryItem);
    }

    public static byte[] encodeActivation(TopicWire.Activation activation) {
        return bytes(array(text(activation.name()), uint(activation.revision()), hash32(activation.messageId())));
    }

    public static TopicWire.Activation decodeActivation(byte[] bytes) {
        return decode(bytes, item -> {
            List<DataItem> fields = fields(item, 3);
            return new TopicWire.Activation(text(fields.get(0)), longValue(fields.get(1)),
                    hex(fixed(fields.get(2), 32)));
        }, TopicWireCodec::encodeActivation);
    }

    public static byte[] encodeReceipt(TopicWire.Receipt receipt) {
        return bytes(array(text(receipt.code()), uint(receipt.activation())));
    }

    public static TopicWire.Receipt decodeReceipt(byte[] bytes) {
        return decode(bytes, item -> {
            List<DataItem> fields = fields(item, 2);
            return new TopicWire.Receipt(text(fields.get(0)), longValue(fields.get(1)));
        }, TopicWireCodec::encodeReceipt);
    }

    public static byte[] encodeGovernancePending(List<GovernanceApproval> approvals) {
        Array list = new Array();
        for (GovernanceApproval approval : approvals) {
            list.add(array(hash32(approval.commandHash()), uint(approval.firstSeen()), keys(approval.approvers())));
        }
        return bytes(list);
    }

    public static List<GovernanceApproval> decodeGovernancePending(byte[] bytes) {
        return decode(bytes, item -> {
            List<GovernanceApproval> approvals = new ArrayList<>();
            for (DataItem entry : list(item)) {
                List<DataItem> fields = fields(entry, 3);
                approvals.add(new GovernanceApproval(hex(fixed(fields.get(0), 32)), longValue(fields.get(1)),
                        keyList(fields.get(2))));
            }
            return List.copyOf(approvals);
        }, TopicWireCodec::encodeGovernancePending);
    }

    public static byte[] encodeCommandStatement(CommandStatement statement) {
        return bytes(statement(statement));
    }

    public static byte[] encodeRegistryCommand(RegistryCommand command) {
        return bytes(array(uint(1), statement(command.statement()), signatures(command.signatures())));
    }

    public static RegistryCommand decodeRegistryCommand(byte[] bytes) {
        return decode(bytes, item -> {
            List<DataItem> fields = fields(item, 3);
            version(fields.get(0));
            return new RegistryCommand(statement(fields.get(1)), signatures(fields.get(2)));
        }, TopicWireCodec::encodeRegistryCommand);
    }

    public static byte[] encodeGovernanceCommand(GovernanceCommand command) {
        return bytes(array(text(GOVERNANCE_DOMAIN), text(command.chainId()), hash32(command.generationId()),
                hash32(command.nonce()), governanceOperation(command.operation())));
    }

    public static GovernanceCommand decodeGovernanceCommand(byte[] bytes) {
        return decode(bytes, item -> {
            List<DataItem> fields = fields(item, 5);
            domain(fields.get(0), GOVERNANCE_DOMAIN);
            return new GovernanceCommand(text(fields.get(1)), hex(fixed(fields.get(2), 32)),
                    hex(fixed(fields.get(3), 16)), governanceOperation(fields.get(4)));
        }, TopicWireCodec::encodeGovernanceCommand);
    }

    public static byte[] encodeDelegation(Delegation delegation) {
        return bytes(delegation(delegation));
    }

    public static Delegation decodeDelegation(byte[] bytes) {
        return decode(bytes, TopicWireCodec::delegation, TopicWireCodec::encodeDelegation);
    }

    /** The bytes the issuer signs; {@link #delegationHash} is their BLAKE2b-256. */
    public static byte[] delegationSignedBytes(String chainId, String generationId, Delegation delegation) {
        return bytes(array(text(DELEGATION_DOMAIN), text(chainId), hash32(generationId), uint(1),
                hash32(delegation.delegate()), text(delegation.scope()), uint(delegation.notBefore()),
                uint(delegation.notAfter()), hash32(delegation.issuer())));
    }

    public static String delegationHash(String chainId, String generationId, Delegation delegation) {
        return hex(Blake2bUtil.blake2bHash256(delegationSignedBytes(chainId, generationId, delegation)));
    }

    public static byte[] encodeWriteStatement(WriteStatement statement) {
        return bytes(array(text(WRITE_DOMAIN), text(statement.chainId()), hash32(statement.generationId()),
                text(statement.topic()), hash32(statement.payloadHash()), hash32(statement.nonce()),
                uint(statement.notAfter()),
                statement.delegationHash() == null ? nil() : hash32(statement.delegationHash())));
    }

    /** The replay identity of a write (D9): BLAKE2b-256 of the canonical write-statement. */
    public static String statementHash(WriteStatement statement) {
        return hex(Blake2bUtil.blake2bHash256(encodeWriteStatement(statement)));
    }

    public static byte[] encodeFrame(Frame frame) {
        WriterProof proof = frame.proof();
        return bytes(array(uint(1), new ByteString(frame.payload()), array(hash32(proof.nonce()),
                uint(proof.notAfter()), signatures(proof.signatures()),
                proof.delegation() == null ? nil() : delegation(proof.delegation()))));
    }

    public static Frame decodeFrame(byte[] bytes) {
        return decode(bytes, item -> {
            List<DataItem> fields = fields(item, 3);
            version(fields.get(0));
            List<DataItem> proof = fields(fields.get(2), 4);
            return new Frame(byteString(fields.get(1)), new WriterProof(hex(fixed(proof.get(0), 16)),
                    longValue(proof.get(1)), signatures(proof.get(2)),
                    isNull(proof.get(3)) ? null : delegation(proof.get(3))));
        }, TopicWireCodec::encodeFrame);
    }

    public static byte[] encodeEffectiveOutcome(EffectiveOutcome outcome) {
        Array sources = new Array();
        for (Source source : outcome.sources()) {
            sources.add(array(text(source.name()), uint(source.revision())));
        }
        return bytes(array(text(OUTCOME_DOMAIN), uint(outcome.kind().code()),
                outcome.policy() == null ? nil() : resolvedPolicy(outcome.policy()),
                outcome.configRevision() == null ? nil() : uint(outcome.configRevision()), sources));
    }

    /** {@code effectivePolicyDigest} (D15). */
    public static String outcomeDigest(EffectiveOutcome outcome) {
        return hex(Blake2bUtil.blake2bHash256(encodeEffectiveOutcome(outcome)));
    }

    /** BLAKE2b-256 as lowercase hex. */
    public static String blake2b256Hex(byte[] bytes) {
        return hex(Blake2bUtil.blake2bHash256(bytes));
    }

    // ------------------------------------------------------------------ structures

    private static Array settings(Settings settings) {
        return array(bool(settings.governance()),
                settings.registrars() == null ? nil() : keySet(settings.registrars()),
                bool(settings.openClaims()), bool(settings.unregisteredReject()),
                uint(settings.maxDelegationMs()), uint(settings.maxCommandMs()),
                uint(settings.maxPendingPerEntry()), uint(settings.maxEntries()),
                uint(settings.maxEntriesPerNamespace()), uint(settings.activationLag()),
                uint(settings.governanceWindowBlocks()));
    }

    private static Settings settings(DataItem item) {
        List<DataItem> fields = fields(item, 11);
        return new Settings(bool(fields.get(0)), isNull(fields.get(1)) ? null : keySet(fields.get(1)),
                bool(fields.get(2)), bool(fields.get(3)), longValue(fields.get(4)), longValue(fields.get(5)),
                longValue(fields.get(6)), longValue(fields.get(7)), longValue(fields.get(8)),
                longValue(fields.get(9)), longValue(fields.get(10)));
    }

    private static Array keySet(KeySet keySet) {
        return array(uint(keySet.threshold()), keys(keySet.keys()));
    }

    private static KeySet keySet(DataItem item) {
        List<DataItem> fields = fields(item, 2);
        return new KeySet(intValue(fields.get(0)), keyList(fields.get(1)));
    }

    private static Array keys(List<String> keys) {
        Array array = new Array();
        keys.forEach(key -> array.add(hash32(key)));
        return array;
    }

    private static List<String> keyList(DataItem item) {
        List<String> keys = new ArrayList<>();
        for (DataItem key : list(item)) {
            keys.add(hex(fixed(key, 32)));
        }
        return keys;
    }

    private static DataItem writers(Writers writers) {
        return writers.open() ? uint(0) : keySet(writers.keys());
    }

    private static Writers writers(DataItem item) {
        if (item instanceof UnsignedInteger && !item.hasTag()) {
            if (longValue(item) != 0) {
                throw invalid("open writers must be encoded as 0");
            }
            return Writers.OPEN;
        }
        return new Writers(keySet(item));
    }

    private static Array policy(Policy policy) {
        return array(policy.writers() == null ? nil() : writers(policy.writers()),
                policy.delegate() == null ? nil() : keys(policy.delegate()),
                policy.maxDelegationMs() == null ? nil() : uint(policy.maxDelegationMs()),
                policy.schema() == null ? nil() : schema(policy.schema()),
                policy.maxPayloadBytes() == null ? nil() : uint(policy.maxPayloadBytes()));
    }

    private static Policy policy(DataItem item) {
        List<DataItem> fields = fields(item, 5);
        return new Policy(isNull(fields.get(0)) ? null : writers(fields.get(0)),
                isNull(fields.get(1)) ? null : keyList(fields.get(1)),
                isNull(fields.get(2)) ? null : longValue(fields.get(2)),
                isNull(fields.get(3)) ? null : schema(fields.get(3)),
                isNull(fields.get(4)) ? null : longValue(fields.get(4)));
    }

    private static Array resolvedPolicy(ResolvedPolicy policy) {
        return array(writers(policy.writers()), keys(policy.delegate()), uint(policy.maxDelegationMs()),
                schema(policy.schema()), uint(policy.maxPayloadBytes()), bool(policy.retired()));
    }

    private static ByteString schema(String schema) {
        return new ByteString(schema.isEmpty() ? new byte[0] : HexUtil.decodeHexString(schema));
    }

    private static String schema(DataItem item) {
        byte[] bytes = byteString(item);
        if (bytes.length != 0 && bytes.length != 32) {
            throw invalid("schema must be empty or a 32-byte hash");
        }
        return hex(bytes);
    }

    private static Array content(EntryContent content) {
        return array(keySet(content.owners()), policy(content.policy()), bool(content.retired()));
    }

    private static EntryContent content(DataItem item) {
        List<DataItem> fields = fields(item, 3);
        return new EntryContent(keySet(fields.get(0)), policy(fields.get(1)), bool(fields.get(2)));
    }

    private static Array activeRevision(ActiveRevision revision) {
        return array(uint(revision.revision()), uint(revision.activeFrom()), uint(revision.ownersSince()),
                content(revision.content()));
    }

    private static ActiveRevision activeRevision(DataItem item) {
        List<DataItem> fields = fields(item, 4);
        return new ActiveRevision(longValue(fields.get(0)), longValue(fields.get(1)), longValue(fields.get(2)),
                content(fields.get(3)));
    }

    private static Array statement(CommandStatement statement) {
        return array(text(COMMAND_DOMAIN), text(statement.chainId()), hash32(statement.generationId()),
                text(statement.target()), text(statement.authority()),
                statement.expected() == null ? nil() : uint(statement.expected()),
                hash32(statement.nonce()), uint(statement.notAfter()), operation(statement.operation()));
    }

    private static CommandStatement statement(DataItem item) {
        List<DataItem> fields = fields(item, 9);
        domain(fields.get(0), COMMAND_DOMAIN);
        return new CommandStatement(text(fields.get(1)), hex(fixed(fields.get(2), 32)), text(fields.get(3)),
                text(fields.get(4)), isNull(fields.get(5)) ? null : longValue(fields.get(5)),
                hex(fixed(fields.get(6), 16)), longValue(fields.get(7)), operation(fields.get(8)));
    }

    private static Array operation(Operation operation) {
        return switch (operation) {
            case Create create -> array(uint(0), keySet(create.owners()), policy(create.policy()),
                    optionalBytes(create.schemaIr()));
            case Update update -> array(uint(1), policy(update.policy()), optionalBytes(update.schemaIr()));
            case Rotate rotate -> array(uint(2), keySet(rotate.owners()));
            case Retire ignored -> array(uint(3));
            case Revoke revoke -> array(uint(4), delegation(revoke.delegation()));
        };
    }

    private static Operation operation(DataItem item) {
        List<DataItem> fields = list(item);
        if (fields.isEmpty()) {
            throw invalid("empty operation");
        }
        int op = intValue(fields.get(0));
        return switch (op) {
            case 0 -> {
                expectSize(fields, 4);
                yield new Create(keySet(fields.get(1)), policy(fields.get(2)), optionalBytes(fields.get(3)));
            }
            case 1 -> {
                expectSize(fields, 3);
                yield new Update(policy(fields.get(1)), optionalBytes(fields.get(2)));
            }
            case 2 -> {
                expectSize(fields, 2);
                yield new Rotate(keySet(fields.get(1)));
            }
            case 3 -> {
                expectSize(fields, 1);
                yield new Retire();
            }
            case 4 -> {
                expectSize(fields, 2);
                yield new Revoke(delegation(fields.get(1)));
            }
            default -> throw invalid("unknown registry operation " + op);
        };
    }

    private static Array governanceOperation(GovernanceOperation operation) {
        return switch (operation) {
            case GovCreate create -> array(uint(0), text(create.name()), keySet(create.owners()),
                    policy(create.policy()), optionalBytes(create.schemaIr()));
            case GovRecover recover -> array(uint(1), text(recover.name()), keySet(recover.owners()));
            case GovRetire retire -> array(uint(2), text(retire.name()));
            case GovConfigure configure -> array(uint(3), settings(configure.settings()));
        };
    }

    private static GovernanceOperation governanceOperation(DataItem item) {
        List<DataItem> fields = list(item);
        if (fields.isEmpty()) {
            throw invalid("empty governance operation");
        }
        int op = intValue(fields.get(0));
        return switch (op) {
            case 0 -> {
                expectSize(fields, 5);
                yield new GovCreate(text(fields.get(1)), keySet(fields.get(2)), policy(fields.get(3)),
                        optionalBytes(fields.get(4)));
            }
            case 1 -> {
                expectSize(fields, 3);
                yield new GovRecover(text(fields.get(1)), keySet(fields.get(2)));
            }
            case 2 -> {
                expectSize(fields, 2);
                yield new GovRetire(text(fields.get(1)));
            }
            case 3 -> {
                expectSize(fields, 2);
                yield new GovConfigure(settings(fields.get(1)));
            }
            default -> throw invalid("unknown governance operation " + op);
        };
    }

    private static Array delegation(Delegation delegation) {
        return array(uint(1), hash32(delegation.delegate()), text(delegation.scope()),
                uint(delegation.notBefore()), uint(delegation.notAfter()), hash32(delegation.issuer()),
                new ByteString(delegation.sig()));
    }

    private static Delegation delegation(DataItem item) {
        List<DataItem> fields = fields(item, 7);
        version(fields.get(0));
        return new Delegation(hex(fixed(fields.get(1), 32)), text(fields.get(2)), longValue(fields.get(3)),
                longValue(fields.get(4)), hex(fixed(fields.get(5), 32)), fixed(fields.get(6), 64));
    }

    private static Array signatures(List<Signature> signatures) {
        Array array = new Array();
        for (Signature signature : signatures) {
            array.add(array(hash32(signature.key()), new ByteString(signature.sig())));
        }
        return array;
    }

    private static List<Signature> signatures(DataItem item) {
        List<Signature> signatures = new ArrayList<>();
        for (DataItem entry : list(item)) {
            List<DataItem> pair = fields(entry, 2);
            signatures.add(new Signature(hex(fixed(pair.get(0), 32)), fixed(pair.get(1), 64)));
        }
        return signatures;
    }

    // ------------------------------------------------------------------ primitives

    private static <T> T decode(byte[] bytes, Function<DataItem, T> parse, Function<T, byte[]> encode) {
        if (!CborStructurePreflight.accepts(bytes, LIMITS)) {
            throw invalid("invalid bounded topic CBOR");
        }
        T value;
        try {
            value = parse.apply(CborSerializationUtil.deserializeOne(bytes));
        } catch (TopicWireException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new TopicWireException("malformed topic structure: " + e.getMessage(), e);
        }
        if (!Arrays.equals(bytes, encode.apply(value))) {
            throw invalid("topic structure is not canonically encoded");
        }
        return value;
    }

    private static byte[] bytes(DataItem item) {
        return CborSerializationUtil.serialize(item);
    }

    private static Array array(DataItem... items) {
        Array array = new Array();
        for (DataItem item : items) {
            array.add(item);
        }
        return array;
    }

    private static UnsignedInteger uint(long value) {
        if (value < 0) {
            throw new IllegalArgumentException("unsigned value must be nonnegative");
        }
        return new UnsignedInteger(value);
    }

    private static UnicodeString text(String value) {
        return new UnicodeString(value);
    }

    /** A fixed-size byte string from its hex (hashes, keys, nonces). */
    private static ByteString hash32(String hex) {
        return new ByteString(HexUtil.decodeHexString(hex));
    }

    private static DataItem optionalBytes(byte[] value) {
        return value == null ? nil() : new ByteString(value);
    }

    private static byte[] optionalBytes(DataItem item) {
        return isNull(item) ? null : byteString(item);
    }

    private static SimpleValue bool(boolean value) {
        return value ? SimpleValue.TRUE : SimpleValue.FALSE;
    }

    private static SimpleValue nil() {
        return SimpleValue.NULL;
    }

    private static List<DataItem> list(DataItem item) {
        untagged(item);
        if (!(item instanceof Array array)) {
            throw invalid("expected an array");
        }
        if (array.isChunked()) {
            throw invalid("indefinite-length arrays are not allowed");
        }
        return array.getDataItems();
    }

    private static List<DataItem> fields(DataItem item, int size) {
        List<DataItem> fields = list(item);
        expectSize(fields, size);
        return fields;
    }

    private static void expectSize(List<DataItem> fields, int size) {
        if (fields.size() != size) {
            throw invalid("expected " + size + " fields, found " + fields.size());
        }
    }

    private static void version(DataItem item) {
        if (longValue(item) != 1) {
            throw invalid("unsupported version");
        }
    }

    private static void domain(DataItem item, String expected) {
        if (!expected.equals(text(item))) {
            throw invalid("unexpected domain");
        }
    }

    private static long longValue(DataItem item) {
        untagged(item);
        if (!(item instanceof UnsignedInteger value)) {
            throw invalid("expected an unsigned integer");
        }
        BigInteger big = value.getValue();
        if (big.bitLength() > 63) {
            throw invalid("unsigned integer exceeds 2^63 - 1");
        }
        return big.longValue();
    }

    private static int intValue(DataItem item) {
        long value = longValue(item);
        if (value > Integer.MAX_VALUE) {
            throw invalid("integer out of range");
        }
        return (int) value;
    }

    private static String text(DataItem item) {
        untagged(item);
        if (!(item instanceof UnicodeString text)) {
            throw invalid("expected a text string");
        }
        return text.getString();
    }

    private static byte[] byteString(DataItem item) {
        untagged(item);
        if (!(item instanceof ByteString bytes)) {
            throw invalid("expected a byte string");
        }
        return bytes.getBytes();
    }

    private static byte[] fixed(DataItem item, int size) {
        byte[] bytes = byteString(item);
        if (bytes.length != size) {
            throw invalid("expected " + size + " bytes");
        }
        return bytes;
    }

    private static boolean bool(DataItem item) {
        untagged(item);
        if (item instanceof SimpleValue simple) {
            if (simple.getSimpleValueType() == SimpleValueType.TRUE) {
                return true;
            }
            if (simple.getSimpleValueType() == SimpleValueType.FALSE) {
                return false;
            }
        }
        throw invalid("expected a boolean");
    }

    private static boolean isNull(DataItem item) {
        untagged(item);
        return item instanceof SimpleValue simple && simple.getSimpleValueType() == SimpleValueType.NULL;
    }

    private static void untagged(DataItem item) {
        if (item == null || item.hasTag()) {
            throw invalid("tags are not allowed");
        }
    }

    private static String hex(byte[] bytes) {
        return HexUtil.encodeHexString(bytes);
    }

    private static byte[] requireRecordBound(byte[] bytes) {
        if (bytes == null || bytes.length > MAX_RECORD_BYTES) {
            throw invalid("record exceeds " + MAX_RECORD_BYTES + " bytes");
        }
        return bytes;
    }

    private static TopicWireException invalid(String message) {
        return new TopicWireException(message);
    }

    /** A topic structure that is malformed, out of bounds or not canonically encoded. */
    public static final class TopicWireException extends IllegalArgumentException {
        TopicWireException(String message) {
            super(message);
        }

        TopicWireException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
