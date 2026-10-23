package org.yanoproject.api.appchain.topics;

import org.junit.jupiter.api.Test;
import org.yanoproject.api.appchain.schema.CborSchema;
import org.yanoproject.api.appchain.topics.TopicCommandRules.Admission;
import org.yanoproject.api.appchain.topics.TopicTestFixtures.Key;
import org.yanoproject.api.appchain.topics.TopicTestFixtures.State;
import org.yanoproject.api.appchain.topics.TopicWire.CommandStatement;
import org.yanoproject.api.appchain.topics.TopicWire.Create;
import org.yanoproject.api.appchain.topics.TopicWire.Delegation;
import org.yanoproject.api.appchain.topics.TopicWire.Operation;
import org.yanoproject.api.appchain.topics.TopicWire.Policy;
import org.yanoproject.api.appchain.topics.TopicWire.RegistryCommand;
import org.yanoproject.api.appchain.topics.TopicWire.Revoke;
import org.yanoproject.api.appchain.topics.TopicWire.Rotate;
import org.yanoproject.api.appchain.topics.TopicWire.Signature;
import org.yanoproject.api.appchain.topics.TopicWire.Update;
import org.yanoproject.api.appchain.topics.TopicWire.Writers;

import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.yanoproject.api.appchain.topics.TopicTestFixtures.key;
import static org.yanoproject.api.appchain.topics.TopicTestFixtures.keys;
import static org.yanoproject.api.appchain.topics.TopicTestFixtures.settings;

/** ADR app-layer/039 D3-D5, D8, I6, I19: registry command admission, one refusal branch at a time. */
class TopicCommandRulesTest {
    private static final String CHAIN = "topics-commands";
    private static final String GENERATION = "ab".repeat(32);
    private static final long NOW = 1_800_000_000_000L;

    private final Key owner = key(1);
    private final Key registrarA = key(2);
    private final Key registrarB = key(3);
    private final Key issuer = key(4);
    private final Key outsider = key(5);

    @Test
    void envelopeChecksComeFirst() {
        Create create = new Create(keys(1, owner), Policy.INHERIT, null);
        assertThat(admit(new State(), command("acme/orders", "acme/", null, create, owner)).refusal())
                .as("topics disabled").isEqualTo(TopicRefusal.TOPIC_COMMAND_INVALID);

        State state = withAcme();
        assertThat(TopicCommandRules.admit(new byte[] {1}, CHAIN, GENERATION, NOW, state.view(), Set.of())
                .refusal()).as("malformed").isEqualTo(TopicRefusal.TOPIC_COMMAND_INVALID);
        byte[] body = command("acme/orders", "acme/", null, create, owner);
        assertThat(TopicCommandRules.admit(body, "other-chain", GENERATION, NOW, state.view(), Set.of()).refusal())
                .isEqualTo(TopicRefusal.TOPIC_COMMAND_INVALID);
        assertThat(TopicCommandRules.admit(body, CHAIN, "cd".repeat(32), NOW, state.view(), Set.of()).refusal())
                .isEqualTo(TopicRefusal.TOPIC_COMMAND_INVALID);
        assertThat(admit(state, signed(statement("acme/orders", "acme/", null, NOW - 1, create), owner)).refusal())
                .as("past notAfter").isEqualTo(TopicRefusal.TOPIC_COMMAND_EXPIRED);
        assertThat(admit(state, signed(statement("acme/orders", "acme/", null, NOW + 3_600_001, create), owner))
                .refusal()).as("beyond max-command-ms").isEqualTo(TopicRefusal.TOPIC_COMMAND_EXPIRED);
        assertThat(admit(state, command("Acme/x", "acme/", null, create, owner)).refusal())
                .as("invalid target").isEqualTo(TopicRefusal.TOPIC_COMMAND_INVALID);
        assertThat(admit(state, body).admitted()).isTrue();
    }

    @Test
    void registrarsCreateTopLevelNamesAtTheirThreshold() {
        Create create = new Create(keys(1, owner), Policy.INHERIT, null);
        State none = new State().config(true);
        assertThat(admit(none, command("acme/", TopicWire.AUTHORITY_REGISTRARS, null, create, registrarA))
                .refusal()).as("no registrars configured").isEqualTo(TopicRefusal.TOPIC_COMMAND_DENIED);

        State state = new State().config(settings(keys(2, registrarA, registrarB), false, true));
        assertThat(admit(state, command("acme/orders/", TopicWire.AUTHORITY_REGISTRARS, null, create,
                registrarA, registrarB)).refusal()).as("not top-level").isEqualTo(TopicRefusal.TOPIC_COMMAND_INVALID);
        assertThat(admit(state, command("acme/", TopicWire.AUTHORITY_REGISTRARS, null, create, registrarA))
                .refusal()).as("below threshold").isEqualTo(TopicRefusal.TOPIC_COMMAND_DENIED);
        assertThat(admit(state, command("acme/", TopicWire.AUTHORITY_REGISTRARS, null, create, registrarA,
                outsider)).refusal()).as("outsider signature").isEqualTo(TopicRefusal.TOPIC_COMMAND_DENIED);
        Admission admitted = admit(state, command("acme/", TopicWire.AUTHORITY_REGISTRARS, null, create,
                registrarA, registrarB));
        assertThat(admitted.admitted()).isTrue();
        assertThat(admitted.authorityEpoch()).as("registrarsSince").isEqualTo(1);
        assertThat(admit(state, command("acme/", TopicWire.AUTHORITY_REGISTRARS, 1L, create, registrarA,
                registrarB)).refusal()).as("a creation names no head").isEqualTo(TopicRefusal.TOPIC_COMMAND_INVALID);
    }

    @Test
    void openClaimsAreSingleSegmentAndOptIn() {
        Create create = new Create(keys(1, owner), Policy.INHERIT, null);
        assertThat(admit(new State().config(true), command("acme/", TopicWire.AUTHORITY_CLAIM, null, create, owner))
                .refusal()).as("claims disabled").isEqualTo(TopicRefusal.TOPIC_COMMAND_DENIED);
        State open = new State().config(settings(null, true, true));
        assertThat(admit(open, command("acme/orders", TopicWire.AUTHORITY_CLAIM, null, create, owner)).refusal())
                .as("I6: multi-segment claim").isEqualTo(TopicRefusal.TOPIC_COMMAND_INVALID);
        assertThat(admit(open, command("acme/", TopicWire.AUTHORITY_CLAIM, null, create, outsider)).refusal())
                .as("signed by the claimed owners").isEqualTo(TopicRefusal.TOPIC_COMMAND_DENIED);
        assertThat(admit(open, command("acme/", TopicWire.AUTHORITY_CLAIM, null, create, owner)).admitted()).isTrue();
    }

    @Test
    void deeperEntriesAreCreatedByAnAncestorNamespace() {
        Create create = new Create(keys(1, owner), Policy.INHERIT, null);
        State state = withAcme();
        assertThat(admit(state, command("acme/", "acme/", null, create, owner)).refusal())
                .as("top-level by an ancestor").isEqualTo(TopicRefusal.TOPIC_COMMAND_INVALID);
        assertThat(admit(state, command("acme/orders/", "acme/orders/", null, create, owner)).refusal())
                .as("authority is the target").isEqualTo(TopicRefusal.TOPIC_COMMAND_INVALID);
        assertThat(admit(state, command("acme/orders", "acme", null, create, owner)).refusal())
                .as("authority is not a namespace").isEqualTo(TopicRefusal.TOPIC_COMMAND_INVALID);
        assertThat(admit(state, command("acme/orders", "other/", null, create, owner)).refusal())
                .as("authority does not cover").isEqualTo(TopicRefusal.TOPIC_COMMAND_INVALID);
        assertThat(admit(state, command("acme/a/b", "acme/a/", null, create, owner)).refusal())
                .as("authority has no active revision").isEqualTo(TopicRefusal.TOPIC_COMMAND_DENIED);
        assertThat(admit(state, command("acme/orders", "acme/", null, create, outsider)).refusal())
                .isEqualTo(TopicRefusal.TOPIC_COMMAND_DENIED);
        assertThat(admit(state, command("acme/a/b", "acme/", null, create, owner)).admitted())
                .as("any ancestor with an active revision").isTrue();
    }

    @Test
    void schemaReferencesAreJudgedAfterTheSignatures() {
        State state = withAcme();
        byte[] ir = CborSchema.of(CborSchema.IntegerNode.uint()).definition();
        String hash = TopicWireCodec.blake2b256Hex(ir);
        Policy schemaPolicy = new Policy(null, null, null, hash, null);
        assertThat(admit(state, command("acme/orders", "acme/", null, new Create(keys(1, owner), schemaPolicy,
                new byte[] {1}), owner)).refusal()).as("IR not matching").isEqualTo(TopicRefusal.TOPIC_COMMAND_INVALID);
        assertThat(admit(state, command("acme/orders", "acme/", null, new Create(keys(1, owner), schemaPolicy,
                null), owner)).refusal()).as("unknown hash").isEqualTo(TopicRefusal.TOPIC_COMMAND_INVALID);
        assertThat(admit(state, command("acme/orders", "acme/", null, new Create(keys(1, owner), schemaPolicy,
                new byte[] {1}), outsider)).refusal()).as("a forged command never reaches the schema")
                .isEqualTo(TopicRefusal.TOPIC_COMMAND_DENIED);
        assertThat(admit(state, command("acme/orders", "acme/", null, new Create(keys(1, owner), schemaPolicy,
                ir), owner)).admitted()).isTrue();

        state.schema(hash, ir);
        byte[] byHash = command("acme/orders", "acme/", null, new Create(keys(1, owner), schemaPolicy, null), owner);
        assertThat(admit(state, byHash).admitted()).isTrue();
        assertThat(TopicCommandRules.admit(byHash, CHAIN, GENERATION, NOW, state.view(), Set.of(hash)).refusal())
                .as("stored earlier in the same block").isEqualTo(TopicRefusal.TOPIC_COMMAND_INVALID);
    }

    @Test
    void changesNameTheHeadAndComeFromTheTargetOrAnAncestor() {
        State state = withAcme();
        Operation rotate = new Rotate(keys(1, outsider));
        assertThat(admit(state, command("acme/", "acme/", null, rotate, owner)).refusal())
                .as("no head").isEqualTo(TopicRefusal.TOPIC_COMMAND_INVALID);
        assertThat(admit(state, command("acme/", "other/", 1L, rotate, owner)).refusal())
                .isEqualTo(TopicRefusal.TOPIC_COMMAND_INVALID);
        assertThat(admit(state, command("acme/", "acme/", 1L, rotate, outsider)).refusal())
                .isEqualTo(TopicRefusal.TOPIC_COMMAND_DENIED);
        Admission admitted = admit(state, command("acme/", "acme/", 1L,
                new Update(new Policy(Writers.OPEN, null, null, null, null), null), owner));
        assertThat(admitted.admitted()).isTrue();
        assertThat(admitted.authorityEpoch()).as("ownersSince").isEqualTo(1);
    }

    @Test
    void revocationsNeedTheSignedDelegationWithinItsHorizonAndAnAuthorizedRevoker() {
        State state = withAcme();
        state.entry("acme/orders/", keys(1, owner),
                new Policy(new Writers(keys(1, issuer)), List.of(issuer.hex()), null, null, null), false);
        Delegation delegation = delegation("acme/orders/", NOW + 60_000);
        assertThat(admit(state, issuerRevoke("acme/", delegation)).refusal())
                .as("target is the scope").isEqualTo(TopicRefusal.TOPIC_COMMAND_INVALID);
        Delegation forged = new Delegation(delegation.delegate(), delegation.scope(), delegation.notBefore(),
                delegation.notAfter(), delegation.issuer(), new byte[64]);
        assertThat(admit(state, issuerRevoke("acme/orders/", forged)).refusal())
                .isEqualTo(TopicRefusal.TOPIC_COMMAND_INVALID);
        assertThat(TopicCommandRules.admit(issuerRevoke("acme/orders/", delegation), CHAIN, GENERATION,
                NOW + 60_001, state.view(), Set.of()).refusal()).as("already expired")
                .isEqualTo(TopicRefusal.TOPIC_COMMAND_EXPIRED);
        assertThat(admit(state, issuerRevoke("acme/orders/", delegation("acme/orders/", NOW + 3_600_001)))
                .refusal()).as("beyond the retention horizon").isEqualTo(TopicRefusal.TOPIC_COMMAND_INVALID);
        assertThat(admit(state, issuerRevoke("acme/orders/", delegation)).admitted()).isTrue();
        assertThat(admit(state, command("acme/orders/", "acme/", null, new Revoke(delegation), owner)).admitted())
                .as("an ancestor's owners").isTrue();
        assertThat(admit(state, command("acme/orders/", "other/", null, new Revoke(delegation), owner)).refusal())
                .isEqualTo(TopicRefusal.TOPIC_COMMAND_INVALID);

        // D8, I9: a scope the issuer may not delegate is neither revocable by it nor usable for writes
        Delegation wide = delegation("acme/", NOW + 60_000);
        assertThat(admit(state, issuerRevoke("acme/", wide)).refusal()).isEqualTo(TopicRefusal.TOPIC_COMMAND_DENIED);
        assertThat(TopicWriteRules.mayDelegate(state.view(), "acme/", issuer.hex())).isFalse();
        assertThat(TopicWriteRules.mayDelegate(state.view(), "acme/orders/", issuer.hex())).isTrue();
    }

    // ------------------------------------------------------------------ fixtures

    private State withAcme() {
        State state = new State().config(true);
        state.entry("acme/", keys(1, owner), Policy.INHERIT, false);
        return state;
    }

    private static Admission admit(State state, byte[] body) {
        return TopicCommandRules.admit(body, CHAIN, GENERATION, NOW, state.view(), Set.of());
    }

    private static CommandStatement statement(String target, String authority, Long expected, long notAfter,
                                              Operation operation) {
        return new CommandStatement(CHAIN, GENERATION, target, authority, expected, "22".repeat(16), notAfter,
                operation);
    }

    private static byte[] command(String target, String authority, Long expected, Operation operation,
                                  Key... signers) {
        return signed(statement(target, authority, expected, NOW + 60_000, operation), signers);
    }

    private static byte[] signed(CommandStatement statement, Key... signers) {
        byte[] bytes = TopicWireCodec.encodeCommandStatement(statement);
        return TopicWireCodec.encodeRegistryCommand(new RegistryCommand(statement, Arrays.stream(signers)
                .sorted((left, right) -> left.hex().compareTo(right.hex()))
                .map(signer -> new Signature(signer.hex(), signer.sign(bytes))).toList()));
    }

    private byte[] issuerRevoke(String target, Delegation delegation) {
        return command(target, TopicWire.AUTHORITY_ISSUER, null, new Revoke(delegation), issuer);
    }

    private Delegation delegation(String scope, long notAfter) {
        Delegation unsigned = new Delegation(outsider.hex(), scope, NOW - 1_000, notAfter, issuer.hex(),
                new byte[64]);
        return new Delegation(unsigned.delegate(), scope, unsigned.notBefore(), notAfter, issuer.hex(),
                issuer.sign(TopicWireCodec.delegationSignedBytes(CHAIN, GENERATION, unsigned)));
    }
}
