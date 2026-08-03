package com.bloxbean.cardano.yano.appchain.stdlib.contracts;

import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.ByteString;
import co.nstant.in.cbor.model.UnicodeString;
import co.nstant.in.cbor.model.UnsignedInteger;
import com.bloxbean.cardano.client.crypto.Blake2bUtil;
import com.bloxbean.cardano.yano.appchain.stdlib.contracts.internal.StdlibContractCbor;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Frozen version-1 wire, genesis, key and entry contract for
 * {@code authenticated-map} (ADR-025 Phase 0).
 *
 * <p>All CBOR values are definite-length preferred serialization using arrays,
 * unsigned integers, byte strings and text only. Application values remain
 * opaque bytes: an application that promises canonical CBOR must enforce that
 * promise before constructing a command.</p>
 */
public final class AuthenticatedMapContract {
    public static final String STATE_MACHINE_ID = "authenticated-map";
    public static final int STATE_MACHINE_VERSION = 1;
    public static final String DEFAULT_TOPIC = "authenticated-map.command.v1";

    public static final String PROFILE_MPF_BLAKE2B256_V1 = "mpf-blake2b256-v1";
    public static final String PROFILE_JMT_BLAKE2B256_V1 = "jmt-blake2b256-v1";
    public static final String PROFILE_JMT_POSEIDON_BLS12381_V1 =
            "jmt-poseidon-bls12381-v1";
    public static final Set<String> PROFILE_IDS = Set.of(
            PROFILE_MPF_BLAKE2B256_V1,
            PROFILE_JMT_BLAKE2B256_V1,
            PROFILE_JMT_POSEIDON_BLS12381_V1);

    public static final int KEY_CODEC_VERSION = 1;
    public static final int NAMESPACE_KIND_AUTHENTICATED_MAP = 1;
    public static final int MAX_COLLECTIONS = 64;
    public static final int MAX_COLLECTION_ID_BYTES = 64;
    public static final int MAX_APPLICATION_KEY_BYTES = 128;
    public static final int MAX_VALUE_BYTES = 1_048_576;
    public static final int MAX_BATCH_ITEMS = 128;
    public static final int MAX_BATCH_BYTES = 1_048_576;

    public static final int AUTH_OPEN = 0;
    public static final int AUTH_OWNER = 1;
    public static final int AUTH_MEMBER = 2;

    public static final int STATUS_ACTIVE = 0;
    public static final int STATUS_REVOKED = 1;

    public static final int OP_PUT = 0;
    public static final int OP_PUT_IF_ABSENT = 1;
    public static final int OP_COMPARE_AND_SET = 2;
    public static final int OP_TRANSFER_CONTROLLER = 3;
    public static final int OP_REVOKE = 4;
    public static final int OP_RESTORE = 5;

    private static final int COMMAND_SINGLE = 0;
    private static final int COMMAND_BATCH = 1;
    private static final Pattern COLLECTION_ID =
            Pattern.compile("[a-z0-9][a-z0-9._-]{0,63}");
    private static final byte[] VALUE_HASH_DOMAIN =
            "yano-authenticated-map-value-v1\0".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] BATCH_HASH_DOMAIN =
            "yano-authenticated-map-batch-v1\0".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] GENESIS_HASH_DOMAIN =
            "yano-appchain-genesis-v1\0".getBytes(StandardCharsets.US_ASCII);

    private AuthenticatedMapContract() {
    }

    /**
     * Canonical backend key:
     * {@code version:u8 || namespace:u8 || collectionLen:u16 || collection || keyLen:u32 || key}.
     */
    public static byte[] canonicalKey(String collectionId, byte[] applicationKey) {
        String collection = requireCollectionId(collectionId);
        byte[] collectionBytes = collection.getBytes(StandardCharsets.US_ASCII);
        byte[] key = requireApplicationKey(applicationKey, MAX_APPLICATION_KEY_BYTES);
        return ByteBuffer.allocate(2 + Short.BYTES + collectionBytes.length
                        + Integer.BYTES + key.length)
                .put((byte) KEY_CODEC_VERSION)
                .put((byte) NAMESPACE_KIND_AUTHENTICATED_MAP)
                .putShort((short) collectionBytes.length)
                .put(collectionBytes)
                .putInt(key.length)
                .put(key)
                .array();
    }

    public static CanonicalKey decodeCanonicalKey(byte[] canonical) {
        byte[] bytes = Objects.requireNonNull(canonical, "canonical").clone();
        try {
            ByteBuffer input = ByteBuffer.wrap(bytes);
            if (Byte.toUnsignedInt(input.get()) != KEY_CODEC_VERSION
                    || Byte.toUnsignedInt(input.get()) != NAMESPACE_KIND_AUTHENTICATED_MAP) {
                throw malformed();
            }
            int collectionLength = Short.toUnsignedInt(input.getShort());
            if (collectionLength == 0 || collectionLength > MAX_COLLECTION_ID_BYTES
                    || input.remaining() < collectionLength + Integer.BYTES) {
                throw malformed();
            }
            byte[] collectionBytes = new byte[collectionLength];
            input.get(collectionBytes);
            String collection = requireCollectionId(
                    new String(collectionBytes, StandardCharsets.US_ASCII));
            int keyLength = input.getInt();
            if (keyLength <= 0 || keyLength > MAX_APPLICATION_KEY_BYTES
                    || input.remaining() != keyLength) {
                throw malformed();
            }
            byte[] key = new byte[keyLength];
            input.get(key);
            if (!Arrays.equals(bytes, canonicalKey(collection, key))) {
                throw malformed();
            }
            return new CanonicalKey(collection, key);
        } catch (java.nio.BufferUnderflowException failure) {
            throw malformed();
        }
    }

    public static byte[] logicalValueHash(byte[] value) {
        byte[] bounded = requireValue(value, MAX_VALUE_BYTES);
        ByteBuffer input = ByteBuffer.allocate(VALUE_HASH_DOMAIN.length + Integer.BYTES
                + bounded.length);
        input.put(VALUE_HASH_DOMAIN).putInt(bounded.length).put(bounded);
        return Blake2bUtil.blake2bHash256(input.array());
    }

    public static byte[] encodeCommand(Command command) {
        Objects.requireNonNull(command, "command");
        Array mutations = new Array();
        for (Mutation mutation : command.mutations()) {
            mutations.add(encodeMutation(mutation));
        }
        Array root = new Array();
        root.add(new UnsignedInteger(STATE_MACHINE_VERSION));
        root.add(new UnsignedInteger(command.batch() ? COMMAND_BATCH : COMMAND_SINGLE));
        root.add(mutations);
        byte[] encoded = StdlibContractCbor.encode(root);
        if (encoded.length > MAX_BATCH_BYTES) {
            throw new IllegalArgumentException("authenticated-map command exceeds maximum bytes");
        }
        return encoded;
    }

    public static Command decodeCommand(byte[] encoded) {
        List<co.nstant.in.cbor.model.DataItem> fields =
                StdlibContractCbor.decodeArray(encoded, 3).getDataItems();
        if (StdlibContractCbor.uintInt(fields.get(0)) != STATE_MACHINE_VERSION) {
            throw malformed();
        }
        int kind = StdlibContractCbor.uintInt(fields.get(1));
        if (kind != COMMAND_SINGLE && kind != COMMAND_BATCH) {
            throw malformed();
        }
        Array mutationItems = StdlibContractCbor.array(fields.get(2), MAX_BATCH_ITEMS);
        if (mutationItems.getDataItems().isEmpty()
                || kind == COMMAND_SINGLE && mutationItems.getDataItems().size() != 1) {
            throw malformed();
        }
        List<Mutation> mutations = new ArrayList<>(mutationItems.getDataItems().size());
        for (co.nstant.in.cbor.model.DataItem item : mutationItems.getDataItems()) {
            Array mutation = StdlibContractCbor.array(item, 7);
            if (mutation.getDataItems().size() != 7) {
                throw malformed();
            }
            List<co.nstant.in.cbor.model.DataItem> values = mutation.getDataItems();
            mutations.add(new Mutation(
                    StdlibContractCbor.uintInt(values.get(0)),
                    StdlibContractCbor.text(values.get(1)),
                    StdlibContractCbor.bytes(values.get(2)),
                    StdlibContractCbor.bytes(values.get(3)),
                    StdlibContractCbor.uint(values.get(4)),
                    StdlibContractCbor.bytes(values.get(5)),
                    StdlibContractCbor.bytes(values.get(6))));
        }
        Command decoded = new Command(kind == COMMAND_BATCH, mutations);
        if (!Arrays.equals(encoded, encodeCommand(decoded))) {
            throw malformed();
        }
        return decoded;
    }

    public static byte[] batchCommitment(Command command) {
        byte[] encoded = encodeCommand(command);
        byte[] input = new byte[BATCH_HASH_DOMAIN.length + encoded.length];
        System.arraycopy(BATCH_HASH_DOMAIN, 0, input, 0, BATCH_HASH_DOMAIN.length);
        System.arraycopy(encoded, 0, input, BATCH_HASH_DOMAIN.length, encoded.length);
        return Blake2bUtil.blake2bHash256(input);
    }

    public static byte[] encodeEntry(Entry entry) {
        Objects.requireNonNull(entry, "entry");
        Array root = new Array();
        root.add(new UnsignedInteger(STATE_MACHINE_VERSION));
        root.add(new UnsignedInteger(entry.status()));
        root.add(new UnsignedInteger(entry.revision()));
        root.add(new ByteString(entry.controller()));
        root.add(new ByteString(entry.value()));
        root.add(new ByteString(entry.logicalValueHash()));
        root.add(new UnsignedInteger(entry.createdHeight()));
        root.add(new UnsignedInteger(entry.lastMutationHeight()));
        return StdlibContractCbor.encode(root);
    }

    public static Entry decodeEntry(byte[] encoded) {
        List<co.nstant.in.cbor.model.DataItem> values =
                StdlibContractCbor.decodeArray(encoded, 8).getDataItems();
        if (StdlibContractCbor.uintInt(values.get(0)) != STATE_MACHINE_VERSION) {
            throw malformed();
        }
        Entry decoded = new Entry(
                StdlibContractCbor.uintInt(values.get(1)),
                StdlibContractCbor.uint(values.get(2)),
                StdlibContractCbor.bytes(values.get(3)),
                StdlibContractCbor.bytes(values.get(4)),
                StdlibContractCbor.bytes(values.get(5)),
                StdlibContractCbor.uint(values.get(6)),
                StdlibContractCbor.uint(values.get(7)));
        if (!Arrays.equals(encoded, encodeEntry(decoded))) {
            throw malformed();
        }
        return decoded;
    }

    public static byte[] encodeGenesis(Genesis genesis) {
        Objects.requireNonNull(genesis, "genesis");
        Array collections = new Array();
        for (CollectionDescriptor descriptor : genesis.collections()) {
            collections.add(encodeCollection(descriptor));
        }
        Array initialEntries = new Array();
        for (GenesisEntry entry : genesis.initialEntries()) {
            Array item = new Array();
            item.add(new UnicodeString(entry.collectionId()));
            item.add(new ByteString(entry.applicationKey()));
            item.add(new ByteString(entry.controller()));
            item.add(new ByteString(entry.value()));
            initialEntries.add(item);
        }
        Array root = new Array();
        root.add(new UnsignedInteger(STATE_MACHINE_VERSION));
        root.add(new UnicodeString(genesis.chainId()));
        root.add(new UnicodeString(STATE_MACHINE_ID));
        root.add(new UnsignedInteger(STATE_MACHINE_VERSION));
        root.add(new UnicodeString(genesis.commitmentProfileId()));
        root.add(new ByteString(genesis.formatFingerprint()));
        root.add(new ByteString(genesis.frameworkConsensusProfileDigest()));
        root.add(new ByteString(genesis.membershipCommitment()));
        root.add(new ByteString(genesis.anchorPolicyCommitment()));
        root.add(new UnsignedInteger(genesis.maxBatchItems()));
        root.add(new UnsignedInteger(genesis.maxBatchBytes()));
        root.add(collections);
        root.add(initialEntries);
        return StdlibContractCbor.encode(root);
    }

    public static Genesis decodeGenesis(byte[] encoded) {
        List<co.nstant.in.cbor.model.DataItem> values =
                StdlibContractCbor.decodeArray(encoded, 13).getDataItems();
        if (StdlibContractCbor.uintInt(values.get(0)) != STATE_MACHINE_VERSION
                || !STATE_MACHINE_ID.equals(StdlibContractCbor.text(values.get(2)))
                || StdlibContractCbor.uintInt(values.get(3)) != STATE_MACHINE_VERSION) {
            throw malformed();
        }
        Array collectionItems = StdlibContractCbor.array(values.get(11), MAX_COLLECTIONS);
        List<CollectionDescriptor> collections = new ArrayList<>(collectionItems.getDataItems().size());
        for (co.nstant.in.cbor.model.DataItem item : collectionItems.getDataItems()) {
            collections.add(decodeCollection(StdlibContractCbor.array(item, 6)));
        }
        Array entryItems = StdlibContractCbor.array(values.get(12), MAX_BATCH_ITEMS);
        List<GenesisEntry> entries = new ArrayList<>(entryItems.getDataItems().size());
        for (co.nstant.in.cbor.model.DataItem item : entryItems.getDataItems()) {
            Array entry = StdlibContractCbor.array(item, 4);
            if (entry.getDataItems().size() != 4) {
                throw malformed();
            }
            List<co.nstant.in.cbor.model.DataItem> fields = entry.getDataItems();
            entries.add(new GenesisEntry(
                    StdlibContractCbor.text(fields.get(0)),
                    StdlibContractCbor.bytes(fields.get(1)),
                    StdlibContractCbor.bytes(fields.get(2)),
                    StdlibContractCbor.bytes(fields.get(3))));
        }
        Genesis decoded = new Genesis(
                StdlibContractCbor.text(values.get(1)),
                StdlibContractCbor.text(values.get(4)),
                StdlibContractCbor.bytes(values.get(5)),
                StdlibContractCbor.bytes(values.get(6)),
                StdlibContractCbor.bytes(values.get(7)),
                StdlibContractCbor.bytes(values.get(8)),
                StdlibContractCbor.uintInt(values.get(9)),
                StdlibContractCbor.uintInt(values.get(10)),
                collections,
                entries);
        if (!Arrays.equals(encoded, encodeGenesis(decoded))) {
            throw malformed();
        }
        return decoded;
    }

    public static byte[] genesisId(Genesis genesis) {
        byte[] encoded = encodeGenesis(genesis);
        byte[] input = new byte[GENESIS_HASH_DOMAIN.length + encoded.length];
        System.arraycopy(GENESIS_HASH_DOMAIN, 0, input, 0, GENESIS_HASH_DOMAIN.length);
        System.arraycopy(encoded, 0, input, GENESIS_HASH_DOMAIN.length, encoded.length);
        return Blake2bUtil.blake2bHash256(input);
    }

    private static Array encodeMutation(Mutation mutation) {
        Array item = new Array();
        item.add(new UnsignedInteger(mutation.operation()));
        item.add(new UnicodeString(mutation.collectionId()));
        item.add(new ByteString(mutation.applicationKey()));
        item.add(new ByteString(mutation.value()));
        item.add(new UnsignedInteger(mutation.expectedRevision()));
        item.add(new ByteString(mutation.expectedValueHash()));
        item.add(new ByteString(mutation.newController()));
        return item;
    }

    private static Array encodeCollection(CollectionDescriptor descriptor) {
        Array item = new Array();
        item.add(new UnsignedInteger(STATE_MACHINE_VERSION));
        item.add(new UnicodeString(descriptor.id()));
        item.add(new UnsignedInteger(descriptor.authorization()));
        item.add(new UnsignedInteger(descriptor.restoreAllowed() ? 1 : 0));
        item.add(new UnsignedInteger(descriptor.maxKeyBytes()));
        item.add(new UnsignedInteger(descriptor.maxValueBytes()));
        return item;
    }

    private static CollectionDescriptor decodeCollection(Array item) {
        if (item.getDataItems().size() != 6) {
            throw malformed();
        }
        List<co.nstant.in.cbor.model.DataItem> values = item.getDataItems();
        if (StdlibContractCbor.uintInt(values.get(0)) != STATE_MACHINE_VERSION) {
            throw malformed();
        }
        int restore = StdlibContractCbor.uintInt(values.get(3));
        if (restore > 1) {
            throw malformed();
        }
        return new CollectionDescriptor(
                StdlibContractCbor.text(values.get(1)),
                StdlibContractCbor.uintInt(values.get(2)),
                restore == 1,
                StdlibContractCbor.uintInt(values.get(4)),
                StdlibContractCbor.uintInt(values.get(5)));
    }

    private static String requireCollectionId(String id) {
        String value = Objects.requireNonNull(id, "collectionId");
        if (!COLLECTION_ID.matcher(value).matches()
                || value.getBytes(StandardCharsets.US_ASCII).length > MAX_COLLECTION_ID_BYTES) {
            throw new IllegalArgumentException("collectionId must be canonical lowercase ASCII");
        }
        return value;
    }

    private static byte[] requireApplicationKey(byte[] key, int maximum) {
        if (key == null || key.length == 0 || key.length > maximum) {
            throw new IllegalArgumentException("applicationKey must contain 1-" + maximum + " bytes");
        }
        return key.clone();
    }

    private static byte[] requireValue(byte[] value, int maximum) {
        if (value == null || value.length > maximum) {
            throw new IllegalArgumentException("value must contain at most " + maximum + " bytes");
        }
        return value.clone();
    }

    private static byte[] requireOptional32(byte[] value, String name) {
        byte[] bytes = Objects.requireNonNull(value, name).clone();
        if (bytes.length != 0 && bytes.length != 32) {
            throw new IllegalArgumentException(name + " must be empty or 32 bytes");
        }
        return bytes;
    }

    private static byte[] require32(byte[] value, String name) {
        byte[] bytes = Objects.requireNonNull(value, name).clone();
        if (bytes.length != 32) {
            throw new IllegalArgumentException(name + " must contain 32 bytes");
        }
        return bytes;
    }

    private static IllegalArgumentException malformed() {
        return new IllegalArgumentException("invalid canonical authenticated-map v1 value");
    }

    private static int compareUnsigned(byte[] left, byte[] right) {
        return Arrays.compareUnsigned(left, right);
    }

    public record CanonicalKey(String collectionId, byte[] applicationKey) {
        public CanonicalKey {
            collectionId = requireCollectionId(collectionId);
            applicationKey = requireApplicationKey(applicationKey, MAX_APPLICATION_KEY_BYTES);
        }

        @Override
        public byte[] applicationKey() {
            return applicationKey.clone();
        }
    }

    public record CollectionDescriptor(
            String id,
            int authorization,
            boolean restoreAllowed,
            int maxKeyBytes,
            int maxValueBytes
    ) {
        public CollectionDescriptor {
            id = requireCollectionId(id);
            if (authorization < AUTH_OPEN || authorization > AUTH_MEMBER) {
                throw new IllegalArgumentException("unsupported authenticated-map authorization policy");
            }
            if (maxKeyBytes <= 0 || maxKeyBytes > MAX_APPLICATION_KEY_BYTES) {
                throw new IllegalArgumentException("maxKeyBytes is outside the v1 contract");
            }
            if (maxValueBytes < 0 || maxValueBytes > MAX_VALUE_BYTES) {
                throw new IllegalArgumentException("maxValueBytes is outside the v1 contract");
            }
        }
    }

    public record Mutation(
            int operation,
            String collectionId,
            byte[] applicationKey,
            byte[] value,
            long expectedRevision,
            byte[] expectedValueHash,
            byte[] newController
    ) {
        public Mutation {
            collectionId = requireCollectionId(collectionId);
            applicationKey = requireApplicationKey(applicationKey, MAX_APPLICATION_KEY_BYTES);
            value = requireValue(value, MAX_VALUE_BYTES);
            expectedValueHash = requireOptional32(expectedValueHash, "expectedValueHash");
            newController = requireOptional32(newController, "newController");
            if (expectedRevision < 0) {
                throw new IllegalArgumentException("expectedRevision must be nonnegative");
            }
            switch (operation) {
                case OP_PUT, OP_PUT_IF_ABSENT, OP_RESTORE -> {
                    if (expectedRevision != 0 || expectedValueHash.length != 0
                            || newController.length != 0) {
                        throw new IllegalArgumentException("operation contains unsupported precondition fields");
                    }
                }
                case OP_COMPARE_AND_SET -> {
                    if (expectedRevision == 0 && expectedValueHash.length == 0
                            || newController.length != 0) {
                        throw new IllegalArgumentException("compare-and-set requires a revision or value hash");
                    }
                }
                case OP_TRANSFER_CONTROLLER -> {
                    if (value.length != 0 || newController.length != 32) {
                        throw new IllegalArgumentException("controller transfer requires exactly one controller");
                    }
                }
                case OP_REVOKE -> {
                    if (value.length != 0 || newController.length != 0) {
                        throw new IllegalArgumentException("revoke cannot contain a value or new controller");
                    }
                }
                default -> throw new IllegalArgumentException("unsupported authenticated-map operation");
            }
        }

        @Override public byte[] applicationKey() { return applicationKey.clone(); }
        @Override public byte[] value() { return value.clone(); }
        @Override public byte[] expectedValueHash() { return expectedValueHash.clone(); }
        @Override public byte[] newController() { return newController.clone(); }

        public static Mutation put(String collection, byte[] key, byte[] value) {
            return new Mutation(OP_PUT, collection, key, value, 0, new byte[0], new byte[0]);
        }

        public static Mutation putIfAbsent(String collection, byte[] key, byte[] value) {
            return new Mutation(OP_PUT_IF_ABSENT, collection, key, value,
                    0, new byte[0], new byte[0]);
        }

        public static Mutation compareAndSet(String collection, byte[] key, byte[] value,
                                             long revision, byte[] valueHash) {
            return new Mutation(OP_COMPARE_AND_SET, collection, key, value,
                    revision, valueHash == null ? new byte[0] : valueHash, new byte[0]);
        }

        public static Mutation transferController(String collection, byte[] key,
                                                   byte[] controller, long revision) {
            return new Mutation(OP_TRANSFER_CONTROLLER, collection, key, new byte[0],
                    revision, new byte[0], controller);
        }

        public static Mutation revoke(String collection, byte[] key, long revision,
                                      byte[] valueHash) {
            return new Mutation(OP_REVOKE, collection, key, new byte[0], revision,
                    valueHash == null ? new byte[0] : valueHash, new byte[0]);
        }

        public static Mutation restore(String collection, byte[] key, byte[] value) {
            return new Mutation(OP_RESTORE, collection, key, value,
                    0, new byte[0], new byte[0]);
        }
    }

    public record Command(boolean batch, List<Mutation> mutations) {
        public Command {
            List<Mutation> copy = List.copyOf(Objects.requireNonNull(mutations, "mutations"));
            if (copy.isEmpty() || copy.size() > MAX_BATCH_ITEMS || !batch && copy.size() != 1) {
                throw new IllegalArgumentException("authenticated-map command has invalid item count");
            }
            Set<String> keys = new HashSet<>();
            for (Mutation mutation : copy) {
                String identity = mutation.collectionId() + ":"
                        + HexFormat.of().formatHex(mutation.applicationKey());
                if (!keys.add(identity)) {
                    throw new IllegalArgumentException("batch contains duplicate collection/key");
                }
            }
            mutations = copy;
        }

        public static Command single(Mutation mutation) {
            return new Command(false, List.of(mutation));
        }

        public static Command batch(List<Mutation> mutations) {
            return new Command(true, mutations);
        }
    }

    public record Entry(
            int status,
            long revision,
            byte[] controller,
            byte[] value,
            byte[] logicalValueHash,
            long createdHeight,
            long lastMutationHeight
    ) {
        public Entry {
            if (status != STATUS_ACTIVE && status != STATUS_REVOKED) {
                throw new IllegalArgumentException("entry status is unsupported");
            }
            if (revision <= 0 || createdHeight < 0 || lastMutationHeight < createdHeight) {
                throw new IllegalArgumentException("entry revision/heights are invalid");
            }
            controller = requireOptional32(controller, "controller");
            value = requireValue(value, MAX_VALUE_BYTES);
            logicalValueHash = require32(logicalValueHash, "logicalValueHash");
            if (status == STATUS_ACTIVE
                    && !Arrays.equals(logicalValueHash,
                    AuthenticatedMapContract.logicalValueHash(value))) {
                throw new IllegalArgumentException("active entry value hash does not match value");
            }
            if (status == STATUS_REVOKED && value.length != 0) {
                throw new IllegalArgumentException("revoked entry must contain a canonical empty tombstone value");
            }
        }

        @Override public byte[] controller() { return controller.clone(); }
        @Override public byte[] value() { return value.clone(); }
        @Override public byte[] logicalValueHash() { return logicalValueHash.clone(); }

        public static Entry active(long revision, byte[] controller, byte[] value,
                                   long createdHeight, long lastMutationHeight) {
            return new Entry(STATUS_ACTIVE, revision, controller, value,
                    AuthenticatedMapContract.logicalValueHash(value),
                    createdHeight, lastMutationHeight);
        }

        public Entry revoked(long height) {
            return new Entry(STATUS_REVOKED, Math.addExact(revision, 1), controller,
                    new byte[0], logicalValueHash, createdHeight, height);
        }
    }

    public record GenesisEntry(
            String collectionId,
            byte[] applicationKey,
            byte[] controller,
            byte[] value
    ) {
        public GenesisEntry {
            collectionId = requireCollectionId(collectionId);
            applicationKey = requireApplicationKey(applicationKey, MAX_APPLICATION_KEY_BYTES);
            controller = requireOptional32(controller, "controller");
            value = requireValue(value, MAX_VALUE_BYTES);
        }

        @Override public byte[] applicationKey() { return applicationKey.clone(); }
        @Override public byte[] controller() { return controller.clone(); }
        @Override public byte[] value() { return value.clone(); }
    }

    public record Genesis(
            String chainId,
            String commitmentProfileId,
            byte[] formatFingerprint,
            byte[] frameworkConsensusProfileDigest,
            byte[] membershipCommitment,
            byte[] anchorPolicyCommitment,
            int maxBatchItems,
            int maxBatchBytes,
            List<CollectionDescriptor> collections,
            List<GenesisEntry> initialEntries
    ) {
        public Genesis {
            if (chainId == null || chainId.isBlank()
                    || chainId.indexOf('\0') >= 0
                    || chainId.getBytes(StandardCharsets.UTF_8).length > 128) {
                throw new IllegalArgumentException("chainId must contain 1-128 non-NUL UTF-8 bytes");
            }
            if (!PROFILE_IDS.contains(commitmentProfileId)) {
                throw new IllegalArgumentException("unsupported commitment profile id");
            }
            formatFingerprint = require32(formatFingerprint, "formatFingerprint");
            frameworkConsensusProfileDigest = require32(
                    frameworkConsensusProfileDigest, "frameworkConsensusProfileDigest");
            membershipCommitment = require32(membershipCommitment, "membershipCommitment");
            anchorPolicyCommitment = require32(anchorPolicyCommitment, "anchorPolicyCommitment");
            if (maxBatchItems <= 0 || maxBatchItems > MAX_BATCH_ITEMS
                    || maxBatchBytes <= 0 || maxBatchBytes > MAX_BATCH_BYTES) {
                throw new IllegalArgumentException("genesis batch limits are outside v1 bounds");
            }

            List<CollectionDescriptor> collectionCopy = new ArrayList<>(
                    Objects.requireNonNull(collections, "collections"));
            if (collectionCopy.isEmpty() || collectionCopy.size() > MAX_COLLECTIONS) {
                throw new IllegalArgumentException("genesis must declare 1-" + MAX_COLLECTIONS
                        + " collections");
            }
            collectionCopy.sort(Comparator.comparing(CollectionDescriptor::id));
            Map<String, CollectionDescriptor> descriptors = new HashMap<>();
            for (CollectionDescriptor descriptor : collectionCopy) {
                if (descriptors.put(descriptor.id(), descriptor) != null) {
                    throw new IllegalArgumentException("duplicate collection id: " + descriptor.id());
                }
            }

            List<GenesisEntry> entryCopy = new ArrayList<>(
                    Objects.requireNonNull(initialEntries, "initialEntries"));
            entryCopy.sort(Comparator.comparing(GenesisEntry::collectionId)
                    .thenComparing(GenesisEntry::applicationKey,
                            AuthenticatedMapContract::compareUnsigned));
            Set<String> entryKeys = new HashSet<>();
            for (GenesisEntry entry : entryCopy) {
                CollectionDescriptor descriptor = descriptors.get(entry.collectionId());
                if (descriptor == null) {
                    throw new IllegalArgumentException("initial entry references unknown collection");
                }
                if (entry.applicationKey().length > descriptor.maxKeyBytes()
                        || entry.value().length > descriptor.maxValueBytes()) {
                    throw new IllegalArgumentException("initial entry exceeds collection bounds");
                }
                if (descriptor.authorization() == AUTH_OWNER
                        ? entry.controller().length != 32 : entry.controller().length != 0) {
                    throw new IllegalArgumentException("initial entry controller does not match policy");
                }
                String identity = entry.collectionId() + ":"
                        + HexFormat.of().formatHex(entry.applicationKey());
                if (!entryKeys.add(identity)) {
                    throw new IllegalArgumentException("duplicate initial collection/key");
                }
            }
            collections = List.copyOf(collectionCopy);
            initialEntries = List.copyOf(entryCopy);
        }

        @Override public byte[] formatFingerprint() { return formatFingerprint.clone(); }
        @Override public byte[] frameworkConsensusProfileDigest() {
            return frameworkConsensusProfileDigest.clone();
        }
        @Override public byte[] membershipCommitment() { return membershipCommitment.clone(); }
        @Override public byte[] anchorPolicyCommitment() { return anchorPolicyCommitment.clone(); }
    }
}
