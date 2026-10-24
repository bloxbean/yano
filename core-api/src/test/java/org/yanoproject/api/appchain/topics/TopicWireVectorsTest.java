package org.yanoproject.api.appchain.topics;

import com.bloxbean.cardano.yaci.core.util.HexUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.yanoproject.api.appchain.topics.TopicWire.Delegation;
import org.yanoproject.api.appchain.topics.TopicWire.EffectiveOutcome;
import org.yanoproject.api.appchain.topics.TopicWire.Frame;
import org.yanoproject.api.appchain.topics.TopicWire.KeySet;
import org.yanoproject.api.appchain.topics.TopicWire.OutcomeKind;
import org.yanoproject.api.appchain.topics.TopicWire.RegistryCommand;
import org.yanoproject.api.appchain.topics.TopicWire.ResolvedPolicy;
import org.yanoproject.api.appchain.topics.TopicWire.Revoke;
import org.yanoproject.api.appchain.topics.TopicWire.Signature;
import org.yanoproject.api.appchain.topics.TopicWire.Source;
import org.yanoproject.api.appchain.topics.TopicWire.WriteStatement;
import org.yanoproject.api.appchain.topics.TopicWire.Writers;
import org.yanoproject.api.util.StrictEd25519;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V2: the Java codec against vectors produced by an independent implementation
 * ({@code scripts/topics/generate_topic_vectors.py}: cbor2 + PyNaCl), never by the code under test.
 */
class TopicWireVectorsTest {
    private static final Map<String, Function<byte[], ?>> DECODERS = Map.ofEntries(
            Map.entry("config", TopicWireCodec::decodeConfig),
            Map.entry("entry-record", TopicWireCodec::decodeEntryRecord),
            Map.entry("queued-revision", TopicWireCodec::decodeQueuedRevision),
            Map.entry("expiry-batch", TopicWireCodec::decodeExpiryBatch),
            Map.entry("expiry-item", TopicWireCodec::decodeExpiryItem),
            Map.entry("activation", TopicWireCodec::decodeActivation),
            Map.entry("receipt", TopicWireCodec::decodeReceipt),
            Map.entry("governance-pending", TopicWireCodec::decodeGovernancePending),
            Map.entry("delegation", TopicWireCodec::decodeDelegation),
            Map.entry("registry-command", TopicWireCodec::decodeRegistryCommand),
            Map.entry("governance-command", TopicWireCodec::decodeGovernanceCommand),
            Map.entry("frame", TopicWireCodec::decodeFrame));
    private static final Map<String, Function<Object, byte[]>> ENCODERS = encoders();

    @Test
    void decodesAndReencodesEveryValidVectorByteForByte() throws IOException {
        JsonNode root = vectors();
        int checked = 0;
        for (JsonNode vector : root.get("vectors")) {
            String type = vector.get("type").asText();
            if (vector.has("invalid") || type.equals("effective-outcome")) {
                continue;
            }
            byte[] bytes = HexUtil.decodeHexString(vector.get("hex").asText());
            Object decoded = DECODERS.get(type).apply(bytes);
            assertThat(ENCODERS.get(type).apply(decoded)).as(vector.get("name").asText()).isEqualTo(bytes);
            checked++;
        }
        assertThat(checked).isEqualTo(29);
    }

    @Test
    void refusesEveryInvalidVector() throws IOException {
        int checked = 0;
        for (JsonNode vector : vectors().get("vectors")) {
            if (!vector.has("invalid")) {
                continue;
            }
            byte[] bytes = HexUtil.decodeHexString(vector.get("hex").asText());
            assertThatThrownBy(() -> DECODERS.get(vector.get("type").asText()).apply(bytes))
                    .as(vector.get("name").asText() + ": " + vector.get("invalid").asText())
                    .isInstanceOf(TopicWireCodec.TopicWireException.class);
            checked++;
        }
        assertThat(checked).isEqualTo(23);
    }

    @Test
    void delegationHashAndIssuerSignatureMatch() throws IOException {
        JsonNode vector = vector("delegation");
        Delegation delegation = TopicWireCodec.decodeDelegation(hex(vector, "hex"));
        byte[] signed = TopicWireCodec.delegationSignedBytes(
                vector.get("chainId").asText(), vector.get("generationId").asText(), delegation);

        assertThat(signed).isEqualTo(hex(vector, "signedBytes"));
        assertThat(TopicWireCodec.delegationHash(vector.get("chainId").asText(),
                vector.get("generationId").asText(), delegation)).isEqualTo(vector.get("delegationHash").asText());
        assertThat(StrictEd25519.verify(HexUtil.decodeHexString(delegation.issuer()), delegation.sig(), signed))
                .isTrue();
    }

    @Test
    void commandStatementsAndSignaturesMatch() throws IOException {
        int checked = 0;
        for (JsonNode vector : vectors().get("vectors")) {
            if (!"registry-command".equals(vector.get("type").asText()) || vector.has("invalid")) {
                continue;
            }
            RegistryCommand command = TopicWireCodec.decodeRegistryCommand(hex(vector, "hex"));
            byte[] statement = TopicWireCodec.encodeCommandStatement(command.statement());
            assertThat(statement).as(vector.get("name").asText()).isEqualTo(hex(vector, "statementHex"));
            for (Signature signature : command.signatures()) {
                assertThat(StrictEd25519.verify(HexUtil.decodeHexString(signature.key()), signature.sig(),
                        statement)).as(vector.get("name").asText()).isTrue();
            }
            if (command.statement().operation() instanceof Revoke revoke) {
                assertThat(revoke.delegation().scope()).isEqualTo(command.statement().target());
            }
            checked++;
        }
        assertThat(checked).isEqualTo(6);
    }

    @Test
    void writeStatementsHashesAndSignaturesMatch() throws IOException {
        JsonNode root = vectors();
        String chainId = root.get("chainId").asText();
        String generationId = root.get("generationId").asText();
        for (String name : List.of("frame-delegated", "frame-direct-2-of-2")) {
            JsonNode vector = vector(name);
            Frame frame = TopicWireCodec.decodeFrame(hex(vector, "hex"));
            String delegationHash = frame.proof().delegation() == null ? null
                    : TopicWireCodec.delegationHash(chainId, generationId, frame.proof().delegation());
            WriteStatement statement = new WriteStatement(chainId, generationId, vector.get("topic").asText(),
                    TopicWireCodec.blake2b256Hex(frame.payload()), frame.proof().nonce(),
                    frame.proof().notAfter(), delegationHash);
            byte[] encoded = TopicWireCodec.encodeWriteStatement(statement);

            assertThat(statement.payloadHash()).isEqualTo(vector.get("payloadHash").asText());
            assertThat(encoded).as(name).isEqualTo(hex(vector, "statementHex"));
            assertThat(TopicWireCodec.statementHash(statement)).isEqualTo(vector.get("statementHash").asText());
            for (Signature signature : frame.proof().signatures()) {
                assertThat(StrictEd25519.verify(HexUtil.decodeHexString(signature.key()), signature.sig(),
                        encoded)).as(name).isTrue();
            }
        }
    }

    @Test
    void effectiveOutcomeEncodingsAndDigestsMatch() throws IOException {
        JsonNode policyVector = vector("outcome-policy");
        // Rebuild the vector's outcome from its decoded parts: writers and delegate come from the vector's
        // resolved policy, which the frame vectors share (issuer + writer, 1-of-2, issuer delegates).
        Frame direct = TopicWireCodec.decodeFrame(hex(vector("frame-direct-2-of-2"), "hex"));
        List<String> writerKeys = direct.proof().signatures().stream().map(Signature::key).toList();
        Delegation delegation = TopicWireCodec.decodeFrame(hex(vector("frame-delegated"), "hex"))
                .proof().delegation();
        String schema = TopicWireCodec.blake2b256Hex("schema-ir".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        ResolvedPolicy resolved = new ResolvedPolicy(new Writers(new KeySet(1, writerKeys)),
                List.of(delegation.issuer()), 3_600_000, schema, 4096, false);
        Map<String, EffectiveOutcome> outcomes = new HashMap<>();
        outcomes.put("outcome-policy", new EffectiveOutcome(OutcomeKind.POLICY, resolved, 3L,
                List.of(new Source("acme/", 3), new Source("acme/orders/", 2))));
        outcomes.put("outcome-disabled", new EffectiveOutcome(OutcomeKind.DISABLED, null, null, List.of()));
        outcomes.put("outcome-unregistered-open",
                new EffectiveOutcome(OutcomeKind.UNREGISTERED_OPEN, null, 1L, List.of()));
        outcomes.put("outcome-unregistered-reject",
                new EffectiveOutcome(OutcomeKind.UNREGISTERED_REJECT, null, 1L, List.of()));

        assertThat(policyVector).isNotNull();
        for (Map.Entry<String, EffectiveOutcome> entry : outcomes.entrySet()) {
            JsonNode vector = vector(entry.getKey());
            assertThat(TopicWireCodec.encodeEffectiveOutcome(entry.getValue())).as(entry.getKey())
                    .isEqualTo(hex(vector, "hex"));
            assertThat(TopicWireCodec.outcomeDigest(entry.getValue())).isEqualTo(vector.get("digest").asText());
        }
    }

    private static Map<String, Function<Object, byte[]>> encoders() {
        Map<String, Function<Object, byte[]>> encoders = new HashMap<>();
        encoders.put("config", value -> TopicWireCodec.encodeConfig((TopicWire.Config) value));
        encoders.put("entry-record", value -> TopicWireCodec.encodeEntryRecord((TopicWire.EntryRecord) value));
        encoders.put("queued-revision",
                value -> TopicWireCodec.encodeQueuedRevision((TopicWire.QueuedRevision) value));
        encoders.put("expiry-batch", value -> TopicWireCodec.encodeExpiryBatch((TopicWire.ExpiryBatch) value));
        encoders.put("expiry-item", value -> TopicWireCodec.encodeExpiryItem((TopicWire.ExpiryItem) value));
        encoders.put("activation", value -> TopicWireCodec.encodeActivation((TopicWire.Activation) value));
        encoders.put("receipt", value -> TopicWireCodec.encodeReceipt((TopicWire.Receipt) value));
        encoders.put("governance-pending", value -> {
            List<TopicWire.GovernanceApproval> approvals = new ArrayList<>();
            for (Object item : (List<?>) value) {
                approvals.add((TopicWire.GovernanceApproval) item);
            }
            return TopicWireCodec.encodeGovernancePending(approvals);
        });
        encoders.put("delegation", value -> TopicWireCodec.encodeDelegation((Delegation) value));
        encoders.put("registry-command", value -> TopicWireCodec.encodeRegistryCommand((RegistryCommand) value));
        encoders.put("governance-command",
                value -> TopicWireCodec.encodeGovernanceCommand((TopicWire.GovernanceCommand) value));
        encoders.put("frame", value -> TopicWireCodec.encodeFrame((Frame) value));
        return encoders;
    }

    private static JsonNode vector(String name) throws IOException {
        for (JsonNode vector : vectors().get("vectors")) {
            if (name.equals(vector.get("name").asText())) {
                return vector;
            }
        }
        throw new IllegalArgumentException("no vector " + name);
    }

    private static byte[] hex(JsonNode vector, String field) {
        return HexUtil.decodeHexString(vector.get(field).asText());
    }

    private static JsonNode vectors() throws IOException {
        try (InputStream in = TopicWireVectorsTest.class.getResourceAsStream("/topics/v1/vectors.json")) {
            return new ObjectMapper().readTree(in);
        }
    }
}
