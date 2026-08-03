package com.bloxbean.cardano.yano.appchain.stdlib.contracts;

import com.bloxbean.cardano.vds.jmt.JmtProfile;
import com.bloxbean.cardano.yano.api.appchain.state.StateCommitmentProfiles;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuthenticatedMapContractTest {
    private static final byte[] OWNER = java.util.HexFormat.of().parseHex("11".repeat(32));

    @Test
    void canonicalKeyIsStructurallyNamespacedAndLengthDelimited() {
        byte[] encoded = AuthenticatedMapContract.canonicalKey(
                "products", bytes("sku-1"));

        assertThat(java.util.HexFormat.of().formatHex(encoded)).isEqualTo(
                "0101000870726f647563747300000005736b752d31");
        assertThat(AuthenticatedMapContract.decodeCanonicalKey(encoded))
                .satisfies(key -> {
                    assertThat(key.collectionId()).isEqualTo("products");
                    assertThat(key.applicationKey()).isEqualTo(bytes("sku-1"));
                });
        assertThatThrownBy(() -> AuthenticatedMapContract.decodeCanonicalKey(
                java.util.HexFormat.of().parseHex("0100000870726f647563747300000005736b752d31")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void commandAndEntryUseFrozenPreferredCbor() {
        AuthenticatedMapContract.Command command = AuthenticatedMapContract.Command.single(
                AuthenticatedMapContract.Mutation.put("products", bytes("sku-1"),
                        new byte[]{1, 2}));
        byte[] commandBytes = AuthenticatedMapContract.encodeCommand(command);

        assertThat(java.util.HexFormat.of().formatHex(commandBytes)).isEqualTo(
                "8301008187006870726f647563747345736b752d31420102004040");
        assertThat(AuthenticatedMapContract.encodeCommand(
                AuthenticatedMapContract.decodeCommand(commandBytes))).isEqualTo(commandBytes);

        AuthenticatedMapContract.Entry entry = AuthenticatedMapContract.Entry.active(
                1, OWNER, new byte[]{1, 2}, 0, 0);
        byte[] entryBytes = AuthenticatedMapContract.encodeEntry(entry);
        assertThat(AuthenticatedMapContract.encodeEntry(
                AuthenticatedMapContract.decodeEntry(entryBytes))).isEqualTo(entryBytes);
        assertThat(entry.revoked(7)).satisfies(revoked -> {
            assertThat(revoked.status()).isEqualTo(AuthenticatedMapContract.STATUS_REVOKED);
            assertThat(revoked.revision()).isEqualTo(2);
            assertThat(revoked.value()).isEmpty();
            assertThat(revoked.logicalValueHash()).isEqualTo(entry.logicalValueHash());
        });
    }

    @Test
    void genesisCanonicalizesCollectionsAndEntriesAndBindsFrameworkDigests() {
        AuthenticatedMapContract.Genesis genesis = genesis();
        byte[] encoded = AuthenticatedMapContract.encodeGenesis(genesis);

        assertThat(AuthenticatedMapContract.encodeGenesis(
                AuthenticatedMapContract.decodeGenesis(encoded))).isEqualTo(encoded);
        assertThat(genesis.collections()).extracting(AuthenticatedMapContract.CollectionDescriptor::id)
                .containsExactly("issuer-keys", "products");
        assertThat(genesis.initialEntries()).extracting(AuthenticatedMapContract.GenesisEntry::collectionId)
                .containsExactly("products");
        assertThat(genesis.collections())
                .extracting(AuthenticatedMapContract.CollectionDescriptor::valueEncoding)
                .containsOnly(AuthenticatedMapContract.VALUE_ENCODING_OPAQUE);
        assertThat(AuthenticatedMapContract.genesisId(genesis)).hasSize(32);

        byte[] oldCodec = encoded.clone();
        oldCodec[1] = 1;
        assertThatThrownBy(() -> AuthenticatedMapContract.decodeGenesis(oldCodec))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void batchIsOrderedAtomicAndRejectsDuplicateLogicalKeys() {
        AuthenticatedMapContract.Mutation first = AuthenticatedMapContract.Mutation.put(
                "products", bytes("one"), new byte[]{1});
        AuthenticatedMapContract.Mutation second = AuthenticatedMapContract.Mutation.put(
                "products", bytes("two"), new byte[]{2});
        AuthenticatedMapContract.Command batch = AuthenticatedMapContract.Command.batch(
                List.of(second, first));

        AuthenticatedMapContract.Command decoded = AuthenticatedMapContract.decodeCommand(
                AuthenticatedMapContract.encodeCommand(batch));
        assertThat(decoded.mutations()).extracting(AuthenticatedMapContract.Mutation::collectionId)
                .containsExactly("products", "products");
        assertThat(decoded.mutations()).extracting(mutation ->
                        new String(mutation.applicationKey(), StandardCharsets.US_ASCII))
                .containsExactly("two", "one");
        assertThat(AuthenticatedMapContract.batchCommitment(batch)).hasSize(32);
        assertThatThrownBy(() -> AuthenticatedMapContract.Command.batch(List.of(first, first)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("duplicate");
    }

    @Test
    void v1PoliciesAndOperationShapesFailClosed() {
        assertThatThrownBy(() -> new AuthenticatedMapContract.CollectionDescriptor(
                "Products", AuthenticatedMapContract.AUTH_OPEN, false, 32, 128))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AuthenticatedMapContract.CollectionDescriptor(
                "products", 3, false, 32, 128))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AuthenticatedMapContract.CollectionDescriptor(
                "products", AuthenticatedMapContract.AUTH_OPEN, false, 32, 128, 2))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("value encoding");
        assertThatThrownBy(() -> AuthenticatedMapContract.Mutation.compareAndSet(
                "products", bytes("sku-1"), new byte[]{1}, 0, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AuthenticatedMapContract.Entry(
                AuthenticatedMapContract.STATUS_REVOKED, 2, OWNER, new byte[]{1},
                new byte[32], 0, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThat(AuthenticatedMapContract.genesisMarkerKey()[1])
                .isEqualTo((byte) AuthenticatedMapContract.NAMESPACE_KIND_FRAMEWORK);
        assertThat(AuthenticatedMapContract.receiptKey(repeated(5))[1])
                .isEqualTo((byte) AuthenticatedMapContract.NAMESPACE_KIND_FRAMEWORK);
    }

    @Test
    void valueEncodingIsOpaqueByDefaultAndCanonicalCborIsExplicit() {
        AuthenticatedMapContract.CollectionDescriptor opaque =
                new AuthenticatedMapContract.CollectionDescriptor(
                        "opaque", AuthenticatedMapContract.AUTH_OPEN, false, 32, 128);
        AuthenticatedMapContract.CollectionDescriptor canonical =
                new AuthenticatedMapContract.CollectionDescriptor(
                        "canonical", AuthenticatedMapContract.AUTH_OPEN, false, 32, 128,
                        AuthenticatedMapContract.VALUE_ENCODING_CANONICAL_CBOR);

        assertThat(opaque.valueEncoding())
                .isEqualTo(AuthenticatedMapContract.VALUE_ENCODING_OPAQUE);
        assertThat(AuthenticatedMapContract.valueEncodingAccepts(
                opaque.valueEncoding(), new byte[0], opaque.maxValueBytes())).isTrue();
        assertThat(AuthenticatedMapContract.valueEncodingAccepts(
                opaque.valueEncoding(), java.util.HexFormat.of().parseHex("1817"),
                opaque.maxValueBytes())).isTrue();
        assertThat(AuthenticatedMapContract.valueEncodingAccepts(
                canonical.valueEncoding(), java.util.HexFormat.of().parseHex("a1616101"),
                canonical.maxValueBytes())).isTrue();
        assertThat(AuthenticatedMapContract.valueEncodingAccepts(
                canonical.valueEncoding(), java.util.HexFormat.of().parseHex("1817"),
                canonical.maxValueBytes())).isFalse();
    }

    @Test
    void genesisRejectsInitialValueThatViolatesCollectionEncoding() {
        AuthenticatedMapContract.CollectionDescriptor canonical =
                new AuthenticatedMapContract.CollectionDescriptor(
                        "records", AuthenticatedMapContract.AUTH_OPEN, false, 64, 1024,
                        AuthenticatedMapContract.VALUE_ENCODING_CANONICAL_CBOR);

        assertThatThrownBy(() -> new AuthenticatedMapContract.Genesis(
                "canonical-genesis",
                StateCommitmentProfiles.JMT_BLAKE2B256_V1,
                StateCommitmentProfiles.CLASSIC_JMT.formatFingerprint(),
                repeated(1), repeated(2), repeated(3),
                16, 4096, List.of(canonical),
                List.of(new AuthenticatedMapContract.GenesisEntry(
                        "records", bytes("one"), new byte[0],
                        java.util.HexFormat.of().parseHex("1817")))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("value encoding");
    }

    @Test
    void pointAndReceiptDtosAreCanonicalAndProfileNeutral() {
        AuthenticatedMapContract.PointQuery query =
                AuthenticatedMapContract.PointQuery.atHeight(
                        7, "products", bytes("sku-1"));
        AuthenticatedMapContract.PointQuery decodedQuery =
                AuthenticatedMapContract.decodePointQuery(
                        AuthenticatedMapContract.encodePointQuery(query));
        assertThat(decodedQuery.historical()).isTrue();
        assertThat(decodedQuery.height()).isEqualTo(7);
        assertThat(decodedQuery.collectionId()).isEqualTo("products");
        assertThat(decodedQuery.applicationKey()).isEqualTo(bytes("sku-1"));

        AuthenticatedMapContract.Entry entry = AuthenticatedMapContract.Entry.active(
                2, OWNER, bytes("value"), 1, 7);
        AuthenticatedMapContract.PointResult point = new AuthenticatedMapContract.PointResult(
                7, repeated(9), "products", bytes("sku-1"),
                AuthenticatedMapContract.PRESENCE_ACTIVE, entry);
        byte[] pointBytes = AuthenticatedMapContract.encodePointResult(point);
        assertThat(AuthenticatedMapContract.encodePointResult(
                AuthenticatedMapContract.decodePointResult(pointBytes))).isEqualTo(pointBytes);

        AuthenticatedMapContract.Command command = AuthenticatedMapContract.Command.single(
                AuthenticatedMapContract.Mutation.put(
                        "products", bytes("sku-1"), bytes("value")));
        AuthenticatedMapContract.MutationResult mutationResult =
                new AuthenticatedMapContract.MutationResult(
                        "products", bytes("sku-1"), entry.status(), entry.revision(),
                        entry.logicalValueHash());
        AuthenticatedMapContract.Receipt receipt = AuthenticatedMapContract.Receipt.applied(
                repeated(4), 7, AuthenticatedMapContract.batchCommitment(command),
                List.of(mutationResult));
        byte[] receiptBytes = AuthenticatedMapContract.encodeReceipt(receipt);
        assertThat(AuthenticatedMapContract.encodeReceipt(
                AuthenticatedMapContract.decodeReceipt(receiptBytes))).isEqualTo(receiptBytes);

        AuthenticatedMapContract.ReceiptResult result =
                new AuthenticatedMapContract.ReceiptResult(
                        7, repeated(9), repeated(4),
                        AuthenticatedMapContract.RECEIPT_PRESENT, receipt);
        byte[] resultBytes = AuthenticatedMapContract.encodeReceiptResult(result);
        assertThat(AuthenticatedMapContract.encodeReceiptResult(
                AuthenticatedMapContract.decodeReceiptResult(resultBytes)))
                .isEqualTo(resultBytes);
    }

    @Test
    void frameworkAndDependencyProfileIdentifiersRemainInParity() {
        assertThat(AuthenticatedMapContract.PROFILE_IDS).containsExactlyInAnyOrder(
                StateCommitmentProfiles.MPF_BLAKE2B256_V1,
                StateCommitmentProfiles.JMT_BLAKE2B256_V1,
                StateCommitmentProfiles.JMT_POSEIDON_BLS12381_V1);
        assertThat(JmtProfile.classicBlake2b256V1().format().profileId())
                .isEqualTo(StateCommitmentProfiles.CLASSIC_JMT.dependencyDescriptor());
    }

    private static AuthenticatedMapContract.Genesis genesis() {
        AuthenticatedMapContract.CollectionDescriptor products =
                new AuthenticatedMapContract.CollectionDescriptor(
                        "products", AuthenticatedMapContract.AUTH_OWNER, true, 128, 16_384);
        AuthenticatedMapContract.CollectionDescriptor issuers =
                new AuthenticatedMapContract.CollectionDescriptor(
                        "issuer-keys", AuthenticatedMapContract.AUTH_MEMBER, false, 64, 4_096);
        return new AuthenticatedMapContract.Genesis(
                "product-registry",
                StateCommitmentProfiles.JMT_BLAKE2B256_V1,
                StateCommitmentProfiles.CLASSIC_JMT.formatFingerprint(),
                repeated(0x22), repeated(0x33), repeated(0x44),
                64, 65_536,
                List.of(products, issuers),
                List.of(new AuthenticatedMapContract.GenesisEntry(
                        "products", bytes("sku-1"), OWNER, new byte[]{1, 2})));
    }

    private static byte[] repeated(int value) {
        byte[] bytes = new byte[32];
        java.util.Arrays.fill(bytes, (byte) value);
        return bytes;
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.US_ASCII);
    }
}
