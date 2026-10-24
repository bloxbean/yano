package org.yanoproject.api.appchain.topics;

import com.bloxbean.cardano.yaci.core.util.HexUtil;
import org.junit.jupiter.api.Test;
import org.yanoproject.api.appchain.schema.CborSchema;
import org.yanoproject.api.appchain.topics.TopicWire.Delegation;
import org.yanoproject.api.appchain.topics.TopicWire.Frame;
import org.yanoproject.api.appchain.topics.TopicWire.Policy;
import org.yanoproject.api.appchain.topics.TopicWire.Signature;
import org.yanoproject.api.appchain.topics.TopicWire.WriteStatement;
import org.yanoproject.api.appchain.topics.TopicWire.WriterProof;
import org.yanoproject.api.appchain.topics.TopicWire.Writers;
import org.yanoproject.api.appchain.topics.TopicTestFixtures.Key;
import org.yanoproject.api.appchain.topics.TopicTestFixtures.State;
import org.yanoproject.api.appchain.topics.TopicWriteRules.Result;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.yanoproject.api.appchain.topics.TopicTestFixtures.key;
import static org.yanoproject.api.appchain.topics.TopicTestFixtures.keys;

/** ADR app-layer/039 D7-D11: the write check, step by step (V3 writer and delegation cases). */
class TopicWriteRulesTest {
    private static final String CHAIN = "topics-writes";
    private static final String GENERATION = "ab".repeat(32);
    private static final long NOW = 1_800_000_000_000L;
    private static final String TOPIC = "acme/orders/new";
    private static final byte[] PAYLOAD = {0x05};

    private final Key a = key(1);
    private final Key b = key(2);
    private final Key c = key(3);
    private final Key issuer = key(4);
    private final Key session = key(5);
    private final Key outsider = key(6);

    @Test
    void disabledAndUnregisteredTopics() {
        State state = new State();
        assertThat(check(state, PAYLOAD).allowed()).isTrue();
        assertThat(check(state, PAYLOAD).framed()).isFalse();

        state.config(true);
        assertThat(check(state, PAYLOAD).refusal()).isEqualTo(TopicRefusal.TOPIC_UNREGISTERED);
        state.config(false);
        assertThat(check(state, PAYLOAD).payload()).isEqualTo(PAYLOAD);
    }

    @Test
    void retiredAndOpenTopicsWithSchemaAndSize() {
        State state = new State().config(true);
        byte[] ir = CborSchema.of(CborSchema.IntegerNode.uint()).definition();
        String schema = TopicWireCodec.blake2b256Hex(ir);
        state.schema(schema, ir);
        state.entry("acme/", new Policy(Writers.OPEN, null, null, schema, 2L), false);

        assertThat(check(state, PAYLOAD).allowed()).isTrue();
        assertThat(check(state, new byte[] {0x61, 0x61}).refusal()).isEqualTo(TopicRefusal.TOPIC_SCHEMA_REJECTED);
        assertThat(check(state, new byte[] {0x19, 0x01, 0x00}).refusal())
                .isEqualTo(TopicRefusal.TOPIC_PAYLOAD_TOO_LARGE);

        state.entry("acme/", Policy.INHERIT, true);
        assertThat(check(state, PAYLOAD).refusal()).isEqualTo(TopicRefusal.TOPIC_RETIRED);
    }

    @Test
    void aLargeTupleWithARepeatedTailMeetsItsSchema() {
        List<CborSchema.Occurrence> terms = new ArrayList<>();
        for (int field = 0; field < 4; field++) {
            terms.add(CborSchema.Occurrence.required(CborSchema.IntegerNode.uint()));
        }
        terms.add(new CborSchema.Occurrence(0, 65_532, CborSchema.IntegerNode.uint()));
        byte[] ir = CborSchema.of(new CborSchema.ArrayNode(terms)).definition();
        String schema = TopicWireCodec.blake2b256Hex(ir);
        State state = new State().config(true);
        state.schema(schema, ir);
        state.entry("acme/", new Policy(Writers.OPEN, null, null, schema, null), false);
        byte[] zeros = new byte[3 + 32_768]; // array(32768) of 0
        zeros[0] = (byte) 0x99;
        zeros[1] = (byte) 0x80;

        assertThat(check(state, zeros).allowed()).isTrue();
    }

    @Test
    void overlappingRepeatedTermsMeetTheirSchema() {
        byte[] ir = CborSchema.of(new CborSchema.ArrayNode(List.of(
                new CborSchema.Occurrence(1, 1000, new CborSchema.IntegerNode(
                        CborSchema.INTEGER_UINT, BigInteger.ZERO, BigInteger.TEN)),
                new CborSchema.Occurrence(1, 1000, new CborSchema.IntegerNode(
                        CborSchema.INTEGER_UINT, BigInteger.ZERO, BigInteger.valueOf(20))),
                CborSchema.Occurrence.required(new CborSchema.NullNode())))).definition();
        String schema = TopicWireCodec.blake2b256Hex(ir);
        State state = new State().config(true);
        state.schema(schema, ir);
        state.entry("acme/", new Policy(Writers.OPEN, null, null, schema, null), false);
        byte[] payload = new byte[3 + 1000 + 1]; // array(1001): 1,000 zeros, then null
        payload[0] = (byte) 0x99;
        payload[1] = 0x03;
        payload[2] = (byte) 0xe9;
        payload[payload.length - 1] = (byte) 0xf6;

        assertThat(check(state, payload).allowed()).isTrue();
    }

    @Test
    void directThresholdWritesAndReplays() {
        State state = new State().config(true);
        state.entry("acme/", new Policy(new Writers(keys(2, a, b, c)), null, null, null, null), false);

        Result ab = check(state, frame(NOW + 60_000, null, a, b));
        assertThat(ab.allowed()).isTrue();
        assertThat(ab.writer().signers()).isSorted().hasSize(2);
        assertThat(check(state, frame(NOW + 60_000, null, a)).refusal()).isEqualTo(TopicRefusal.TOPIC_WRITER_DENIED);
        assertThat(check(state, frame(NOW + 60_000, null, a, outsider)).refusal())
                .isEqualTo(TopicRefusal.TOPIC_WRITER_DENIED);
        assertThat(check(state, new byte[] {1, 2, 3}).refusal()).isEqualTo(TopicRefusal.TOPIC_FRAME_INVALID);

        // The same intent with another valid signature subset is the same statement (D9).
        Result ac = TopicWriteRules.check(TOPIC, frame(NOW + 60_000, null, a, c), CHAIN, GENERATION, NOW,
                state.view(), Set.of(ab.statementHash()));
        assertThat(ac.refusal()).isEqualTo(TopicRefusal.TOPIC_PROOF_REPLAYED);
        state.used(ab.statementHash());
        assertThat(check(state, frame(NOW + 60_000, null, b, c)).refusal())
                .isEqualTo(TopicRefusal.TOPIC_PROOF_REPLAYED);

        assertThat(check(state, frame(NOW - 1, null, a, b)).refusal()).isEqualTo(TopicRefusal.TOPIC_PROOF_EXPIRED);
        assertThat(check(state, frame(NOW + 3_600_001, null, a, b)).refusal())
                .isEqualTo(TopicRefusal.TOPIC_PROOF_EXPIRED);
    }

    @Test
    void delegatedWritesAndEveryDelegationRule() {
        State state = new State().config(true);
        state.entry("acme/", new Policy(new Writers(keys(1, issuer, a)), List.of(issuer.hex()), 600_000L, null, null),
                false);
        Delegation delegation = delegation(issuer, session.hex(), "acme/orders/", NOW - 1_000, NOW + 300_000);

        Result ok = check(state, frame(NOW + 60_000, delegation, session));
        assertThat(ok.allowed()).isTrue();
        assertThat(ok.writer().kind()).isEqualTo(TopicWriteRules.VerifiedWriter.Kind.DELEGATED);
        assertThat(ok.writer().issuer()).isEqualTo(issuer.hex());

        assertThat(check(state, frame(NOW + 60_000, delegation(a, session.hex(), "acme/", NOW - 1, NOW + 1_000),
                session)).refusal()).as("issuer outside the delegate set")
                .isEqualTo(TopicRefusal.TOPIC_DELEGATION_INVALID);
        assertThat(check(state, frame(NOW + 60_000, delegation(issuer, session.hex(), "acme/other/", NOW - 1,
                NOW + 1_000), session)).refusal()).as("scope").isEqualTo(TopicRefusal.TOPIC_DELEGATION_INVALID);
        assertThat(check(state, frame(NOW + 60_000, delegation(issuer, session.hex(), "acme/", NOW + 1,
                NOW + 1_000), session)).refusal()).as("not yet valid").isEqualTo(TopicRefusal.TOPIC_DELEGATION_INVALID);
        assertThat(check(state, frame(NOW + 60_000, delegation(issuer, session.hex(), "acme/", NOW - 1,
                NOW + 600_000), session)).refusal()).as("lifetime").isEqualTo(TopicRefusal.TOPIC_DELEGATION_INVALID);

        // A relay cannot swap in another issuer's delegation: the session key signed the first one's hash.
        Delegation other = delegation(a, session.hex(), "acme/orders/", NOW - 1_000, NOW + 300_000);
        Frame swapped = TopicWireCodec.decodeFrame(frame(NOW + 60_000, delegation, session));
        byte[] swappedBody = TopicWireCodec.encodeFrame(new Frame(swapped.payload(), new WriterProof(
                swapped.proof().nonce(), swapped.proof().notAfter(), swapped.proof().signatures(), other)));
        state.entry("acme/", new Policy(new Writers(keys(1, issuer, a)), List.of(issuer.hex(), a.hex()).stream()
                .sorted()
                .toList(), 600_000L, null, null), false);
        assertThat(check(state, swappedBody).refusal()).isEqualTo(TopicRefusal.TOPIC_WRITER_DENIED);

        // D8, I9: an issuer delegating only under acme/orders/ cannot hand out a key scoped to all of acme/,
        // which it could not revoke (S3)
        State child = new State().config(true);
        child.entry("acme/", new Policy(new Writers(keys(1, a)), null, null, null, null), false);
        child.entry("acme/orders/", new Policy(new Writers(keys(1, issuer)), List.of(issuer.hex()), 600_000L, null,
                null), false);
        assertThat(check(child, frame(NOW + 60_000, delegation, session)).allowed()).isTrue();
        assertThat(check(child, frame(NOW + 60_000, delegation(issuer, session.hex(), "acme/", NOW - 1_000,
                NOW + 300_000), session)).refusal()).isEqualTo(TopicRefusal.TOPIC_DELEGATION_INVALID);

        state.revoked(TopicWireCodec.delegationHash(CHAIN, GENERATION, delegation));
        assertThat(check(state, frame(NOW + 60_000, delegation, session)).refusal())
                .isEqualTo(TopicRefusal.TOPIC_DELEGATION_INVALID);
    }

    @Test
    void aDelegationScopeMustBeAnEntryNameItsIssuerCanRevoke() {
        State state = new State().config(true);
        state.entry("acme/", new Policy(new Writers(keys(1, issuer)), List.of(issuer.hex()), null, null, null), false);
        for (String scope : List.of("acme/Foo/", "acme//", "acme/a b/")) {
            Delegation delegation = delegation(issuer, session.hex(), scope, NOW - 1, NOW + 60_000);
            byte[] body = frame(scope, GENERATION, NOW + 30_000, delegation, session);
            assertThat(TopicWriteRules.check(scope, body, CHAIN, GENERATION, NOW, state.view(), Set.of()).refusal())
                    .as(scope).isEqualTo(TopicRefusal.TOPIC_DELEGATION_INVALID);
        }
    }

    @Test
    void degenerateSessionKeysAndOtherGenerationsAreRefused() {
        State state = new State().config(true);
        state.entry("acme/", new Policy(new Writers(keys(1, issuer)), List.of(issuer.hex()), null, null, null), false);
        String identity = "01" + "00".repeat(31);
        Delegation toIdentity = delegation(issuer, identity, "acme/", NOW - 1, NOW + 60_000);
        byte[] forged = new byte[64];
        System.arraycopy(HexUtil.decodeHexString(identity), 0, forged, 0, 32);
        WriterProof proof = new WriterProof(nonce(), NOW + 30_000, List.of(new Signature(identity, forged)),
                toIdentity);
        assertThat(check(state, TopicWireCodec.encodeFrame(new Frame(PAYLOAD, proof))).refusal())
                .as("R = identity, S = 0 under the identity key").isEqualTo(TopicRefusal.TOPIC_WRITER_DENIED);

        byte[] otherGeneration = frame("cd".repeat(32), NOW + 60_000, null, issuer);
        assertThat(check(state, otherGeneration).refusal()).isEqualTo(TopicRefusal.TOPIC_WRITER_DENIED);
    }

    // ------------------------------------------------------------------ fixtures

    private Result check(State state, byte[] body) {
        return TopicWriteRules.check(TOPIC, body, CHAIN, GENERATION, NOW, state.view(), Set.of());
    }

    private byte[] frame(long notAfter, Delegation delegation, Key... signers) {
        return frame(GENERATION, notAfter, delegation, signers);
    }

    private byte[] frame(String generation, long notAfter, Delegation delegation, Key... signers) {
        return frame(TOPIC, generation, notAfter, delegation, signers);
    }

    private byte[] frame(String topic, String generation, long notAfter, Delegation delegation, Key... signers) {
        String delegationHash = delegation == null ? null
                : TopicWireCodec.delegationHash(CHAIN, generation, delegation);
        byte[] statement = TopicWireCodec.encodeWriteStatement(new WriteStatement(CHAIN, generation, topic,
                TopicWireCodec.blake2b256Hex(PAYLOAD), nonce(), notAfter, delegationHash));
        List<Signature> signatures = new ArrayList<>();
        Arrays.stream(signers).sorted((x, y) -> x.hex().compareTo(y.hex()))
                .forEach(k -> signatures.add(new Signature(k.hex(), k.sign(statement))));
        return TopicWireCodec.encodeFrame(new Frame(PAYLOAD, new WriterProof(nonce(), notAfter, signatures,
                delegation)));
    }

    private Delegation delegation(Key by, String delegate, String scope, long notBefore, long notAfter) {
        Delegation unsigned = new Delegation(delegate, scope, notBefore, notAfter, by.hex(), new byte[64]);
        return new Delegation(delegate, scope, notBefore, notAfter, by.hex(),
                by.sign(TopicWireCodec.delegationSignedBytes(CHAIN, GENERATION, unsigned)));
    }

    private static String nonce() {
        return "11".repeat(16);
    }
}
