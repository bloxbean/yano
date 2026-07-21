package com.bloxbean.cardano.yano.appchain.examples.evidence.client;

import com.bloxbean.cardano.vds.core.api.NodeStore;
import com.bloxbean.cardano.vds.mpf.MpfTrie;
import com.bloxbean.cardano.yano.appchain.client.AppChainClient;
import com.bloxbean.cardano.yano.appchain.composite.contracts.CompositeCommitmentV1;
import com.bloxbean.cardano.yano.appchain.examples.evidence.EvidenceContract;
import com.bloxbean.cardano.yano.appchain.examples.evidence.query.EvidenceGetResponseV1;
import com.bloxbean.cardano.yano.appchain.examples.evidence.state.EvidenceEffectRef;
import com.bloxbean.cardano.yano.appchain.examples.evidence.state.EvidenceCompositeKeys;
import com.bloxbean.cardano.yano.appchain.examples.evidence.state.EvidenceHeadV1;
import com.bloxbean.cardano.yano.appchain.examples.evidence.state.EvidenceKeys;
import com.bloxbean.cardano.yano.appchain.examples.evidence.state.EvidenceRecordV1;
import com.bloxbean.cardano.yano.appchain.examples.evidence.state.EvidenceStatus;
import com.bloxbean.cardano.yano.appchain.integration.ipfs.CanonicalCid;
import com.bloxbean.cardano.yano.appchain.integration.ipfs.IpfsPinCommandV1;
import com.bloxbean.cardano.yano.appchain.integration.objectstore.DigestAlgorithm;
import com.bloxbean.cardano.yano.appchain.integration.objectstore.ObjectPutCommandV1;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Timeout(60)
class EvidenceClientTest {
    private static final String CHAIN = "evidence-chain";
    private static final String ID = "batch-001";
    private static final byte[] COMPOSITE_PROFILE =
            "canonical-evidence-v1-profile".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] COMPOSITE_PROFILE_DIGEST =
            CompositeCommitmentV1.profileDigest(COMPOSITE_PROFILE);
    private final List<FakeNode> nodes = new ArrayList<>();

    @AfterEach
    void stopServers() {
        nodes.forEach(FakeNode::close);
    }

    @Test
    void foundQueryVerifiesBothExactLeavesAndReturnsImmutableTypedEvidence() {
        Snapshot snapshot = Snapshot.found(ID, 1, 27, 1);
        FakeNode node = node(Plan.same(snapshot));

        VerifiedEvidence result = client(node, 1).queryVerified(ID, 0).orElseThrow();

        assertThat(result.chainId()).isEqualTo(CHAIN);
        assertThat(result.committedHeight()).isEqualTo(27);
        assertThat(result.stateRoot()).isEqualTo(snapshot.root);
        assertThat(result.head().evidenceId()).isEqualTo(ID);
        assertThat(result.record().businessVersion()).isEqualTo(1);
        assertThat(result.status()).isEqualTo(EvidenceStatus.STORAGE_PENDING);
        assertThat(node.queryCount).hasValue(1);
        assertThat(node.proofCount).hasValue(2);

        byte[] leakedRoot = result.stateRoot();
        byte[] leakedValue = result.recordValue();
        leakedRoot[0] ^= 1;
        leakedValue[0] ^= 1;
        assertThat(result.stateRoot()).isEqualTo(snapshot.root);
        assertThat(result.recordValue()).isEqualTo(snapshot.response.recordValue());
    }

    @Test
    void compositeAliasTranslatesLogicalEvidenceKeysToPhysicalProofKeys() {
        Snapshot snapshot = Snapshot.foundComposite(ID, 1, 27, 1);
        Plan composite = new Plan(snapshot, snapshot, Mutation.NONE,
                EvidenceCompositeKeys.STATE_MACHINE_ID, null, null, false, false);
        FakeNode node = node(composite);

        VerifiedEvidence result = compositeClient(node, 1).queryVerified(ID, 1).orElseThrow();

        assertThat(result.record().evidenceId()).isEqualTo(ID);
        assertThat(node.queryCount).hasValue(1);
        assertThat(node.proofCount).hasValue(3);
        assertThat(result.stateMachineId()).isEqualTo(CompositeCommitmentV1.STATE_MACHINE_ID);
        assertThat(result.compositeProfileDigest()).isEqualTo(COMPOSITE_PROFILE_DIGEST);
        assertThat(result.headKey()).isEqualTo(snapshot.response.headKey());
        assertThat(result.physicalHeadKey()).isEqualTo(
                CompositeCommitmentV1.componentKey("evidence", snapshot.response.headKey()));
        EvidenceProofVerifier.verifyComposite(
                new AppChainClient.QueryResult(CHAIN, EvidenceCompositeKeys.STATE_MACHINE_ID,
                        snapshot.height, snapshot.root, snapshot.response.encode()),
                snapshot.response,
                snapshot.appProof(EvidenceCompositeKeys.physicalKey(snapshot.response.headKey()), CHAIN),
                snapshot.appProof(EvidenceCompositeKeys.physicalKey(snapshot.response.recordKey()), CHAIN),
                snapshot.appProof(CompositeCommitmentV1.profileMarkerKey(), CHAIN),
                COMPOSITE_PROFILE_DIGEST, CHAIN, ID, 1);

        assertFailure(() -> EvidenceProofVerifier.verify(
                        new AppChainClient.QueryResult(CHAIN,
                                CompositeCommitmentV1.STATE_MACHINE_ID,
                                snapshot.height, snapshot.root, snapshot.response.encode()),
                        snapshot.response,
                        snapshot.appProof(result.physicalHeadKey(), CHAIN),
                        snapshot.appProof(result.physicalRecordKey(), CHAIN),
                        CHAIN, ID, 1),
                EvidenceClientError.WRONG_STATE_MACHINE);
        byte[] wrongDigest = COMPOSITE_PROFILE_DIGEST.clone();
        wrongDigest[0] ^= 1;
        assertFailure(() -> new EvidenceClient(validTransport(node.baseUrl()), CHAIN,
                        1, wrongDigest).queryVerified(ID, 1),
                EvidenceClientError.WRONG_STATE_MACHINE);
    }

    @Test
    void offlineVerifierBindsPayloadAndSupportsProvenAbsence() {
        Snapshot found = Snapshot.found(ID, 1, 28, 15);
        AppChainClient.QueryResult query = found.queryResult(CHAIN);
        VerifiedEvidence verified = EvidenceProofVerifier.verify(
                query, found.response, found.appProof(found.response.headKey(), CHAIN),
                found.appProof(found.response.recordKey(), CHAIN), CHAIN, ID, 1);
        assertThat(verified.record().evidenceId()).isEqualTo(ID);

        Snapshot unrelated = Snapshot.found("batch-002", 1, 28, 15);
        assertFailure(() -> EvidenceProofVerifier.verify(
                        query, unrelated.response,
                        found.appProof(found.response.headKey(), CHAIN),
                        found.appProof(found.response.recordKey(), CHAIN), CHAIN, ID, 1),
                EvidenceClientError.RESPONSE_MISMATCH);

        Snapshot absent = Snapshot.absent(ID, 29, 16);
        EvidenceProofVerifier.verifyAbsence(absent.queryResult(CHAIN),
                absent.appProof(EvidenceKeys.headKey(ID), CHAIN), CHAIN, ID, 0);
    }

    @Test
    void explicitFutureVersionAbsenceIsProvenByTheCommittedHead() {
        Snapshot futureAbsent = Snapshot.futureVersionAbsent(ID, 1, 30, 18);
        FakeNode node = node(Plan.same(futureAbsent));

        assertThat(client(node, 1).queryVerified(ID, 2)).isEmpty();
        assertThat(node.proofCount).hasValue(1);

        FakeNode latestCannotBeAbsent = node(Plan.same(futureAbsent));
        assertFailure(() -> client(latestCannotBeAbsent, 1).queryVerified(ID, 0),
                EvidenceClientError.PROOF_INVALID);

        FakeNode existingVersionCannotBeAbsent = node(Plan.same(futureAbsent));
        assertFailure(() -> client(existingVersionCannotBeAbsent, 1).queryVerified(ID, 1),
                EvidenceClientError.PROOF_INVALID);

        Snapshot versionTwo = Snapshot.found(ID, 2, 31, 19);
        FakeNode publishedDuringRead = node(
                new Plan(futureAbsent, versionTwo, Mutation.NONE,
                        null, null, null, false, false),
                Plan.same(versionTwo));
        VerifiedEvidence nowPresent = client(publishedDuringRead, 3)
                .queryVerified(ID, 2).orElseThrow();
        assertThat(nowPresent.record().businessVersion()).isEqualTo(2);
        assertThat(publishedDuringRead.queryCount).hasValue(2);
    }

    @Test
    void notFoundRequiresAndVerifiesAnExclusionProof() {
        Snapshot absent = Snapshot.absent(ID, 31, 2);
        FakeNode node = node(Plan.same(absent));

        assertThat(client(node, 1).queryVerified(ID, 0)).isEmpty();
        assertThat(node.queryCount).hasValue(1);
        assertThat(node.proofCount).hasValue(1);

        FakeNode invalid = node(new Plan(absent, absent, Mutation.WRONG_WIRE,
                null, null, null, false, false));
        assertFailure(() -> client(invalid, 1).queryVerified(ID, 0),
                EvidenceClientError.PROOF_INVALID);

        FakeNode missing = node(new Plan(absent, absent, Mutation.NONE,
                null, null, null, true, false));
        assertFailure(() -> client(missing, 1).queryVerified(ID, 0),
                EvidenceClientError.PROOF_MISSING);
    }

    @Test
    void tamperedProofValuesKeysAndWiresAlwaysFailClosed() {
        Snapshot snapshot = Snapshot.found(ID, 1, 40, 3);

        FakeNode wrongValue = node(new Plan(snapshot, snapshot, Mutation.WRONG_VALUE,
                null, null, null, false, false));
        assertFailure(() -> client(wrongValue, 1).queryVerified(ID, 1),
                EvidenceClientError.PROOF_INVALID);

        FakeNode wrongWire = node(new Plan(snapshot, snapshot, Mutation.WRONG_WIRE,
                null, null, null, false, false));
        assertFailure(() -> client(wrongWire, 1).queryVerified(ID, 1),
                EvidenceClientError.PROOF_INVALID);

        // The generic transport rejects a key other than the one requested
        // before the domain verifier sees it. The companion still sanitizes it.
        FakeNode wrongKey = node(new Plan(snapshot, snapshot, Mutation.WRONG_KEY,
                null, null, null, false, false));
        assertFailure(() -> client(wrongKey, 1).queryVerified(ID, 1),
                EvidenceClientError.TRANSPORT_FAILURE);
    }

    @Test
    void movingRootRetriesWholeSequenceAndNeverMixesSnapshots() {
        Snapshot first = Snapshot.found(ID, 1, 50, 4);
        Snapshot second = Snapshot.found(ID, 1, 51, 5);
        FakeNode converging = node(new Plan(first, second, Mutation.NONE,
                        null, null, null, false, false),
                Plan.same(second));

        VerifiedEvidence verified = client(converging, 3)
                .queryVerified(ID, 1).orElseThrow();
        assertThat(verified.committedHeight()).isEqualTo(51);
        assertThat(verified.stateRoot()).isEqualTo(second.root);
        assertThat(converging.queryCount).hasValue(2);
        assertThat(converging.proofCount).hasValue(4);

        FakeNode neverConverges = node(
                new Plan(first, second, Mutation.NONE,
                        null, null, null, false, false));
        assertFailure(() -> client(neverConverges, 2).queryVerified(ID, 1),
                EvidenceClientError.SNAPSHOT_RACE_EXHAUSTED);
        assertThat(neverConverges.queryCount).hasValue(2);
    }

    @Test
    void insertionOrHeadUpdateBetweenQueryAndProofIsRetriedNotMisclassified() {
        Snapshot absent = Snapshot.absent(ID, 52, 12);
        Snapshot versionOne = Snapshot.found(ID, 1, 53, 13);
        FakeNode inserted = node(new Plan(absent, versionOne, Mutation.NONE,
                        null, null, null, false, false),
                Plan.same(versionOne));

        VerifiedEvidence first = client(inserted, 3)
                .queryVerified(ID, 0).orElseThrow();
        assertThat(first.record().businessVersion()).isEqualTo(1);
        assertThat(inserted.queryCount).hasValue(2);

        Snapshot versionTwo = Snapshot.found(ID, 2, 54, 14);
        FakeNode updated = node(new Plan(versionOne, versionTwo, Mutation.NONE,
                        null, null, null, false, false),
                Plan.same(versionTwo));

        VerifiedEvidence latest = client(updated, 3)
                .queryVerified(ID, 0).orElseThrow();
        assertThat(latest.record().businessVersion()).isEqualTo(2);
        assertThat(updated.queryCount).hasValue(2);
    }

    @Test
    void tamperedRootOrHeightIsNotAcceptedAsTheQuerySnapshot() {
        Snapshot snapshot = Snapshot.found(ID, 1, 60, 6);

        FakeNode root = node(new Plan(snapshot, snapshot, Mutation.WRONG_ROOT,
                null, null, null, false, false));
        assertFailure(() -> client(root, 1).queryVerified(ID, 1),
                EvidenceClientError.SNAPSHOT_RACE_EXHAUSTED);

        FakeNode height = node(new Plan(snapshot, snapshot, Mutation.WRONG_HEIGHT,
                null, null, null, false, false));
        assertFailure(() -> client(height, 1).queryVerified(ID, 1),
                EvidenceClientError.SNAPSHOT_RACE_EXHAUSTED);

        FakeNode legacyWithoutHeight = node(new Plan(snapshot, snapshot,
                Mutation.OMIT_HEIGHT, null, null, null, false, false));
        assertFailure(() -> client(legacyWithoutHeight, 1).queryVerified(ID, 1),
                EvidenceClientError.PROOF_INVALID);
    }

    @Test
    void wrongMachineChainIdAndVersionAreRejected() {
        Snapshot snapshot = Snapshot.found(ID, 1, 70, 7);

        FakeNode machine = node(new Plan(snapshot, snapshot, Mutation.NONE,
                "some-other-machine", null, null, false, false));
        assertFailure(() -> client(machine, 1).queryVerified(ID, 1),
                EvidenceClientError.WRONG_STATE_MACHINE);

        // AppChainClient itself rejects a query-chain mismatch before returning
        // the envelope; the domain wrapper maps all transport details to one code.
        FakeNode queryChain = node(new Plan(snapshot, snapshot, Mutation.NONE,
                null, "wrong-chain", null, false, false));
        assertFailure(() -> client(queryChain, 1).queryVerified(ID, 1),
                EvidenceClientError.TRANSPORT_FAILURE);

        FakeNode proofChain = node(new Plan(snapshot, snapshot, Mutation.WRONG_CHAIN,
                null, null, null, false, false));
        assertFailure(() -> client(proofChain, 1).queryVerified(ID, 1),
                EvidenceClientError.TRANSPORT_FAILURE);

        FakeNode version = node(Plan.same(snapshot));
        assertFailure(() -> client(version, 1).queryVerified(ID, 2),
                EvidenceClientError.RESPONSE_MISMATCH);
        assertThat(version.proofCount).hasValue(0);

        Snapshot anotherId = Snapshot.found("batch-002", 1, 71, 8);
        FakeNode id = node(Plan.same(anotherId));
        assertFailure(() -> client(id, 1).queryVerified(ID, 1),
                EvidenceClientError.RESPONSE_MISMATCH);
        assertThat(id.proofCount).hasValue(0);
    }

    @Test
    void malformedAndOversizedDomainPayloadsAndInputsAreRejected() {
        Snapshot snapshot = Snapshot.found(ID, 1, 80, 9);
        FakeNode malformed = node(new Plan(snapshot, snapshot, Mutation.NONE,
                null, null, new byte[]{(byte) 0xff}, false, false));
        assertFailure(() -> client(malformed, 1).queryVerified(ID, 1),
                EvidenceClientError.RESPONSE_MISMATCH);

        byte[] oversized = new byte[EvidenceContract.MAX_QUERY_RESPONSE_BYTES + 1];
        FakeNode tooLarge = node(new Plan(snapshot, snapshot, Mutation.NONE,
                null, null, oversized, false, false));
        assertFailure(() -> client(tooLarge, 1).queryVerified(ID, 1),
                EvidenceClientError.RESPONSE_MISMATCH);

        EvidenceClient valid = client(node(Plan.same(snapshot)), 1);
        assertFailure(() -> valid.queryVerified("A", 1),
                EvidenceClientError.INVALID_ARGUMENT);
        assertFailure(() -> valid.queryVerified("a".repeat(64), 1),
                EvidenceClientError.INVALID_ARGUMENT);
        assertFailure(() -> valid.queryVerified(ID, -1),
                EvidenceClientError.INVALID_ARGUMENT);
        assertFailure(() -> valid.notify(ID, 0),
                EvidenceClientError.INVALID_ARGUMENT);
        assertFailure(() -> new EvidenceClient(null, CHAIN),
                EvidenceClientError.INVALID_ARGUMENT);
        assertFailure(() -> new EvidenceClient(validTransport("http://localhost:1"), " "),
                EvidenceClientError.INVALID_ARGUMENT);
        assertFailure(() -> new EvidenceClient(validTransport("http://localhost:1"), CHAIN, 9),
                EvidenceClientError.INVALID_ARGUMENT);
    }

    @Test
    void submissionHelpersUseOnlyCanonicalTopicAndSanitizeFailures() {
        Snapshot snapshot = Snapshot.found(ID, 1, 90, 10);
        FakeNode node = node(Plan.same(snapshot));
        EvidenceClient client = client(node, 1);

        AppChainClient.SubmitResult result = client.notify(ID, 1);
        assertThat(result.chainId()).isEqualTo(CHAIN);
        assertThat(result.topic()).isEqualTo(EvidenceContract.COMMAND_TOPIC);
        assertThat(node.lastMessageBody).contains("\"topic\":\"evidence.command.v1\"");
        assertThat(node.lastMessageBody).contains("\"bodyHex\"");

        node.messageFailureBody = "credential=do-not-reflect";
        EvidenceClientException failure = capture(() -> client.notify(ID, 1));
        assertThat(failure.code()).isEqualTo(EvidenceClientError.TRANSPORT_FAILURE);
        assertThat(failure.getMessage()).doesNotContain("credential", "do-not-reflect");
        assertThat(failure.getCause()).isNull();
    }

    @Test
    void queryTransportFailureNeverReflectsUntrustedResponseText() {
        Snapshot snapshot = Snapshot.found(ID, 1, 100, 11);
        FakeNode node = node(new Plan(snapshot, snapshot, Mutation.NONE,
                null, null, null, false, true));

        EvidenceClientException failure = capture(
                () -> client(node, 1).queryVerified(ID, 1));
        assertThat(failure.code()).isEqualTo(EvidenceClientError.TRANSPORT_FAILURE);
        assertThat(failure.getMessage()).doesNotContain("secret", "attacker");
        assertThat(failure.getCause()).isNull();
    }

    @Test
    void malformedPublicProofInputsNeverEscapeAsRawOrReflectedExceptions() {
        Snapshot snapshot = Snapshot.found(ID, 1, 110, 17);
        AppChainClient.QueryResult query = snapshot.queryResult(CHAIN);
        Random random = new Random(0x59414e4fL);

        for (int i = 0; i < 2_000; i++) {
            String attackerText = randomAscii(random, 32 + random.nextInt(65));
            AppChainClient.Proof malformed = new AppChainClient.Proof(
                    attackerText, CHAIN, attackerText, attackerText,
                    attackerText, null, 110L);
            EvidenceClientException failure = capture(() -> EvidenceProofVerifier.verify(
                    query, snapshot.response, malformed, malformed, CHAIN, ID, 1));
            assertThat(failure.code()).isEqualTo(EvidenceClientError.PROOF_INVALID);
            assertThat(failure.getMessage()).doesNotContain(attackerText);
            assertThat(failure.getCause()).isNull();
        }

        String oversized = "a".repeat(2 * 1024 * 1024 + 2);
        AppChainClient.Proof malformed = new AppChainClient.Proof(
                oversized, CHAIN, "00".repeat(32), "00", "00", null, 110L);
        assertFailure(() -> EvidenceProofVerifier.verify(
                        query, snapshot.response, malformed, malformed, CHAIN, ID, 1),
                EvidenceClientError.PROOF_INVALID);
    }

    private FakeNode node(Plan... plans) {
        FakeNode node = new FakeNode(List.of(plans));
        nodes.add(node);
        return node;
    }

    private static EvidenceClient client(FakeNode node, int attempts) {
        return new EvidenceClient(validTransport(node.baseUrl()), CHAIN, attempts);
    }

    private static EvidenceClient compositeClient(FakeNode node, int attempts) {
        return new EvidenceClient(validTransport(node.baseUrl()), CHAIN,
                attempts, COMPOSITE_PROFILE_DIGEST);
    }

    private static AppChainClient validTransport(String baseUrl) {
        return AppChainClient.builder(baseUrl).chainId(CHAIN).build();
    }

    private static void assertFailure(ThrowingCall call, EvidenceClientError code) {
        assertThatThrownBy(call::run)
                .isInstanceOfSatisfying(EvidenceClientException.class,
                        failure -> assertThat(failure.code()).isEqualTo(code));
    }

    private static EvidenceClientException capture(ThrowingCall call) {
        try {
            call.run();
            throw new AssertionError("expected evidence client failure");
        } catch (EvidenceClientException failure) {
            return failure;
        } catch (Exception other) {
            throw new AssertionError(other);
        }
    }

    @FunctionalInterface
    private interface ThrowingCall {
        void run() throws Exception;
    }

    private enum Mutation {
        NONE,
        WRONG_VALUE,
        WRONG_KEY,
        WRONG_ROOT,
        WRONG_WIRE,
        WRONG_CHAIN,
        WRONG_HEIGHT,
        OMIT_HEIGHT
    }

    private record Plan(Snapshot querySnapshot,
                        Snapshot proofSnapshot,
                        Mutation mutation,
                        String machineId,
                        String queryChainId,
                        byte[] payloadOverride,
                        boolean missingProof,
                        boolean failQuery) {
        static Plan same(Snapshot snapshot) {
            return new Plan(snapshot, snapshot, Mutation.NONE,
                    null, null, null, false, false);
        }
    }

    private static final class FakeNode implements AutoCloseable {
        private final List<Plan> plans;
        private final HttpServer server;
        private final AtomicInteger queryCount = new AtomicInteger();
        private final AtomicInteger proofCount = new AtomicInteger();
        private volatile Plan current;
        private volatile String lastMessageBody;
        private volatile String messageFailureBody;

        private FakeNode(List<Plan> plans) {
            this.plans = plans;
            try {
                server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
            server.createContext("/api/v1/app-chain/chains/" + CHAIN + "/query/evidence/get",
                    this::query);
            server.createContext("/api/v1/app-chain/chains/" + CHAIN + "/proof",
                    this::proof);
            server.createContext("/api/v1/app-chain/chains/" + CHAIN + "/messages",
                    this::message);
            server.start();
        }

        private String baseUrl() {
            return "http://localhost:" + server.getAddress().getPort() + "/api/v1";
        }

        private void query(HttpExchange exchange) throws IOException {
            int index = queryCount.getAndIncrement();
            current = plans.get(Math.min(index, plans.size() - 1));
            if (current.failQuery) {
                respond(exchange, 500,
                        "{\"code\":\"INTERNAL_ERROR\",\"error\":\"secret-attacker-text\"}");
                return;
            }
            Snapshot snapshot = current.querySnapshot;
            byte[] payload = current.payloadOverride != null
                    ? current.payloadOverride : snapshot.response.encode();
            String json = "{\"chainId\":\"" + valueOr(current.queryChainId, CHAIN)
                    + "\",\"stateMachineId\":\""
                    + valueOr(current.machineId, EvidenceContract.STATE_MACHINE_ID)
                    + "\",\"committedHeight\":" + snapshot.height
                    + ",\"stateRoot\":\"" + hex(snapshot.root)
                    + "\",\"payloadHex\":\"" + hex(payload) + "\"}";
            respond(exchange, 200, json);
        }

        private void proof(HttpExchange exchange) throws IOException {
            proofCount.incrementAndGet();
            Plan plan = current;
            if (plan == null || plan.missingProof) {
                respond(exchange, 404, "{\"error\":\"missing\"}");
                return;
            }
            String path = exchange.getRequestURI().getRawPath();
            String encodedKey = path.substring(path.lastIndexOf('/') + 1);
            byte[] requestedKey = decodeHex(encodedKey);
            Snapshot snapshot = plan.proofSnapshot;
            byte[] key = requestedKey.clone();
            byte[] value = snapshot.value(key);
            byte[] root = snapshot.root.clone();
            byte[] wire = snapshot.proof(key);
            String chain = CHAIN;
            Long committedHeight = snapshot.height;

            switch (plan.mutation) {
                case WRONG_VALUE -> value = value == null ? new byte[]{1} : flip(value);
                case WRONG_KEY -> key = flip(key);
                case WRONG_ROOT -> root = flip(root);
                case WRONG_WIRE -> wire = flip(wire);
                case WRONG_CHAIN -> chain = "wrong-chain";
                case WRONG_HEIGHT -> committedHeight = snapshot.height + 1;
                case OMIT_HEIGHT -> committedHeight = null;
                case NONE -> {
                }
            }

            StringBuilder json = new StringBuilder("{\"key\":\"")
                    .append(hex(key)).append("\",\"chainId\":\"")
                    .append(chain).append("\",\"stateRoot\":\"")
                    .append(hex(root)).append("\",\"proofWireHex\":\"")
                    .append(hex(wire)).append('"');
            if (value != null) {
                json.append(",\"valueHex\":\"").append(hex(value)).append('"');
            }
            if (committedHeight != null) {
                json.append(",\"committedHeight\":").append(committedHeight);
            }
            json.append('}');
            respond(exchange, 200, json.toString());
        }

        private void message(HttpExchange exchange) throws IOException {
            lastMessageBody = new String(exchange.getRequestBody().readAllBytes(),
                    StandardCharsets.UTF_8);
            if (messageFailureBody != null) {
                respond(exchange, 500, "{\"error\":\"" + messageFailureBody + "\"}");
                return;
            }
            respond(exchange, 202, "{\"messageId\":\"" + "ab".repeat(32)
                    + "\",\"chainId\":\"" + CHAIN
                    + "\",\"topic\":\"" + EvidenceContract.COMMAND_TOPIC + "\"}");
        }

        @Override
        public void close() {
            server.stop(0);
        }

        private static void respond(HttpExchange exchange, int status, String body)
                throws IOException {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        }
    }

    private static final class Snapshot {
        private final long height;
        private final EvidenceGetResponseV1 response;
        private final MpfTrie trie;
        private final Map<String, byte[]> values;
        private final byte[] root;

        private Snapshot(long height, EvidenceGetResponseV1 response,
                         MpfTrie trie, Map<String, byte[]> values) {
            this.height = height;
            this.response = response;
            this.trie = trie;
            this.values = values;
            this.root = trie.getRootHash();
        }

        private static Snapshot found(String evidenceId, long version,
                                      long height, int extra) {
            EvidenceHeadV1 head = new EvidenceHeadV1(evidenceId, repeat(0x41), version);
            EvidenceRecordV1 record = record(evidenceId, version);
            EvidenceGetResponseV1 response = EvidenceGetResponseV1.found(head, record);
            Map<String, byte[]> values = new HashMap<>();
            values.put(hex(response.headKey()), response.headValue());
            values.put(hex(response.recordKey()), response.recordValue());
            values.put(hex(new byte[]{0x7f, (byte) extra}), new byte[]{(byte) extra});
            return build(height, response, values);
        }

        private static Snapshot foundComposite(String evidenceId, long version,
                                               long height, int extra) {
            EvidenceHeadV1 head = new EvidenceHeadV1(evidenceId, repeat(0x41), version);
            EvidenceRecordV1 record = record(evidenceId, version);
            EvidenceGetResponseV1 response = EvidenceGetResponseV1.found(head, record);
            Map<String, byte[]> values = new HashMap<>();
            values.put(hex(EvidenceCompositeKeys.physicalKey(response.headKey())),
                    response.headValue());
            values.put(hex(EvidenceCompositeKeys.physicalKey(response.recordKey())),
                    response.recordValue());
            values.put(hex(CompositeCommitmentV1.profileMarkerKey()), COMPOSITE_PROFILE);
            values.put(hex(new byte[]{0x7f, (byte) extra}), new byte[]{(byte) extra});
            return build(height, response, values);
        }

        private static Snapshot absent(String evidenceId, long height, int extra) {
            Map<String, byte[]> values = new HashMap<>();
            values.put(hex(new byte[]{0x6f, (byte) extra}), new byte[]{(byte) extra});
            return build(height, EvidenceGetResponseV1.notFound(), values);
        }

        private static Snapshot futureVersionAbsent(String evidenceId, long latestVersion,
                                                    long height, int extra) {
            Snapshot present = found(evidenceId, latestVersion, height, extra);
            return build(height, EvidenceGetResponseV1.notFound(),
                    new HashMap<>(present.values));
        }

        private static Snapshot build(long height, EvidenceGetResponseV1 response,
                                      Map<String, byte[]> values) {
            MpfTrie trie = new MpfTrie(new MapNodeStore());
            values.forEach((key, value) -> trie.put(decodeHex(key), value));
            return new Snapshot(height, response, trie, values);
        }

        private byte[] value(byte[] key) {
            byte[] value = values.get(hex(key));
            return value == null ? null : value.clone();
        }

        private byte[] proof(byte[] key) {
            return trie.getProofWire(key).orElseThrow(
                    () -> new AssertionError("missing test MPF proof"));
        }

        private AppChainClient.QueryResult queryResult(String chainId) {
            return new AppChainClient.QueryResult(chainId,
                    EvidenceContract.STATE_MACHINE_ID, height, root, response.encode());
        }

        private AppChainClient.Proof appProof(byte[] key, String chainId) {
            byte[] value = value(key);
            return new AppChainClient.Proof(hex(key), chainId, hex(root),
                    hex(proof(key)), value == null ? null : hex(value), null, height);
        }
    }

    private static EvidenceRecordV1 record(String id, long version) {
        byte[] digest = repeat(0x11);
        ObjectPutCommandV1 object = new ObjectPutCommandV1(
                "archive-v1", "incoming/certificate.pdf", "evidence/certificate.pdf",
                DigestAlgorithm.SHA_256, digest, 15, "application/pdf", null);
        byte[] cid = new byte[36];
        cid[0] = 1;
        cid[1] = 0x55;
        cid[2] = 0x12;
        cid[3] = 0x20;
        System.arraycopy(digest, 0, cid, 4, digest.length);
        IpfsPinCommandV1 ipfs = new IpfsPinCommandV1(
                "kubo-v1", CanonicalCid.fromBytes(cid), true, "single-v1");
        return new EvidenceRecordV1(
                id, version, repeat(0x41), repeat(0x42), object.encode(), repeat(0x21),
                new EvidenceEffectRef(10, 0), null, ipfs.encode(), repeat(0x22),
                new EvidenceEffectRef(10, 1), null, "primary-v1", "evidence-ready",
                repeat(0x23), null, null, null);
    }

    private static final class MapNodeStore implements NodeStore {
        private final Map<String, byte[]> nodes = new HashMap<>();

        @Override
        public byte[] get(byte[] hash) {
            return nodes.get(hex(hash));
        }

        @Override
        public void put(byte[] hash, byte[] nodeBytes) {
            nodes.put(hex(hash), nodeBytes.clone());
        }

        @Override
        public void delete(byte[] hash) {
            nodes.remove(hex(hash));
        }
    }

    private static byte[] repeat(int value) {
        byte[] bytes = new byte[32];
        Arrays.fill(bytes, (byte) value);
        return bytes;
    }

    private static byte[] flip(byte[] value) {
        byte[] changed = value.clone();
        changed[0] ^= 1;
        return changed;
    }

    private static String valueOr(String value, String fallback) {
        return value != null ? value : fallback;
    }

    private static String hex(byte[] value) {
        char[] chars = new char[value.length * 2];
        char[] alphabet = "0123456789abcdef".toCharArray();
        for (int i = 0; i < value.length; i++) {
            int unsigned = value[i] & 0xff;
            chars[i * 2] = alphabet[unsigned >>> 4];
            chars[i * 2 + 1] = alphabet[unsigned & 0x0f];
        }
        return new String(chars);
    }

    private static byte[] decodeHex(String value) {
        byte[] bytes = new byte[value.length() / 2];
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = (byte) ((Character.digit(value.charAt(i * 2), 16) << 4)
                    | Character.digit(value.charAt(i * 2 + 1), 16));
        }
        return bytes;
    }

    private static String randomAscii(Random random, int length) {
        StringBuilder value = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            value.append((char) (32 + random.nextInt(95)));
        }
        return value.toString();
    }
}
