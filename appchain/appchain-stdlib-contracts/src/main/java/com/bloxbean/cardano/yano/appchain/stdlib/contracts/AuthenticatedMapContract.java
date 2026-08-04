package com.bloxbean.cardano.yano.appchain.stdlib.contracts;

import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.ByteString;
import co.nstant.in.cbor.model.UnicodeString;
import co.nstant.in.cbor.model.UnsignedInteger;
import com.bloxbean.cardano.client.crypto.Blake2bUtil;
import com.bloxbean.cardano.yano.appchain.roles.contracts.GovernedGenesisV1;
import com.bloxbean.cardano.yano.appchain.roles.contracts.RoleWorkflowIdentifiers;
import com.bloxbean.cardano.yano.appchain.stdlib.contracts.internal.CanonicalValueCbor;
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
 * <p>All contract CBOR values are definite-length preferred serialization.
 * Application values remain opaque by default; a collection may instead bind
 * canonical-CBOR enforcement into genesis.</p>
 */
public final class AuthenticatedMapContract {
    public static final String STATE_MACHINE_ID = "authenticated-map";
    public static final int STATE_MACHINE_VERSION = 1;
    public static final int GENESIS_CODEC_VERSION = 4;
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
    public static final int NAMESPACE_KIND_FRAMEWORK = 0;
    public static final int NAMESPACE_KIND_AUTHENTICATED_MAP = 1;
    public static final int MAX_COLLECTIONS = 64;
    public static final int MAX_COLLECTION_ID_BYTES = 64;
    public static final int MAX_APPLICATION_KEY_BYTES = 128;
    public static final int MAX_VALUE_BYTES = 1_048_576;
    public static final int MAX_BATCH_ITEMS = 128;
    public static final int MAX_BATCH_BYTES = 1_048_576;
    public static final int MAX_VALIDATORS = 32;
    public static final int MAX_VALIDATOR_PARAMETERS_BYTES = 16_384;

    public static final int AUTH_OPEN = 0;
    public static final int AUTH_OWNER = 1;
    public static final int AUTH_MEMBER = 2;
    public static final int AUTH_GOVERNED_ROLE = 3;
    public static final int AUTH_APPROVAL = 4;

    public static final int VALUE_ENCODING_OPAQUE = 0;
    public static final int VALUE_ENCODING_CANONICAL_CBOR = 1;
    public static final int MAX_VALUE_CBOR_DEPTH = CanonicalValueCbor.MAX_DEPTH;
    public static final int MAX_VALUE_CBOR_ITEMS = CanonicalValueCbor.MAX_ITEMS;
    public static final int MAX_VALUE_CBOR_CONTAINER_ITEMS =
            CanonicalValueCbor.MAX_CONTAINER_ITEMS;

    public static final int VALIDATOR_KIND_SCHEMA = 0;
    public static final int VALIDATOR_KIND_PLUGIN = 1;
    public static final String VALIDATOR_SPI_CONTRACT_VERSION =
            "authenticated-map-validator-v1";

    public static final int STATUS_ACTIVE = 0;
    public static final int STATUS_REVOKED = 1;

    public static final int OP_PUT = 0;
    public static final int OP_PUT_IF_ABSENT = 1;
    public static final int OP_COMPARE_AND_SET = 2;
    public static final int OP_TRANSFER_CONTROLLER = 3;
    public static final int OP_REVOKE = 4;
    public static final int OP_RESTORE = 5;

    public static final String POINT_QUERY_PATH = "authenticated-map/entry-v1";
    public static final String RECEIPT_QUERY_PATH = "authenticated-map/receipt-v1";
    public static final String DIRECT_CONSUMPTION_QUERY_PATH =
            "authenticated-map/direct-consumption-v1";
    public static final String APPROVAL_CONSUMPTION_QUERY_PATH =
            "authenticated-map/approval-consumption-v1";
    public static final String CAPABILITIES_QUERY_PATH =
            "authenticated-map/capabilities-v1";

    public static final int PRESENCE_ABSENT = 0;
    public static final int PRESENCE_ACTIVE = 1;
    public static final int PRESENCE_REVOKED = 2;

    public static final int RECEIPT_APPLIED = 0;
    public static final int RECEIPT_REJECTED = 1;
    public static final int RECEIPT_ABSENT = 0;
    public static final int RECEIPT_PRESENT = 1;

    public static final int ERROR_NONE = 0;
    public static final int ERROR_UNKNOWN_COLLECTION = 1;
    public static final int ERROR_COLLECTION_BOUNDS = 2;
    public static final int ERROR_UNAUTHORIZED = 3;
    public static final int ERROR_ALREADY_EXISTS = 4;
    public static final int ERROR_ABSENT = 5;
    public static final int ERROR_REVOKED = 6;
    public static final int ERROR_ACTIVE = 7;
    public static final int ERROR_PRECONDITION = 8;
    public static final int ERROR_RESTORE_FORBIDDEN = 9;
    public static final int ERROR_VALUE_ENCODING = 10;
    public static final int ERROR_VALUE_SCHEMA = 11;
    public static final int ERROR_VALUE_VALIDATOR = 12;
    public static final int ERROR_AUTHORIZATION_ASSIGNMENT = 13;
    public static final int ERROR_UNKNOWN_POLICY = 14;
    public static final int ERROR_POLICY_INACTIVE = 15;
    public static final int ERROR_ACTOR_INELIGIBLE = 16;
    public static final int ERROR_ACTOR_SIGNATURE = 17;
    public static final int ERROR_AUTHORIZATION_DEADLINE = 18;
    public static final int ERROR_DIRECT_AUTHORIZATION_REPLAY = 19;
    public static final int ERROR_APPROVAL_NOT_APPROVED = 20;
    public static final int ERROR_APPROVAL_MISMATCH = 21;
    public static final int ERROR_APPROVAL_REPLAY = 22;
    public static final int ERROR_CAPACITY_EXCEEDED = 23;
    public static final int ERROR_CRYPTO_WORK_EXCEEDED = 24;
    public static final int ERROR_GOVERNED_ROUTE_UNSUPPORTED = 25;
    public static final int ERROR_WRONG_GENESIS = 26;
    public static final int ERROR_WRONG_REVISION = 27;

    private static final String INTERNAL_GENESIS_COLLECTION =
            "yano-authenticated-map-internal-v1";
    private static final String INTERNAL_RECEIPTS_COLLECTION =
            "yano-authenticated-map-receipts-v1";
    private static final String INTERNAL_DIRECT_CONSUMPTION_COLLECTION =
            "yano-authenticated-map-direct-consumption-v1";
    private static final String INTERNAL_APPROVAL_CONSUMPTION_COLLECTION =
            "yano-authenticated-map-approval-consumption-v1";
    private static final byte[] GENESIS_MARKER_APPLICATION_KEY =
            "genesis".getBytes(StandardCharsets.US_ASCII);

    private static final int COMMAND_SINGLE = 0;
    private static final int COMMAND_BATCH = 1;
    private static final Pattern COLLECTION_ID =
            Pattern.compile("[a-z0-9][a-z0-9._-]{0,63}");
    private static final Pattern VALIDATOR_ID =
            Pattern.compile("[a-z0-9][a-z0-9._-]{0,63}");
    private static final byte[] VALUE_HASH_DOMAIN =
            "yano-authenticated-map-value-v1\0".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] BATCH_HASH_DOMAIN =
            "yano-authenticated-map-batch-v1\0".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] GENESIS_HASH_DOMAIN =
            "yano-appchain-genesis-v1\0".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] RESULT_HASH_DOMAIN =
            "yano-authenticated-map-result-v1\0".getBytes(StandardCharsets.US_ASCII);

    private AuthenticatedMapContract() {
    }

    /**
     * Canonical backend key:
     * {@code version:u8 || namespace:u8 || collectionLen:u16 || collection || keyLen:u32 || key}.
     */
    public static byte[] canonicalKey(String collectionId, byte[] applicationKey) {
        return encodeCanonicalKey(
                NAMESPACE_KIND_AUTHENTICATED_MAP, collectionId, applicationKey);
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

    /** True when one bounded application value satisfies the selected encoding. */
    public static boolean valueEncodingAccepts(
            int valueEncoding,
            byte[] value,
            int maximumBytes
    ) {
        if (value == null || maximumBytes < 0 || value.length > maximumBytes) {
            return false;
        }
        return switch (valueEncoding) {
            case VALUE_ENCODING_OPAQUE -> true;
            case VALUE_ENCODING_CANONICAL_CBOR ->
                    CanonicalValueCbor.accepts(value, maximumBytes);
            default -> false;
        };
    }

    /** Consensus-state key holding the canonical genesis identity. */
    public static byte[] genesisMarkerKey() {
        return encodeCanonicalKey(NAMESPACE_KIND_FRAMEWORK,
                INTERNAL_GENESIS_COLLECTION, GENESIS_MARKER_APPLICATION_KEY);
    }

    /** Consensus-state key holding the retained receipt for one app message. */
    public static byte[] receiptKey(byte[] messageId) {
        return encodeCanonicalKey(NAMESPACE_KIND_FRAMEWORK,
                INTERNAL_RECEIPTS_COLLECTION, require32(messageId, "messageId"));
    }

    /** One replay namespace per actor; another actor may independently use the same ID. */
    public static byte[] directConsumptionKey(String actorId, byte[] authorizationId) {
        byte[] actor = RoleWorkflowIdentifiers.id(actorId, "actorId")
                .getBytes(StandardCharsets.US_ASCII);
        byte[] identifier = require32(authorizationId, "authorizationId");
        byte[] applicationKey = ByteBuffer.allocate(1 + Short.BYTES + actor.length
                        + identifier.length)
                .put((byte) 1)
                .putShort((short) actor.length)
                .put(actor)
                .put(identifier)
                .array();
        return encodeCanonicalKey(NAMESPACE_KIND_FRAMEWORK,
                INTERNAL_DIRECT_CONSUMPTION_COLLECTION, applicationKey);
    }

    /** Exactly one replay namespace per approval proposal, with no action-scope suffix. */
    public static byte[] approvalConsumptionKey(String proposalId) {
        byte[] applicationKey = RoleWorkflowIdentifiers.id(proposalId, "proposalId")
                .getBytes(StandardCharsets.US_ASCII);
        return encodeCanonicalKey(NAMESPACE_KIND_FRAMEWORK,
                INTERNAL_APPROVAL_CONSUMPTION_COLLECTION, applicationKey);
    }

    private static byte[] encodeCanonicalKey(
            int namespaceKind,
            String collectionId,
            byte[] applicationKey
    ) {
        String collection = requireCollectionId(collectionId);
        byte[] collectionBytes = collection.getBytes(StandardCharsets.US_ASCII);
        byte[] key = requireApplicationKey(applicationKey, MAX_APPLICATION_KEY_BYTES);
        return ByteBuffer.allocate(2 + Short.BYTES + collectionBytes.length
                        + Integer.BYTES + key.length)
                .put((byte) KEY_CODEC_VERSION)
                .put((byte) namespaceKind)
                .putShort((short) collectionBytes.length)
                .put(collectionBytes)
                .putInt(key.length)
                .put(key)
                .array();
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
        Array validators = new Array();
        for (ValidatorDescriptor descriptor : genesis.validators()) {
            validators.add(encodeValidator(descriptor));
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
        root.add(new UnsignedInteger(GENESIS_CODEC_VERSION));
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
        root.add(validators);
        root.add(initialEntries);
        root.add(new ByteString(genesis.governedGenesis() == null
                ? new byte[0] : genesis.governedGenesis().encode()));
        return StdlibContractCbor.encode(root);
    }

    public static Genesis decodeGenesis(byte[] encoded) {
        List<co.nstant.in.cbor.model.DataItem> values =
                StdlibContractCbor.decodeArray(encoded, 15).getDataItems();
        if (StdlibContractCbor.uintInt(values.get(0)) != GENESIS_CODEC_VERSION
                || !STATE_MACHINE_ID.equals(StdlibContractCbor.text(values.get(2)))
                || StdlibContractCbor.uintInt(values.get(3)) != STATE_MACHINE_VERSION) {
            throw malformed();
        }
        Array collectionItems = StdlibContractCbor.array(values.get(11), MAX_COLLECTIONS);
        List<CollectionDescriptor> collections = new ArrayList<>(collectionItems.getDataItems().size());
        for (co.nstant.in.cbor.model.DataItem item : collectionItems.getDataItems()) {
            collections.add(decodeCollection(StdlibContractCbor.array(item, 9)));
        }
        Array validatorItems = StdlibContractCbor.array(values.get(12), MAX_VALIDATORS);
        List<ValidatorDescriptor> validators = new ArrayList<>(
                validatorItems.getDataItems().size());
        for (co.nstant.in.cbor.model.DataItem item : validatorItems.getDataItems()) {
            validators.add(decodeValidator(StdlibContractCbor.array(item, 7)));
        }
        Array entryItems = StdlibContractCbor.array(values.get(13), MAX_BATCH_ITEMS);
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
                validators,
                entries,
                governedGenesis(values.get(14)));
        if (!Arrays.equals(encoded, encodeGenesis(decoded))) {
            throw malformed();
        }
        return decoded;
    }

    private static GovernedGenesisV1 governedGenesis(
            co.nstant.in.cbor.model.DataItem value
    ) {
        byte[] encoded = StdlibContractCbor.bytes(value);
        return encoded.length == 0 ? null : GovernedGenesisV1.decode(encoded);
    }

    public static byte[] genesisId(Genesis genesis) {
        byte[] encoded = encodeGenesis(genesis);
        byte[] input = new byte[GENESIS_HASH_DOMAIN.length + encoded.length];
        System.arraycopy(GENESIS_HASH_DOMAIN, 0, input, 0, GENESIS_HASH_DOMAIN.length);
        System.arraycopy(encoded, 0, input, GENESIS_HASH_DOMAIN.length, encoded.length);
        return Blake2bUtil.blake2bHash256(input);
    }

    public static byte[] encodePointQuery(PointQuery query) {
        Objects.requireNonNull(query, "query");
        Array root = new Array();
        root.add(new UnsignedInteger(STATE_MACHINE_VERSION));
        root.add(new UnsignedInteger(query.historical() ? 1 : 0));
        root.add(new UnsignedInteger(query.height()));
        root.add(new UnicodeString(query.collectionId()));
        root.add(new ByteString(query.applicationKey()));
        return StdlibContractCbor.encode(root);
    }

    public static PointQuery decodePointQuery(byte[] encoded) {
        List<co.nstant.in.cbor.model.DataItem> fields =
                StdlibContractCbor.decodeArray(encoded, 5).getDataItems();
        if (StdlibContractCbor.uintInt(fields.get(0)) != STATE_MACHINE_VERSION) {
            throw malformed();
        }
        int mode = StdlibContractCbor.uintInt(fields.get(1));
        if (mode > 1) {
            throw malformed();
        }
        PointQuery decoded = new PointQuery(
                mode == 1,
                StdlibContractCbor.uint(fields.get(2)),
                StdlibContractCbor.text(fields.get(3)),
                StdlibContractCbor.bytes(fields.get(4)));
        if (!Arrays.equals(encoded, encodePointQuery(decoded))) {
            throw malformed();
        }
        return decoded;
    }

    public static byte[] encodePointResult(PointResult result) {
        Objects.requireNonNull(result, "result");
        Array root = new Array();
        root.add(new UnsignedInteger(STATE_MACHINE_VERSION));
        root.add(new UnsignedInteger(result.committedHeight()));
        root.add(new ByteString(result.stateRoot()));
        root.add(new UnicodeString(result.collectionId()));
        root.add(new ByteString(result.applicationKey()));
        root.add(new UnsignedInteger(result.presence()));
        root.add(new ByteString(result.entry() == null
                ? new byte[0] : encodeEntry(result.entry())));
        return StdlibContractCbor.encode(root);
    }

    public static PointResult decodePointResult(byte[] encoded) {
        List<co.nstant.in.cbor.model.DataItem> fields =
                StdlibContractCbor.decodeArray(encoded, 7).getDataItems();
        if (StdlibContractCbor.uintInt(fields.get(0)) != STATE_MACHINE_VERSION) {
            throw malformed();
        }
        byte[] entryBytes = StdlibContractCbor.bytes(fields.get(6));
        PointResult decoded = new PointResult(
                StdlibContractCbor.uint(fields.get(1)),
                StdlibContractCbor.bytes(fields.get(2)),
                StdlibContractCbor.text(fields.get(3)),
                StdlibContractCbor.bytes(fields.get(4)),
                StdlibContractCbor.uintInt(fields.get(5)),
                entryBytes.length == 0 ? null : decodeEntry(entryBytes));
        if (!Arrays.equals(encoded, encodePointResult(decoded))) {
            throw malformed();
        }
        return decoded;
    }

    public static byte[] encodeReceiptQuery(ReceiptQuery query) {
        Objects.requireNonNull(query, "query");
        Array root = new Array();
        root.add(new UnsignedInteger(STATE_MACHINE_VERSION));
        root.add(new ByteString(query.messageId()));
        return StdlibContractCbor.encode(root);
    }

    public static ReceiptQuery decodeReceiptQuery(byte[] encoded) {
        List<co.nstant.in.cbor.model.DataItem> fields =
                StdlibContractCbor.decodeArray(encoded, 2).getDataItems();
        if (StdlibContractCbor.uintInt(fields.get(0)) != STATE_MACHINE_VERSION) {
            throw malformed();
        }
        ReceiptQuery decoded = new ReceiptQuery(StdlibContractCbor.bytes(fields.get(1)));
        if (!Arrays.equals(encoded, encodeReceiptQuery(decoded))) {
            throw malformed();
        }
        return decoded;
    }

    public static byte[] resultCommitment(int status, int errorCode,
                                          List<MutationResult> results) {
        Array items = new Array();
        for (MutationResult result : List.copyOf(results)) {
            items.add(encodeMutationResult(result));
        }
        Array material = new Array();
        material.add(new UnsignedInteger(STATE_MACHINE_VERSION));
        material.add(new UnsignedInteger(status));
        material.add(new UnsignedInteger(errorCode));
        material.add(items);
        byte[] encoded = StdlibContractCbor.encode(material);
        byte[] input = new byte[RESULT_HASH_DOMAIN.length + encoded.length];
        System.arraycopy(RESULT_HASH_DOMAIN, 0, input, 0, RESULT_HASH_DOMAIN.length);
        System.arraycopy(encoded, 0, input, RESULT_HASH_DOMAIN.length, encoded.length);
        return Blake2bUtil.blake2bHash256(input);
    }

    public static byte[] encodeReceipt(Receipt receipt) {
        Objects.requireNonNull(receipt, "receipt");
        Array results = new Array();
        for (MutationResult result : receipt.results()) {
            results.add(encodeMutationResult(result));
        }
        Array root = new Array();
        root.add(new UnsignedInteger(STATE_MACHINE_VERSION));
        root.add(new ByteString(receipt.messageId()));
        root.add(new UnsignedInteger(receipt.height()));
        root.add(new UnsignedInteger(receipt.status()));
        root.add(new UnsignedInteger(receipt.errorCode()));
        root.add(new ByteString(receipt.batchCommitment()));
        root.add(new ByteString(receipt.resultCommitment()));
        root.add(results);
        return StdlibContractCbor.encode(root);
    }

    public static Receipt decodeReceipt(byte[] encoded) {
        List<co.nstant.in.cbor.model.DataItem> fields =
                StdlibContractCbor.decodeArray(encoded, 8).getDataItems();
        if (StdlibContractCbor.uintInt(fields.get(0)) != STATE_MACHINE_VERSION) {
            throw malformed();
        }
        Array resultItems = StdlibContractCbor.array(fields.get(7), MAX_BATCH_ITEMS);
        List<MutationResult> results = new ArrayList<>(resultItems.getDataItems().size());
        for (co.nstant.in.cbor.model.DataItem item : resultItems.getDataItems()) {
            results.add(decodeMutationResult(StdlibContractCbor.array(item, 5)));
        }
        Receipt decoded = new Receipt(
                StdlibContractCbor.bytes(fields.get(1)),
                StdlibContractCbor.uint(fields.get(2)),
                StdlibContractCbor.uintInt(fields.get(3)),
                StdlibContractCbor.uintInt(fields.get(4)),
                StdlibContractCbor.bytes(fields.get(5)),
                StdlibContractCbor.bytes(fields.get(6)),
                results);
        if (!Arrays.equals(encoded, encodeReceipt(decoded))) {
            throw malformed();
        }
        return decoded;
    }

    public static byte[] encodeReceiptResult(ReceiptResult result) {
        Objects.requireNonNull(result, "result");
        Array root = new Array();
        root.add(new UnsignedInteger(STATE_MACHINE_VERSION));
        root.add(new UnsignedInteger(result.committedHeight()));
        root.add(new ByteString(result.stateRoot()));
        root.add(new ByteString(result.messageId()));
        root.add(new UnsignedInteger(result.presence()));
        root.add(new ByteString(result.receipt() == null
                ? new byte[0] : encodeReceipt(result.receipt())));
        return StdlibContractCbor.encode(root);
    }

    public static ReceiptResult decodeReceiptResult(byte[] encoded) {
        List<co.nstant.in.cbor.model.DataItem> fields =
                StdlibContractCbor.decodeArray(encoded, 6).getDataItems();
        if (StdlibContractCbor.uintInt(fields.get(0)) != STATE_MACHINE_VERSION) {
            throw malformed();
        }
        byte[] receiptBytes = StdlibContractCbor.bytes(fields.get(5));
        ReceiptResult decoded = new ReceiptResult(
                StdlibContractCbor.uint(fields.get(1)),
                StdlibContractCbor.bytes(fields.get(2)),
                StdlibContractCbor.bytes(fields.get(3)),
                StdlibContractCbor.uintInt(fields.get(4)),
                receiptBytes.length == 0 ? null : decodeReceipt(receiptBytes));
        if (!Arrays.equals(encoded, encodeReceiptResult(decoded))) {
            throw malformed();
        }
        return decoded;
    }

    private static Array encodeMutationResult(MutationResult result) {
        Array item = new Array();
        item.add(new UnicodeString(result.collectionId()));
        item.add(new ByteString(result.applicationKey()));
        item.add(new UnsignedInteger(result.status()));
        item.add(new UnsignedInteger(result.revision()));
        item.add(new ByteString(result.logicalValueHash()));
        return item;
    }

    private static MutationResult decodeMutationResult(Array item) {
        if (item.getDataItems().size() != 5) {
            throw malformed();
        }
        List<co.nstant.in.cbor.model.DataItem> fields = item.getDataItems();
        return new MutationResult(
                StdlibContractCbor.text(fields.get(0)),
                StdlibContractCbor.bytes(fields.get(1)),
                StdlibContractCbor.uintInt(fields.get(2)),
                StdlibContractCbor.uint(fields.get(3)),
                StdlibContractCbor.bytes(fields.get(4)));
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
        item.add(new UnsignedInteger(GENESIS_CODEC_VERSION));
        item.add(new UnicodeString(descriptor.id()));
        item.add(new UnsignedInteger(descriptor.authorization()));
        item.add(new UnicodeString(descriptor.authorizationPolicyId()));
        item.add(new UnsignedInteger(descriptor.restoreAllowed() ? 1 : 0));
        item.add(new UnsignedInteger(descriptor.maxKeyBytes()));
        item.add(new UnsignedInteger(descriptor.maxValueBytes()));
        item.add(new UnsignedInteger(descriptor.valueEncoding()));
        item.add(new UnicodeString(descriptor.validatorId()));
        return item;
    }

    private static CollectionDescriptor decodeCollection(Array item) {
        if (item.getDataItems().size() != 9) {
            throw malformed();
        }
        List<co.nstant.in.cbor.model.DataItem> values = item.getDataItems();
        if (StdlibContractCbor.uintInt(values.get(0)) != GENESIS_CODEC_VERSION) {
            throw malformed();
        }
        int restore = StdlibContractCbor.uintInt(values.get(4));
        if (restore > 1) {
            throw malformed();
        }
        return new CollectionDescriptor(
                StdlibContractCbor.text(values.get(1)),
                StdlibContractCbor.uintInt(values.get(2)),
                StdlibContractCbor.text(values.get(3)),
                restore == 1,
                StdlibContractCbor.uintInt(values.get(5)),
                StdlibContractCbor.uintInt(values.get(6)),
                StdlibContractCbor.uintInt(values.get(7)),
                StdlibContractCbor.text(values.get(8)));
    }

    private static Array encodeValidator(ValidatorDescriptor descriptor) {
        Array item = new Array();
        item.add(new UnsignedInteger(GENESIS_CODEC_VERSION));
        item.add(new UnicodeString(descriptor.id()));
        item.add(new UnsignedInteger(descriptor.kind()));
        item.add(new UnicodeString(descriptor.providerId()));
        item.add(new UnicodeString(descriptor.contractVersion()));
        item.add(new ByteString(descriptor.definition()));
        item.add(new ByteString(descriptor.parameters()));
        return item;
    }

    private static ValidatorDescriptor decodeValidator(Array item) {
        if (item.getDataItems().size() != 7) {
            throw malformed();
        }
        List<co.nstant.in.cbor.model.DataItem> values = item.getDataItems();
        if (StdlibContractCbor.uintInt(values.get(0)) != GENESIS_CODEC_VERSION) {
            throw malformed();
        }
        return new ValidatorDescriptor(
                StdlibContractCbor.text(values.get(1)),
                StdlibContractCbor.uintInt(values.get(2)),
                StdlibContractCbor.text(values.get(3)),
                StdlibContractCbor.text(values.get(4)),
                StdlibContractCbor.bytes(values.get(5)),
                StdlibContractCbor.bytes(values.get(6)));
    }

    private static String requireCollectionId(String id) {
        String value = Objects.requireNonNull(id, "collectionId");
        if (!COLLECTION_ID.matcher(value).matches()
                || value.getBytes(StandardCharsets.US_ASCII).length > MAX_COLLECTION_ID_BYTES) {
            throw new IllegalArgumentException("collectionId must be canonical lowercase ASCII");
        }
        return value;
    }

    private static String requireValidatorId(String id, boolean emptyAllowed) {
        String value = Objects.requireNonNull(id, "validatorId");
        if (emptyAllowed && value.isEmpty()) return value;
        if (!VALIDATOR_ID.matcher(value).matches()
                || value.getBytes(StandardCharsets.US_ASCII).length > 64) {
            throw new IllegalArgumentException(
                    "validatorId must be canonical lowercase ASCII");
        }
        return value;
    }

    private static String requirePolicyId(String id, boolean emptyAllowed) {
        String value = Objects.requireNonNull(id, "authorizationPolicyId");
        if (emptyAllowed && value.isEmpty()) return value;
        return RoleWorkflowIdentifiers.id(value, "authorizationPolicyId");
    }

    private static boolean canonicalParameterMap(byte[] parameters) {
        return parameters != null && parameters.length > 0
                && parameters.length <= MAX_VALIDATOR_PARAMETERS_BYTES
                && (parameters[0] & 0xe0) == 0xa0
                && CanonicalValueCbor.accepts(parameters, MAX_VALIDATOR_PARAMETERS_BYTES);
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

    public record PointQuery(
            boolean historical,
            long height,
            String collectionId,
            byte[] applicationKey
    ) {
        public PointQuery {
            if (height < 0 || historical && height == 0 || !historical && height != 0) {
                throw new IllegalArgumentException("point query height/mode is invalid");
            }
            collectionId = requireCollectionId(collectionId);
            applicationKey = requireApplicationKey(applicationKey, MAX_APPLICATION_KEY_BYTES);
        }

        @Override public byte[] applicationKey() { return applicationKey.clone(); }

        public static PointQuery current(String collectionId, byte[] applicationKey) {
            return new PointQuery(false, 0, collectionId, applicationKey);
        }

        public static PointQuery atHeight(long height, String collectionId,
                                          byte[] applicationKey) {
            return new PointQuery(true, height, collectionId, applicationKey);
        }
    }

    public record PointResult(
            long committedHeight,
            byte[] stateRoot,
            String collectionId,
            byte[] applicationKey,
            int presence,
            Entry entry
    ) {
        public PointResult {
            if (committedHeight < 0) {
                throw new IllegalArgumentException("committedHeight must be nonnegative");
            }
            stateRoot = require32(stateRoot, "stateRoot");
            collectionId = requireCollectionId(collectionId);
            applicationKey = requireApplicationKey(applicationKey, MAX_APPLICATION_KEY_BYTES);
            if (presence < PRESENCE_ABSENT || presence > PRESENCE_REVOKED) {
                throw new IllegalArgumentException("point result presence is unsupported");
            }
            if (presence == PRESENCE_ABSENT ? entry != null : entry == null) {
                throw new IllegalArgumentException("point result presence and entry differ");
            }
            if (entry != null && (presence == PRESENCE_ACTIVE
                    ? entry.status() != STATUS_ACTIVE : entry.status() != STATUS_REVOKED)) {
                throw new IllegalArgumentException("point result status and entry differ");
            }
        }

        @Override public byte[] stateRoot() { return stateRoot.clone(); }
        @Override public byte[] applicationKey() { return applicationKey.clone(); }
    }

    public record ReceiptQuery(byte[] messageId) {
        public ReceiptQuery {
            messageId = require32(messageId, "messageId");
        }

        @Override public byte[] messageId() { return messageId.clone(); }
    }

    public record MutationResult(
            String collectionId,
            byte[] applicationKey,
            int status,
            long revision,
            byte[] logicalValueHash
    ) {
        public MutationResult {
            collectionId = requireCollectionId(collectionId);
            applicationKey = requireApplicationKey(applicationKey, MAX_APPLICATION_KEY_BYTES);
            if (status != STATUS_ACTIVE && status != STATUS_REVOKED || revision <= 0) {
                throw new IllegalArgumentException("mutation result status/revision is invalid");
            }
            logicalValueHash = require32(logicalValueHash, "logicalValueHash");
        }

        @Override public byte[] applicationKey() { return applicationKey.clone(); }
        @Override public byte[] logicalValueHash() { return logicalValueHash.clone(); }
    }

    public record Receipt(
            byte[] messageId,
            long height,
            int status,
            int errorCode,
            byte[] batchCommitment,
            byte[] resultCommitment,
            List<MutationResult> results
    ) {
        public Receipt {
            messageId = require32(messageId, "messageId");
            if (height <= 0 || status < RECEIPT_APPLIED || status > RECEIPT_REJECTED
                    || errorCode < ERROR_NONE || errorCode > ERROR_VALUE_VALIDATOR) {
                throw new IllegalArgumentException("receipt status/height/error is invalid");
            }
            batchCommitment = require32(batchCommitment, "batchCommitment");
            resultCommitment = require32(resultCommitment, "resultCommitment");
            List<MutationResult> copy = List.copyOf(Objects.requireNonNull(results, "results"));
            if (copy.size() > MAX_BATCH_ITEMS
                    || status == RECEIPT_APPLIED && (errorCode != ERROR_NONE || copy.isEmpty())
                    || status == RECEIPT_REJECTED && (errorCode == ERROR_NONE || !copy.isEmpty())) {
                throw new IllegalArgumentException("receipt status/result shape is invalid");
            }
            if (!Arrays.equals(resultCommitment,
                    AuthenticatedMapContract.resultCommitment(status, errorCode, copy))) {
                throw new IllegalArgumentException("receipt result commitment does not match");
            }
            results = copy;
        }

        @Override public byte[] messageId() { return messageId.clone(); }
        @Override public byte[] batchCommitment() { return batchCommitment.clone(); }
        @Override public byte[] resultCommitment() { return resultCommitment.clone(); }

        public static Receipt applied(byte[] messageId, long height, byte[] batchCommitment,
                                      List<MutationResult> results) {
            return new Receipt(messageId, height, RECEIPT_APPLIED, ERROR_NONE,
                    batchCommitment,
                    AuthenticatedMapContract.resultCommitment(
                            RECEIPT_APPLIED, ERROR_NONE, results),
                    results);
        }

        public static Receipt rejected(byte[] messageId, long height, byte[] batchCommitment,
                                       int errorCode) {
            return new Receipt(messageId, height, RECEIPT_REJECTED, errorCode,
                    batchCommitment,
                    AuthenticatedMapContract.resultCommitment(
                            RECEIPT_REJECTED, errorCode, List.of()),
                    List.of());
        }
    }

    public record ReceiptResult(
            long committedHeight,
            byte[] stateRoot,
            byte[] messageId,
            int presence,
            Receipt receipt
    ) {
        public ReceiptResult {
            if (committedHeight < 0) {
                throw new IllegalArgumentException("committedHeight must be nonnegative");
            }
            stateRoot = require32(stateRoot, "stateRoot");
            messageId = require32(messageId, "messageId");
            if (presence != RECEIPT_ABSENT && presence != RECEIPT_PRESENT
                    || presence == RECEIPT_ABSENT && receipt != null
                    || presence == RECEIPT_PRESENT && receipt == null
                    || receipt != null && !Arrays.equals(messageId, receipt.messageId())) {
                throw new IllegalArgumentException("receipt result presence/message is invalid");
            }
        }

        @Override public byte[] stateRoot() { return stateRoot.clone(); }
        @Override public byte[] messageId() { return messageId.clone(); }
    }

    public record CollectionDescriptor(
            String id,
            int authorization,
            String authorizationPolicyId,
            boolean restoreAllowed,
            int maxKeyBytes,
            int maxValueBytes,
            int valueEncoding,
            String validatorId
    ) {
        public CollectionDescriptor {
            id = requireCollectionId(id);
            authorizationPolicyId = requirePolicyId(authorizationPolicyId, true);
            validatorId = requireValidatorId(validatorId, true);
            if (authorization < AUTH_OPEN || authorization > AUTH_APPROVAL) {
                throw new IllegalArgumentException("unsupported authenticated-map authorization policy");
            }
            boolean governed = authorization == AUTH_GOVERNED_ROLE
                    || authorization == AUTH_APPROVAL;
            if (governed == authorizationPolicyId.isEmpty()) {
                throw new IllegalArgumentException(
                        "authenticated-map policy id does not match authorization kind");
            }
            if (maxKeyBytes <= 0 || maxKeyBytes > MAX_APPLICATION_KEY_BYTES) {
                throw new IllegalArgumentException("maxKeyBytes is outside the v1 contract");
            }
            if (maxValueBytes < 0 || maxValueBytes > MAX_VALUE_BYTES) {
                throw new IllegalArgumentException("maxValueBytes is outside the v1 contract");
            }
            if (valueEncoding != VALUE_ENCODING_OPAQUE
                    && valueEncoding != VALUE_ENCODING_CANONICAL_CBOR) {
                throw new IllegalArgumentException("unsupported authenticated-map value encoding");
            }
        }

        /** Source-compatible constructor retaining the explicit v1 opaque default. */
        public CollectionDescriptor(
                String id,
                int authorization,
                boolean restoreAllowed,
                int maxKeyBytes,
                int maxValueBytes
        ) {
            this(id, authorization, "", restoreAllowed, maxKeyBytes, maxValueBytes,
                    VALUE_ENCODING_OPAQUE, "");
        }

        /** Phase-A source-compatible constructor with no consensus validator. */
        public CollectionDescriptor(
                String id,
                int authorization,
                boolean restoreAllowed,
                int maxKeyBytes,
                int maxValueBytes,
                int valueEncoding
        ) {
            this(id, authorization, "", restoreAllowed, maxKeyBytes, maxValueBytes,
                    valueEncoding, "");
        }

        /** Source-compatible constructor for the ADR-025.1 descriptor. */
        public CollectionDescriptor(
                String id,
                int authorization,
                boolean restoreAllowed,
                int maxKeyBytes,
                int maxValueBytes,
                int valueEncoding,
                String validatorId
        ) {
            this(id, authorization, "", restoreAllowed, maxKeyBytes, maxValueBytes,
                    valueEncoding, validatorId);
        }
    }

    /** Genesis-bound schema or plugin validator descriptor. */
    public record ValidatorDescriptor(
            String id,
            int kind,
            String providerId,
            String contractVersion,
            byte[] definition,
            byte[] parameters
    ) {
        public ValidatorDescriptor {
            id = requireValidatorId(id, false);
            providerId = Objects.requireNonNull(providerId, "providerId");
            contractVersion = Objects.requireNonNull(contractVersion, "contractVersion");
            definition = Objects.requireNonNull(definition, "definition").clone();
            parameters = Objects.requireNonNull(parameters, "parameters").clone();
            if (!canonicalParameterMap(parameters)) {
                throw new IllegalArgumentException(
                        "validator parameters must be a bounded canonical CBOR map");
            }
            if (kind == VALIDATOR_KIND_SCHEMA) {
                if (!providerId.isEmpty()
                        || !AuthenticatedMapSchema.IR_CATALOG_ID.equals(contractVersion)
                        || !AuthenticatedMapSchema.isCanonicalDefinition(definition)
                        || !Arrays.equals(parameters, new byte[]{(byte) 0xa0})) {
                    throw new IllegalArgumentException("invalid declarative schema descriptor");
                }
            } else if (kind == VALIDATOR_KIND_PLUGIN) {
                requireValidatorId(providerId, false);
                if (!VALIDATOR_SPI_CONTRACT_VERSION.equals(contractVersion)
                        || definition.length != 32) {
                    throw new IllegalArgumentException("invalid plugin validator descriptor");
                }
            } else {
                throw new IllegalArgumentException("unsupported validator kind");
            }
        }

        public static ValidatorDescriptor schema(String id, byte[] definition) {
            return new ValidatorDescriptor(id, VALIDATOR_KIND_SCHEMA, "",
                    AuthenticatedMapSchema.IR_CATALOG_ID, definition,
                    new byte[]{(byte) 0xa0});
        }

        public static ValidatorDescriptor plugin(
                String id,
                String providerId,
                byte[] artifactClosureDigest,
                byte[] parameters
        ) {
            return new ValidatorDescriptor(id, VALIDATOR_KIND_PLUGIN, providerId,
                    VALIDATOR_SPI_CONTRACT_VERSION, artifactClosureDigest, parameters);
        }

        @Override public byte[] definition() { return definition.clone(); }
        @Override public byte[] parameters() { return parameters.clone(); }
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
            List<ValidatorDescriptor> validators,
            List<GenesisEntry> initialEntries,
            GovernedGenesisV1 governedGenesis
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

            boolean governed = collectionCopy.stream().anyMatch(descriptor ->
                    descriptor.authorization() == AUTH_GOVERNED_ROLE
                            || descriptor.authorization() == AUTH_APPROVAL);
            if (governed != (governedGenesis != null)) {
                throw new IllegalArgumentException(
                        "governed collection capability does not match genesis closure");
            }
            if (governedGenesis != null) {
                if (!chainId.equals(governedGenesis.chainId())) {
                    throw new IllegalArgumentException(
                            "governed genesis closure belongs to another chain");
                }
                for (CollectionDescriptor descriptor : collectionCopy) {
                    if (descriptor.authorization() == AUTH_GOVERNED_ROLE
                            && governedGenesis.approvalPolicy(
                            descriptor.authorizationPolicyId()) != null
                            || descriptor.authorization() == AUTH_APPROVAL
                            && governedGenesis.directPolicy(
                            descriptor.authorizationPolicyId()) != null) {
                        throw new IllegalArgumentException(
                                "collection references the wrong governed policy kind");
                    }
                }
            }

            List<ValidatorDescriptor> validatorCopy = new ArrayList<>(
                    Objects.requireNonNull(validators, "validators"));
            if (validatorCopy.size() > MAX_VALIDATORS) {
                throw new IllegalArgumentException(
                        "genesis exceeds " + MAX_VALIDATORS + " validators");
            }
            validatorCopy.sort(Comparator.comparing(ValidatorDescriptor::id));
            Map<String, ValidatorDescriptor> validatorDescriptors = new HashMap<>();
            for (ValidatorDescriptor validator : validatorCopy) {
                if (validatorDescriptors.put(validator.id(), validator) != null) {
                    throw new IllegalArgumentException(
                            "duplicate validator id: " + validator.id());
                }
            }
            Set<String> referencedValidators = new HashSet<>();
            for (CollectionDescriptor descriptor : collectionCopy) {
                if (descriptor.validatorId().isEmpty()) continue;
                ValidatorDescriptor validator = validatorDescriptors.get(
                        descriptor.validatorId());
                if (validator == null) {
                    throw new IllegalArgumentException("collection " + descriptor.id()
                            + " references unknown validator " + descriptor.validatorId());
                }
                if (validator.kind() == VALIDATOR_KIND_SCHEMA
                        && descriptor.valueEncoding() != VALUE_ENCODING_CANONICAL_CBOR) {
                    throw new IllegalArgumentException("schema collection " + descriptor.id()
                            + " must use canonical CBOR value encoding");
                }
                referencedValidators.add(validator.id());
            }
            if (referencedValidators.size() != validatorCopy.size()) {
                throw new IllegalArgumentException(
                        "genesis contains an unreferenced validator descriptor");
            }

            Map<String, AuthenticatedMapSchema.Schema> schemas = new HashMap<>();
            for (ValidatorDescriptor validator : validatorCopy) {
                if (validator.kind() == VALIDATOR_KIND_SCHEMA) {
                    schemas.put(validator.id(),
                            AuthenticatedMapSchema.decode(validator.definition()));
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
                if (!valueEncodingAccepts(
                        descriptor.valueEncoding(), entry.value(), descriptor.maxValueBytes())) {
                    throw new IllegalArgumentException(
                            "initial entry violates collection value encoding");
                }
                AuthenticatedMapSchema.Schema schema = schemas.get(descriptor.validatorId());
                if (schema != null && !schema.accepts(entry.value())) {
                    throw new IllegalArgumentException(
                            "initial entry violates collection value schema");
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
            validators = List.copyOf(validatorCopy);
            initialEntries = List.copyOf(entryCopy);
        }

        /** Source-compatible constructor for ADR-025.1 genesis without governance. */
        public Genesis(
                String chainId,
                String commitmentProfileId,
                byte[] formatFingerprint,
                byte[] frameworkConsensusProfileDigest,
                byte[] membershipCommitment,
                byte[] anchorPolicyCommitment,
                int maxBatchItems,
                int maxBatchBytes,
                List<CollectionDescriptor> collections,
                List<ValidatorDescriptor> validators,
                List<GenesisEntry> initialEntries
        ) {
            this(chainId, commitmentProfileId, formatFingerprint,
                    frameworkConsensusProfileDigest, membershipCommitment,
                    anchorPolicyCommitment, maxBatchItems, maxBatchBytes,
                    collections, validators, initialEntries, null);
        }

        /** Source-compatible constructor for genesis without value validators. */
        public Genesis(
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
            this(chainId, commitmentProfileId, formatFingerprint,
                    frameworkConsensusProfileDigest, membershipCommitment,
                    anchorPolicyCommitment, maxBatchItems, maxBatchBytes,
                    collections, List.of(), initialEntries, null);
        }

        @Override public byte[] formatFingerprint() { return formatFingerprint.clone(); }
        @Override public byte[] frameworkConsensusProfileDigest() {
            return frameworkConsensusProfileDigest.clone();
        }
        @Override public byte[] membershipCommitment() { return membershipCommitment.clone(); }
        @Override public byte[] anchorPolicyCommitment() { return anchorPolicyCommitment.clone(); }
    }
}
