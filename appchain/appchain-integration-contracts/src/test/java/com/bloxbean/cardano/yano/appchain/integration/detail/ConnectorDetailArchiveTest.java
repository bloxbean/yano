package com.bloxbean.cardano.yano.appchain.integration.detail;

import com.bloxbean.cardano.yano.appchain.integration.ipfs.CanonicalCid;
import com.bloxbean.cardano.yano.appchain.integration.ipfs.IpfsTargetFingerprint;
import com.bloxbean.cardano.yano.appchain.integration.kafka.KafkaDestinationFingerprint;
import com.bloxbean.cardano.yano.appchain.integration.objectstore.ObjectDestinationFingerprint;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

class ConnectorDetailArchiveTest {
    private static final String RAW_CID =
            "bafkreigh2akiscaildc7pmdz6w3m6fy42j2qcdq3q525bs4x36qj2mzyvi";

    @TempDir
    Path temporaryDirectory;

    @Test
    void envelopeAndDomainSeparatedHashMatchGoldenVector() throws Exception {
        Properties vectors = vectors();
        assertGolden(kafkaDocument(), "kafka", vectors);
        assertGolden(objectDocument(), "object", vectors);
        assertGolden(ipfsDocument(), "ipfs", vectors);

        ConnectorDetailDocumentV1 document = kafkaDocument();
        ConnectorDetailDocumentV1 decoded = ConnectorDetailDocumentV1.decode(document.encode());
        assertThat(decoded.action()).isEqualTo(ConnectorAction.KAFKA_PUBLISH);
        assertThat(decoded.data()).isInstanceOf(KafkaPublishDetailV1.class);
        assertThat(decoded.encode()).isEqualTo(document.encode());
    }

    @Test
    void everyConnectorDetailRoundTripsWithoutVolatileEnvelopeFields() {
        byte[] effect = repeat(0x55);
        byte[] target = repeat(0x11);
        List<ConnectorDetailData> details = List.of(
                new KafkaPublishDetailV1(target, 3, 42, 3, 14),
                new ObjectPutDetailV1(target, "version-1", "etag-1", repeat(0x22),
                        15, ObjectRetentionMode.COMPLIANCE, 1_900_000_000_000L),
                new IpfsPinDetailV1(target, CanonicalCid.fromText(RAW_CID), true, null));

        for (ConnectorDetailData detail : details) {
            ConnectorDetailDocumentV1 document = ConnectorDetailDocumentV1.of(effect, detail);
            ConnectorDetailDocumentV1 decoded = ConnectorDetailDocumentV1.decode(document.encode());
            assertThat(decoded.action()).isEqualTo(detail.action());
            assertThat(decoded.data().encode()).isEqualTo(detail.encode());
            assertThat(document.encode()).hasSizeLessThanOrEqualTo(8_192);
        }
    }

    @Test
    void archiveIsDurableContentAddressedIdempotentAndRetrievable() throws Exception {
        ConnectorDetailDocumentV1 document = kafkaDocument();
        ConnectorDetailHash first;
        try (FileConnectorDetailArchive archive = new FileConnectorDetailArchive(
                temporaryDirectory.resolve("details"))) {
            first = archive.archive(document);
            ConnectorDetailHash second = archive.archive(document);

            assertThat(second.bytes()).isEqualTo(first.bytes());
            assertThat(archive.retrieve(first)).contains(document);
            String relative = FileConnectorDetailArchive.relativeKey(first);
            assertThat(relative).matches("v1/blake2b-256/[0-9a-f]{2}/[0-9a-f]{62}\\.cbor");
            assertThat(Files.readAllBytes(temporaryDirectory.resolve("details").resolve(relative)))
                    .isEqualTo(document.encode());
        }

        // Reopening exercises the on-disk boundary rather than an in-memory
        // cache and verifies that the capability probe leaves no residue.
        try (FileConnectorDetailArchive reopened = new FileConnectorDetailArchive(
                temporaryDirectory.resolve("details"))) {
            assertThat(reopened.retrieve(first)).contains(document);
            try (var entries = Files.list(temporaryDirectory.resolve("details/v1/blake2b-256"))) {
                assertThat(entries.map(path -> path.getFileName().toString()))
                        .noneMatch(name -> name.startsWith(".archive-probe-"));
            }
        }
    }

    @Test
    void concurrentArchiveUsesOneCreateIfAbsentValue() throws Exception {
        ConnectorDetailDocumentV1 document = kafkaDocument();
        try (FileConnectorDetailArchive archive = new FileConnectorDetailArchive(
                temporaryDirectory.resolve("details"));
             var executor = Executors.newFixedThreadPool(8)) {
            List<Callable<ConnectorDetailHash>> calls = java.util.stream.IntStream.range(0, 16)
                    .mapToObj(ignored -> (Callable<ConnectorDetailHash>) () -> archive.archive(document))
                    .toList();
            var futures = executor.invokeAll(calls, 10, TimeUnit.SECONDS);
            assertThat(futures).noneMatch(future -> future.isCancelled() || !future.isDone());
            List<byte[]> hashes = futures.stream()
                    .map(future -> {
                        try {
                            return future.get(1, TimeUnit.SECONDS).bytes();
                        } catch (Exception exception) {
                            throw new AssertionError(exception);
                        }
                    }).toList();
            assertThat(hashes).allSatisfy(hash -> assertThat(hash).isEqualTo(hashes.get(0)));
            assertThat(archive.retrieve(new ConnectorDetailHash(hashes.get(0)))).contains(document);

            Path shard = temporaryDirectory.resolve("details")
                    .resolve(FileConnectorDetailArchive.relativeKey(new ConnectorDetailHash(hashes.get(0))))
                    .getParent();
            try (var entries = Files.list(shard)) {
                assertThat(entries.map(path -> path.getFileName().toString()))
                        .noneMatch(name -> name.startsWith(".detail-"));
            }
        }
    }

    @Test
    void archiveDetectsConflictCorruptionAndClosedUse() throws Exception {
        ConnectorDetailDocumentV1 document = kafkaDocument();
        FileConnectorDetailArchive archive = new FileConnectorDetailArchive(
                temporaryDirectory.resolve("details"));
        ConnectorDetailHash hash = archive.archive(document);
        Path path = temporaryDirectory.resolve("details")
                .resolve(FileConnectorDetailArchive.relativeKey(hash));
        Files.write(path, new byte[]{1, 2, 3});

        assertThatExceptionOfType(IOException.class).isThrownBy(() -> archive.retrieve(hash));
        assertThatExceptionOfType(IOException.class).isThrownBy(() -> archive.archive(document));
        archive.close();
        archive.close();
        assertThatExceptionOfType(IOException.class).isThrownBy(() -> archive.retrieve(hash));
    }

    @Test
    void retrieveUnknownHashInANeverCreatedShardReturnsEmpty() throws Exception {
        try (FileConnectorDetailArchive archive = new FileConnectorDetailArchive(
                temporaryDirectory.resolve("details"))) {
            ConnectorDetailHash unknown = new ConnectorDetailHash(repeat(0xab));

            assertThat(archive.retrieve(unknown)).isEmpty();
            assertThat(Files.exists(temporaryDirectory.resolve("details/v1/blake2b-256/ab")))
                    .isFalse();
        }
    }

    @Test
    void archiveRejectsSymlinkTraversal() throws Exception {
        ConnectorDetailDocumentV1 document = kafkaDocument();
        ConnectorDetailHash hash = ConnectorDetailHash.compute(document);
        Path root = temporaryDirectory.resolve("details");
        FileConnectorDetailArchive archive = new FileConnectorDetailArchive(root);
        String shard = hex(hash.bytes()).substring(0, 2);
        Path outside = temporaryDirectory.resolve("outside");
        Files.createDirectory(outside);
        Files.createSymbolicLink(root.resolve("v1/blake2b-256").resolve(shard), outside);

        assertThatExceptionOfType(IOException.class).isThrownBy(() -> archive.archive(document));
        archive.close();
        try (var files = Files.list(outside)) {
            assertThat(files).isEmpty();
        }
    }

    @Test
    void archiveRejectsReplacedInternalDirectoryBeforeCreatingOutside() throws Exception {
        ConnectorDetailDocumentV1 document = kafkaDocument();
        Path root = temporaryDirectory.resolve("details");
        FileConnectorDetailArchive archive = new FileConnectorDetailArchive(root);
        Path contentRoot = root.resolve("v1/blake2b-256");
        Files.delete(contentRoot);
        Path outside = temporaryDirectory.resolve("outside-content");
        Files.createDirectory(outside);
        Files.createSymbolicLink(contentRoot, outside);

        assertThatExceptionOfType(IOException.class).isThrownBy(() -> archive.archive(document));
        archive.close();
        try (var files = Files.list(outside)) {
            assertThat(files).isEmpty();
        }
    }

    @Test
    void archiveResolvesExternalSymlinkedAncestorButRejectsSymlinkedRoot() throws Exception {
        ConnectorDetailDocumentV1 document = kafkaDocument();
        Path realParent = temporaryDirectory.resolve("real-parent");
        Files.createDirectory(realParent);
        Path parentAlias = temporaryDirectory.resolve("parent-alias");
        Files.createSymbolicLink(parentAlias, realParent);

        Path configuredRoot = parentAlias.resolve("details");
        try (FileConnectorDetailArchive archive = new FileConnectorDetailArchive(configuredRoot)) {
            ConnectorDetailHash hash = archive.archive(document);
            assertThat(Files.readAllBytes(realParent.resolve("details")
                    .resolve(FileConnectorDetailArchive.relativeKey(hash))))
                    .isEqualTo(document.encode());
        }

        Path rootAlias = temporaryDirectory.resolve("root-alias");
        Files.createSymbolicLink(rootAlias, realParent.resolve("details"));
        assertThatExceptionOfType(IOException.class)
                .isThrownBy(() -> new FileConnectorDetailArchive(rootAlias))
                .withMessageContaining("root must not be a symbolic link");
    }

    @Test
    void archiveRequiresPrivatePreProvisionedDirectoriesAndFiles() throws Exception {
        Path root = temporaryDirectory.resolve("details");
        Files.createDirectory(root, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwxr-xr-x")));

        assertThatExceptionOfType(IOException.class)
                .isThrownBy(() -> new FileConnectorDetailArchive(root))
                .withMessageContaining("owner-only permissions");

        Files.setPosixFilePermissions(root, PosixFilePermissions.fromString("rwx------"));
        ConnectorDetailDocumentV1 document = kafkaDocument();
        try (FileConnectorDetailArchive archive = new FileConnectorDetailArchive(root)) {
            ConnectorDetailHash hash = archive.archive(document);
            Path entry = root.resolve(FileConnectorDetailArchive.relativeKey(hash));
            Set<PosixFilePermission> ownerOnly = PosixFilePermissions.fromString("rw-------");
            assertThat(Files.getPosixFilePermissions(entry)).isEqualTo(ownerOnly);

            Files.setPosixFilePermissions(entry, PosixFilePermissions.fromString("rw-r--r--"));
            assertThatExceptionOfType(IOException.class)
                    .isThrownBy(() -> archive.retrieve(hash))
                    .withMessageContaining("owner-only permissions");
        }
    }

    private static ConnectorDetailDocumentV1 kafkaDocument() {
        return ConnectorDetailDocumentV1.of(repeat(0x55),
                new KafkaPublishDetailV1(KafkaDestinationFingerprint.compute(
                        "broker-v1", "evidence.ready").bytes(), 3, 42, 3, 14));
    }

    private static ConnectorDetailDocumentV1 objectDocument() {
        return ConnectorDetailDocumentV1.of(repeat(0x55),
                new ObjectPutDetailV1(ObjectDestinationFingerprint.compute(
                        "archive-v1", "evidence", "immutable", "doc-1.json",
                        "sse-v1", "worm-v1").bytes(), "version-0001", "etag-0001",
                        repeat(0x22), 15, ObjectRetentionMode.GOVERNANCE,
                        1_735_689_600_000L));
    }

    private static ConnectorDetailDocumentV1 ipfsDocument() {
        return ConnectorDetailDocumentV1.of(repeat(0x55),
                new IpfsPinDetailV1(IpfsTargetFingerprint.compute("kubo-v1").bytes(),
                        CanonicalCid.fromText(RAW_CID),
                        true, "pin-42"));
    }

    private static void assertGolden(ConnectorDetailDocumentV1 document,
                                     String prefix,
                                     Properties vectors) {
        assertThat(hex(document.encode())).isEqualTo(vectors.getProperty(prefix + ".detail"));
        assertThat(hex(ConnectorDetailHash.compute(document).bytes()))
                .isEqualTo(vectors.getProperty(prefix + ".detail-hash"));
    }

    private static Properties vectors() throws IOException {
        Properties vectors = new Properties();
        try (var input = ConnectorDetailArchiveTest.class.getResourceAsStream(
                "/META-INF/yano/contracts/connectors/v1/golden-vectors.properties")) {
            vectors.load(input);
        }
        return vectors;
    }

    private static byte[] repeat(int value) {
        byte[] bytes = new byte[32];
        Arrays.fill(bytes, (byte) value);
        return bytes;
    }

    private static String hex(byte[] value) {
        return HexFormat.of().formatHex(value);
    }
}
