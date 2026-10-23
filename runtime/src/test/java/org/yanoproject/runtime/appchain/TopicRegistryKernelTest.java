package org.yanoproject.runtime.appchain;

import com.bloxbean.cardano.yaci.core.protocol.appmsg.model.AppMessage;
import com.bloxbean.cardano.yaci.core.protocol.appmsg.model.AuthScheme;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import org.junit.jupiter.api.Test;
import org.yanoproject.api.appchain.AppBlock;
import org.yanoproject.api.appchain.AppBlockExecutionContext;
import org.yanoproject.api.appchain.AppChainConsensusProfile;
import org.yanoproject.api.appchain.AppStateWriter;
import org.yanoproject.api.appchain.FinalityCert;
import org.yanoproject.api.appchain.codec.AppBlockCodec;
import org.yanoproject.api.appchain.effects.EffectOutcomeCommitment;
import org.yanoproject.api.appchain.effects.FinalityGate;
import org.yanoproject.api.appchain.observation.ObservationProfileV1;
import org.yanoproject.api.appchain.topics.TopicCommandRules;
import org.yanoproject.api.appchain.topics.TopicKeys;
import org.yanoproject.api.appchain.topics.TopicMessageView;
import org.yanoproject.api.appchain.topics.TopicRefusal;
import org.yanoproject.api.appchain.topics.TopicState;
import org.yanoproject.api.appchain.topics.TopicWire;
import org.yanoproject.api.appchain.topics.TopicWire.CommandStatement;
import org.yanoproject.api.appchain.topics.TopicWire.Create;
import org.yanoproject.api.appchain.topics.TopicWire.Delegation;
import org.yanoproject.api.appchain.topics.TopicWire.EntryRecord;
import org.yanoproject.api.appchain.topics.TopicWire.GovCreate;
import org.yanoproject.api.appchain.topics.TopicWire.GovernanceCommand;
import org.yanoproject.api.appchain.topics.TopicWire.KeySet;
import org.yanoproject.api.appchain.topics.TopicWire.OutcomeKind;
import org.yanoproject.api.appchain.topics.TopicWire.Policy;
import org.yanoproject.api.appchain.topics.TopicWire.RegistryCommand;
import org.yanoproject.api.appchain.topics.TopicWire.Revoke;
import org.yanoproject.api.appchain.topics.TopicWire.Rotate;
import org.yanoproject.api.appchain.topics.TopicWire.Settings;
import org.yanoproject.api.appchain.topics.TopicWire.Signature;
import org.yanoproject.api.appchain.topics.TopicWire.Update;
import org.yanoproject.api.appchain.topics.TopicWire.Writers;
import org.yanoproject.api.appchain.topics.TopicWireCodec;
import org.yanoproject.api.appchain.topics.TopicWriteRules;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** ADR app-layer/039 M2: the registry state transition, block by block. */
class TopicRegistryKernelTest {
    private static final String CHAIN = "topics-kernel";
    private static final String GENERATION = "ab".repeat(32);
    private static final long LAG = 2;
    private static final long T0 = 1_800_000_000_000L;

    private final AppMessageSigner memberA = signer(1);
    private final AppMessageSigner memberB = signer(2);
    private final AppMessageSigner ownerO = signer(3);
    private final AppMessageSigner ownerN = signer(4);
    private final AppMessageSigner childOwner = signer(5);
    private final AppMessageSigner issuer = signer(6);
    private final AppMessageSigner registrar = signer(7);
    private final AppMessageSigner sessionKey = signer(8);

    @Test
    void disabledChainWritesNothing() {
        Chain chain = new Chain(null);
        chain.block();
        chain.block();
        assertThat(chain.state.values).isEmpty();
    }

    @Test
    void genesisSeedGovernsFromBlockTwo() {
        Chain chain = new Chain(settings(null, false));
        assertThat(chain.view().config()).isEmpty();
        chain.block(); // height 1
        assertThat(chain.view().config()).hasValueSatisfying(config -> assertThat(config.revision()).isEqualTo(1));
        assertThat(chain.view().outcome("anything").kind()).isEqualTo(OutcomeKind.UNREGISTERED_REJECT);
    }

    @Test
    void governanceCreatesAtTheConsensusThresholdAndOwnersCreateChildren() {
        Chain chain = enabledChain();
        AppMessage first = chain.governance(memberA, govCreate("acme/", ownerO));
        chain.block(first);
        assertThat(chain.view().governancePending()).hasSize(1);
        assertThat(chain.view().entry("acme/")).isEmpty();

        AppMessage second = chain.governance(memberB, govCreate("acme/", ownerO));
        long approvedAt = chain.block(second);
        assertThat(chain.view().governancePending()).isEmpty();
        EntryRecord pending = chain.view().entry("acme/").orElseThrow();
        assertThat(pending.active()).isNull();
        assertThat(chain.receipt(second)).isEqualTo(new TopicWire.Receipt("ACCEPTED", approvedAt + LAG));

        chain.blocksUntil(approvedAt + LAG);
        EntryRecord active = chain.view().entry("acme/").orElseThrow();
        assertThat(active.active().revision()).isEqualTo(1);
        assertThat(active.active().activeFrom()).isEqualTo(approvedAt + LAG);

        AppMessage child = chain.command(create("acme/orders/", "acme/", childOwner), ownerO);
        long createdAt = chain.block(child);
        chain.blocksUntil(createdAt + LAG);
        assertThat(chain.view().entry("acme/orders/").orElseThrow().active()).isNotNull();
        assertThat(chain.view().entry("acme/").orElseThrow().subtree()).isEqualTo(2);
        assertThat(chain.view().count()).isEqualTo(2);
        assertThat(chain.view().outcome("acme/orders/new").sources()).extracting(TopicWire.Source::name)
                .containsExactly("acme/", "acme/orders/");
    }

    @Test
    void secondCommandOnTheSameHeadIsStaleAndSelfRotationActivates() {
        Chain chain = chainWithAcme();
        long head = chain.view().entry("acme/").orElseThrow().head();
        AppMessage rotate = chain.command(statement("acme/", "acme/", head, new Rotate(keys(ownerN))), ownerO);
        AppMessage conflicting = chain.command(statement("acme/", "acme/", head,
                new Update(openPolicy(), null)), ownerO);
        long at = chain.block(rotate, conflicting);
        assertThat(chain.receipt(conflicting).code()).isEqualTo(TopicRefusal.TOPIC_COMMAND_STALE.name());

        chain.blocksUntil(at + LAG);
        assertThat(chain.view().entry("acme/").orElseThrow().active().content().owners())
                .isEqualTo(keys(ownerN));
        assertThat(chain.state.get(TopicKeys.history("acme/", head))).isPresent();
    }

    @Test
    void anAncestorRotationThatActivatesFirstCancelsTheChildChangeAndLaterOnes() {
        Chain chain = chainWithAcme();
        AppMessage child = chain.command(create("acme/orders/", "acme/", childOwner), ownerO);
        chain.blocksUntil(chain.block(child) + LAG);

        long acmeHead = chain.view().entry("acme/").orElseThrow().head();
        long childHead = chain.view().entry("acme/orders/").orElseThrow().head();
        // Same block: the rotation is listed first, so it activates first and advances acme/'s epoch.
        AppMessage rotate = chain.command(statement("acme/", "acme/", acmeHead, new Rotate(keys(ownerN))), ownerO);
        AppMessage update = chain.command(statement("acme/orders/", "acme/", childHead,
                new Update(openPolicy(), null)), ownerO);
        AppMessage later = chain.command(statement("acme/orders/", "acme/orders/", childHead + 1,
                new Rotate(keys(issuer))), childOwner);
        long at = chain.block(rotate, update, later);
        assertThat(chain.view().entry("acme/orders/").orElseThrow().pending()).isEqualTo(2);

        chain.blocksUntil(at + LAG);
        EntryRecord orders = chain.view().entry("acme/orders/").orElseThrow();
        assertThat(orders.active().revision()).isEqualTo(childHead);
        assertThat(orders.head()).as("revision numbers are never reused").isEqualTo(childHead + 2);
        assertThat(orders.pending()).isZero();
        assertThat(chain.view().queued("acme/orders/", childHead + 2)).isEmpty();
        assertThat(chain.view().entry("acme/").orElseThrow().active().content().owners()).isEqualTo(keys(ownerN));
        assertThat(chain.receipt(update).code()).isEqualTo(TopicRefusal.TOPIC_COMMAND_SUPERSEDED.name());
        assertThat(chain.receipt(later).code()).isEqualTo(TopicRefusal.TOPIC_COMMAND_SUPERSEDED.name());

        // New changes after the cascade take fresh revision numbers; the stale activation items of the
        // cancelled numbers never install anything early, and the next change still finds its head.
        AppMessage again = chain.command(statement("acme/orders/", "acme/orders/", childHead + 2,
                new Rotate(keys(issuer))), childOwner);
        long requeued = chain.block(again);
        assertThat(chain.view().entry("acme/orders/").orElseThrow().head()).isEqualTo(childHead + 3);
        chain.blocksUntil(requeued + LAG);
        assertThat(chain.view().entry("acme/orders/").orElseThrow().active().revision()).isEqualTo(childHead + 3);
        AppMessage next = chain.command(statement("acme/orders/", "acme/orders/", childHead + 3,
                new Update(openPolicy(), null)), issuer);
        chain.block(next);
        assertThat(chain.receipt(next).code()).isEqualTo("ACCEPTED");
    }

    @Test
    void machinesReceiveTheVerifiedPayloadOfAProtectedWrite() {
        Chain chain = chainWithAcme();
        long head = chain.view().entry("acme/").orElseThrow().head();
        chain.blocksUntil(chain.block(chain.command(statement("acme/", "acme/", head, new Update(
                new Policy(new Writers(keys(childOwner)), null, null, null, null), null)), ownerO)) + LAG);

        byte[] payload = {0x01, 0x02};
        byte[] frame = frame("acme/orders", payload, 7, chain.time + 60_000, null, childOwner);
        AppMessage write = chain.message(memberA, "acme/orders", frame);

        AppBlockExecutionContext seen = chain.blockSeenByMachine(write);
        AppMessage delivered = seen.messages().getFirst();
        assertThat(delivered.getBody()).isEqualTo(payload);
        assertThat(delivered.getMessageId()).isEqualTo(write.getMessageId());
        assertThat(TopicMessageView.of(delivered).orElseThrow().writer().orElseThrow().signers())
                .containsExactly(childOwner.publicKeyHex());
        assertThat(seen.block().messages().getFirst().getBody()).as("the block keeps the frame").isEqualTo(frame);

        AppMessage unframed = chain.message(memberA, "acme/orders", payload);
        assertThatThrownBy(() -> chain.block(unframed)).hasMessageContaining(TopicRefusal.TOPIC_FRAME_INVALID.name());
    }

    @Test
    void approvalsBeyondSixteenMembersCount() {
        List<AppMessageSigner> members = new ArrayList<>();
        for (int seed = 20; seed < 40; seed++) {
            members.add(signer(seed));
        }
        Chain chain = new Chain(settings(null, false),
                Set.copyOf(members.stream().map(AppMessageSigner::publicKeyHex).toList()), 18);
        chain.block();
        AppMessage[] approvals = members.subList(0, 18).stream()
                .map(member -> chain.governance(member, govCreate("acme/", ownerO))).toArray(AppMessage[]::new);
        long approved = chain.block(approvals);
        assertThat(chain.view().governancePending()).isEmpty();
        assertThat(chain.receipt(approvals[17])).isEqualTo(new TopicWire.Receipt("ACCEPTED", approved + LAG));
    }

    @Test
    void oneConfigurationChangeAtATimeAndTheSeedKeepsRevisionOne() {
        Chain chain = new Chain(settings(null, false));
        TopicWire.GovConfigure change = new TopicWire.GovConfigure(settings(null, true));
        AppMessage first = chain.governance(memberA, change);
        long at = chain.block(first, chain.governance(memberB, change));
        assertThat(at).isEqualTo(1);
        assertThat(chain.view().config().orElseThrow().revision()).isEqualTo(1);

        TopicWire.GovConfigure second = new TopicWire.GovConfigure(settings(keys(registrar), false));
        AppMessage limited = chain.governance(memberB, second);
        chain.block(chain.governance(memberA, second), limited);
        assertThat(chain.receipt(limited).code()).isEqualTo(TopicRefusal.TOPIC_COMMAND_LIMIT.name());

        chain.blocksUntil(at + LAG);
        assertThat(chain.view().config().orElseThrow().revision()).isEqualTo(2);
        assertThat(chain.view().config().orElseThrow().settings().openClaims()).isTrue();
        assertThat(chain.state.get(TopicKeys.history(TopicKeys.CONFIG_HISTORY_NAME, 1))).isPresent();
        assertThat(chain.state.get(TopicKeys.configPending())).isEmpty();
    }

    @Test
    void aLagDecreaseNeverActivatesALaterRevisionFirst() {
        Chain chain = new Chain(settings(null, false, 5, 600));
        chain.block();
        chain.block(chain.governance(memberA, govCreate("acme/", ownerO)),
                chain.governance(memberB, govCreate("acme/", ownerO)));
        chain.blocksUntil(chain.height + 5);
        TopicWire.GovConfigure faster = new TopicWire.GovConfigure(settings(null, false, 1, 600));
        long configured = chain.block(chain.governance(memberA, faster), chain.governance(memberB, faster));
        chain.blocksUntil(configured + 3);
        long head = chain.view().entry("acme/").orElseThrow().head();
        long slow = chain.block(chain.command(statement("acme/", "acme/", head, new Update(openPolicy(), null)),
                ownerO));                                         // lag 5 still in force
        chain.blocksUntil(configured + 5);                        // lag 1 from here
        AppMessage rotate = chain.command(statement("acme/", "acme/", head + 1, new Rotate(keys(ownerN))), ownerO);
        chain.block(rotate);
        assertThat(chain.receipt(rotate).activation()).isEqualTo(slow + 5);
        chain.blocksUntil(slow + 5);
        EntryRecord acme = chain.view().entry("acme/").orElseThrow();
        assertThat(acme.active().revision()).isEqualTo(head + 2);
        assertThat(acme.active().content().owners()).isEqualTo(keys(ownerN));
        assertThat(acme.active().content().policy().writers()).isEqualTo(Writers.OPEN);
    }

    @Test
    void governanceApprovalsExpireAfterTheCommittedWindow() {
        Chain chain = new Chain(settings(null, false, LAG, 2));
        chain.block();
        chain.block(chain.governance(memberA, govCreate("acme/", ownerO)));
        chain.block();
        chain.block();
        chain.block(chain.governance(memberB, govCreate("acme/", ownerO)));
        assertThat(chain.view().entry("acme/")).isEmpty();
        assertThat(chain.view().governancePending()).singleElement()
                .satisfies(approval -> assertThat(approval.approvers()).containsExactly(memberB.publicKeyHex()));
    }

    @Test
    void limitsOpenClaimsAndGovernanceOverrides() {
        Chain chain = new Chain(settings(null, true));
        chain.block();
        AppMessage claim = chain.command(create("neon/", TopicWire.AUTHORITY_CLAIM, ownerO), ownerO);
        long claimed = chain.block(claim);
        chain.blocksUntil(claimed + LAG);
        assertThat(chain.view().entry("neon/").orElseThrow().active()).isNotNull();

        long head = chain.view().entry("neon/").orElseThrow().head();
        List<AppMessage> updates = new ArrayList<>();
        for (int index = 0; index < 5; index++) {
            updates.add(chain.command(statement("neon/", "neon/", head + index,
                    new Update(openPolicy(), null)), ownerO));
        }
        chain.block(updates.toArray(AppMessage[]::new));
        assertThat(chain.receipt(updates.get(4)).code()).isEqualTo(TopicRefusal.TOPIC_COMMAND_LIMIT.name());

        TopicWire.GovRecover recover = new TopicWire.GovRecover("neon/", keys(ownerN));
        long recovered = chain.block(chain.governance(memberA, recover), chain.governance(memberB, recover));
        assertThat(chain.receipt(updates.get(0)).code()).isEqualTo(TopicRefusal.TOPIC_COMMAND_SUPERSEDED.name());
        chain.blocksUntil(recovered + LAG);
        assertThat(chain.view().entry("neon/").orElseThrow().active().content().owners()).isEqualTo(keys(ownerN));

        AppMessage pendingClaim = chain.command(create("spam/", TopicWire.AUTHORITY_CLAIM, issuer), issuer);
        chain.block(pendingClaim);
        TopicWire.GovRetire retire = new TopicWire.GovRetire("spam/");
        chain.block(chain.governance(memberA, retire), chain.governance(memberB, retire));
        assertThat(chain.view().entry("spam/")).as("a pending creation retired by governance is cancelled").isEmpty();
        assertThat(chain.receipt(pendingClaim).code()).isEqualTo(TopicRefusal.TOPIC_COMMAND_SUPERSEDED.name());
    }

    @Test
    void aCancelledPendingCreationFreesItsName() {
        Chain chain = new Chain(settings(keys(registrar), false));
        chain.block();
        AppMessage creation = chain.command(create("neon/", TopicWire.AUTHORITY_REGISTRARS, ownerO), registrar);
        AppMessage reconfigure = chain.governance(memberA, new TopicWire.GovConfigure(settings(keys(ownerN), false)));
        AppMessage approve = chain.governance(memberB, new TopicWire.GovConfigure(settings(keys(ownerN), false)));
        // The registrar change is approved one block before the creation, so it activates first and advances
        // the registrar epoch the creation recorded: the creation is cancelled at its own activation.
        long configured = chain.block(reconfigure, approve);
        long created = chain.block(creation);
        assertThat(created).isEqualTo(configured + 1);
        chain.blocksUntil(created + LAG);
        assertThat(chain.view().config().orElseThrow().revision()).isEqualTo(2);
        assertThat(chain.view().entry("neon/")).isEmpty();
        assertThat(chain.view().count()).isZero();
    }

    @Test
    void inadmissibleCommandsInvalidateTheBlock() {
        Chain chain = chainWithAcme();
        long head = chain.view().entry("acme/").orElseThrow().head();
        AppMessage unauthorized = chain.command(statement("acme/", "acme/", head, new Rotate(keys(issuer))), issuer);
        assertThatThrownBy(() -> chain.block(unauthorized))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(TopicRefusal.TOPIC_COMMAND_DENIED.name());

        AppMessage expired = chain.command(new CommandStatement(CHAIN, GENERATION, "acme/", "acme/", head,
                nonce(9), chain.time - 1, new Rotate(keys(issuer))), ownerO);
        assertThatThrownBy(() -> chain.block(expired))
                .hasMessageContaining(TopicRefusal.TOPIC_COMMAND_EXPIRED.name());

        AppMessage otherGeneration = chain.command(new CommandStatement(CHAIN, "cd".repeat(32), "acme/", "acme/",
                head, nonce(9), chain.time + 1_000, new Rotate(keys(issuer))), ownerO);
        assertThatThrownBy(() -> chain.block(otherGeneration))
                .hasMessageContaining(TopicRefusal.TOPIC_COMMAND_INVALID.name());
    }

    @Test
    void revocationsAndReceiptsArePrunedAfterExpiry() {
        Chain chain = chainWithIssuerDelegation();

        Delegation delegation = delegation(sessionKey, chain.time + 60_000);
        AppMessage revoke = chain.command(new CommandStatement(CHAIN, GENERATION, "acme/",
                TopicWire.AUTHORITY_ISSUER, null, nonce(5), chain.time + 1_000, new Revoke(delegation)), issuer);
        chain.block(revoke);
        String hash = TopicWireCodec.delegationHash(CHAIN, GENERATION, delegation);
        assertThat(chain.view().revoked(hash)).isTrue();
        assertThat(chain.receipt(revoke).code()).isEqualTo("ACCEPTED");

        chain.time += 60_001;
        chain.block();
        assertThat(chain.view().revoked(hash)).isFalse();
        chain.time += 3_600_000;
        chain.block();
        assertThat(chain.view().receipt(revoke.getMessageIdHex())).isEmpty();
        assertThat(chain.state.get(TopicKeys.expiryHead(TopicKeys.CREDENTIAL_QUEUE))).isEmpty();
        assertThat(chain.state.get(TopicKeys.expiryHead(TopicKeys.RECEIPT_QUEUE))).isEmpty();
    }

    @Test
    void aRevocationTakesEffectAfterItsBlockAndDelegatedWritesCarryTheIssuer() {
        Chain chain = chainWithIssuerDelegation();
        Delegation delegation = delegation(sessionKey, chain.time + 60_000);
        AppMessage revoke = chain.command(new CommandStatement(CHAIN, GENERATION, "acme/",
                TopicWire.AUTHORITY_ISSUER, null, nonce(5), chain.time + 1_000, new Revoke(delegation)), issuer);
        byte[] payload = {0x05};
        AppMessage write = chain.message(memberA, "acme/orders",
                frame("acme/orders", payload, 1, chain.time + 30_000, delegation, sessionKey));

        // D8: a revocation is recorded after the block's messages, so a write later in the same block passes
        AppBlockExecutionContext seen = chain.blockSeenByMachine(revoke, write);
        TopicWriteRules.VerifiedWriter writer = TopicMessageView.of(seen.messages().get(1)).orElseThrow()
                .writer().orElseThrow();
        assertThat(writer.kind()).isEqualTo(TopicWriteRules.VerifiedWriter.Kind.DELEGATED);
        assertThat(writer.sessionKey()).isEqualTo(sessionKey.publicKeyHex());
        assertThat(writer.issuer()).isEqualTo(issuer.publicKeyHex());
        assertThat(seen.messages().get(1).getBody()).isEqualTo(payload);

        AppMessage later = chain.message(memberA, "acme/orders",
                frame("acme/orders", payload, 2, chain.time + 30_000, delegation, sessionKey));
        assertThatThrownBy(() -> chain.block(later))
                .hasMessageContaining(TopicRefusal.TOPIC_DELEGATION_INVALID.name());
    }

    @Test
    void aWriteStatementIsUsedOnceUntilItExpires() {
        Chain chain = chainWithIssuerDelegation();
        Delegation delegation = delegation(sessionKey, chain.time + 60_000);
        long notAfter = chain.time + 30_000;
        byte[] frame = frame("acme/orders", new byte[] {0x07}, 3, notAfter, delegation, sessionKey);
        String statementHash = TopicWireCodec.blake2b256Hex(TopicWireCodec.encodeWriteStatement(
                new TopicWire.WriteStatement(CHAIN, GENERATION, "acme/orders",
                        TopicWireCodec.blake2b256Hex(new byte[] {0x07}), nonce(3), notAfter,
                        TopicWireCodec.delegationHash(CHAIN, GENERATION, delegation))));

        assertThatThrownBy(() -> chain.block(chain.message(memberA, "acme/orders", frame),
                chain.message(memberB, "acme/orders", frame)))
                .as("the same statement twice in one block")
                .hasMessageContaining(TopicRefusal.TOPIC_PROOF_REPLAYED.name());

        chain.block(chain.message(memberA, "acme/orders", frame));
        assertThat(chain.view().used(statementHash)).isTrue();
        assertThatThrownBy(() -> chain.block(chain.message(memberB, "acme/orders", frame)))
                .hasMessageContaining(TopicRefusal.TOPIC_PROOF_REPLAYED.name());

        chain.time = notAfter + 1;
        chain.block();
        assertThat(chain.view().used(statementHash)).as("pruned once its proof expired").isFalse();
    }

    @Test
    void staticChainsHaveNoRegistryAndRefuseItsTopics() {
        MemberGroup group = new MemberGroup(Set.of(memberA.publicKeyHex()), 1);
        assertThat(TopicRegistryKernel.forChain(CHAIN, Map.of(), () -> GENERATION, group, 65_536, 100)).isNull();
        assertThatThrownBy(() -> TopicRegistryKernel.forChain(CHAIN, Map.of("topics.mode", "enabled"),
                () -> GENERATION, group, 65_536, 100)).hasMessageContaining("membership.mode=governed");
        assertThat(TopicRegistryKernel.forChain(CHAIN, Map.of("membership.mode", "governed"), () -> GENERATION,
                group, 65_536, 100)).as("governed chains can enable topics later").isNotNull();

        SystemInputKernel kernel = new SystemInputKernel(EffectsSettings.fromSettings(Map.of()),
                new ConsensusProfileGuard(new AppChainConsensusProfile(AppChainConsensusProfile.SCHEMA_VERSION,
                        65_536, 64, 4_194_304, 0, 0, 5_000, false, false, 0, 0, 0, 0, FinalityGate.APP_FINAL,
                        EffectOutcomeCommitment.PER_EFFECT, true, List.of())),
                new ObservationProfileGuard(ObservationProfileV1.disabled()));
        Chain chain = new Chain(null);
        for (String topic : List.of(TopicCommandRules.TOPIC, TopicCommandRules.GOVERNANCE_TOPIC)) {
            AppMessage message = chain.message(memberA, topic, new byte[] {1});
            AppBlock block = new AppBlock(AppBlock.BLOCK_VERSION, CHAIN, 1, new byte[32], 0, new byte[0], T0,
                    AppBlockCodec.messagesRoot(List.of(message)), new byte[32], List.of(message),
                    memberA.publicKey(), FinalityCert.empty());
            // refused before the machine or any reader is consulted
            assertThatThrownBy(() -> kernel.apply(null, AppBlockExecutionContext.fromValidatedBlock(block),
                    new MapState(), null)).hasMessageContaining("topic registry is not available");
        }
    }

    @Test
    void anIssuerOutsideTheDelegateSetCannotRevoke() {
        Chain chain = chainWithAcme();
        Delegation delegation = delegation(sessionKey, chain.time + 60_000);
        AppMessage revoke = chain.command(new CommandStatement(CHAIN, GENERATION, "acme/",
                TopicWire.AUTHORITY_ISSUER, null, nonce(5), chain.time + 1_000, new Revoke(delegation)), issuer);
        assertThatThrownBy(() -> chain.block(revoke)).hasMessageContaining(TopicRefusal.TOPIC_COMMAND_DENIED.name());
    }

    // ------------------------------------------------------------------ fixtures

    private Chain enabledChain() {
        Chain chain = new Chain(settings(null, false));
        chain.block();
        return chain;
    }

    private Chain chainWithAcme() {
        Chain chain = enabledChain();
        chain.block(chain.governance(memberA, govCreate("acme/", ownerO)),
                chain.governance(memberB, govCreate("acme/", ownerO)));
        chain.blocksUntil(chain.height + LAG);
        assertThat(chain.view().entry("acme/").orElseThrow().active()).isNotNull();
        return chain;
    }

    private Settings settings(KeySet registrars, boolean openClaims) {
        return settings(registrars, openClaims, LAG, 600);
    }

    private Settings settings(KeySet registrars, boolean openClaims, long lag, long window) {
        return new Settings(true, registrars, openClaims, true, 3_600_000, 3_600_000, 4, 100, 10, lag, window);
    }

    private GovCreate govCreate(String name, AppMessageSigner owner) {
        return new GovCreate(name, keys(owner), Policy.INHERIT, null);
    }

    private CommandStatement create(String target, String authority, AppMessageSigner owner) {
        return statement(target, authority, null, new Create(keys(owner), Policy.INHERIT, null));
    }

    private CommandStatement statement(String target, String authority, Long expected, TopicWire.Operation op) {
        return new CommandStatement(CHAIN, GENERATION, target, authority, expected, nonce(1),
                T0 + 3_000_000, op);
    }

    /** {@code acme/} writable by {@code issuer}, who may also delegate (D8). */
    private Chain chainWithIssuerDelegation() {
        Chain chain = chainWithAcme();
        long head = chain.view().entry("acme/").orElseThrow().head();
        AppMessage delegatePolicy = chain.command(statement("acme/", "acme/", head, new Update(
                new Policy(new Writers(keys(issuer)), List.of(issuer.publicKeyHex()), null, null, null), null)),
                ownerO);
        chain.blocksUntil(chain.block(delegatePolicy) + LAG);
        return chain;
    }

    /** A framed write signed directly by {@code signer}, or by a session key under {@code delegation}. */
    private static byte[] frame(String topic, byte[] payload, int nonce, long notAfter, Delegation delegation,
                                AppMessageSigner signer) {
        String delegationHash = delegation == null ? null
                : TopicWireCodec.delegationHash(CHAIN, GENERATION, delegation);
        byte[] statement = TopicWireCodec.encodeWriteStatement(new TopicWire.WriteStatement(CHAIN, GENERATION,
                topic, TopicWireCodec.blake2b256Hex(payload), nonce(nonce), notAfter, delegationHash));
        return TopicWireCodec.encodeFrame(new TopicWire.Frame(payload, new TopicWire.WriterProof(nonce(nonce),
                notAfter, List.of(new Signature(signer.publicKeyHex(), signer.sign(statement))), delegation)));
    }

    private Delegation delegation(AppMessageSigner delegate, long notAfter) {
        Delegation unsigned = new Delegation(delegate.publicKeyHex(), "acme/", notAfter - 60_000, notAfter,
                issuer.publicKeyHex(), new byte[64]);
        byte[] signed = TopicWireCodec.delegationSignedBytes(CHAIN, GENERATION, unsigned);
        return new Delegation(unsigned.delegate(), unsigned.scope(), unsigned.notBefore(), unsigned.notAfter(),
                unsigned.issuer(), issuer.sign(signed));
    }

    private static Policy openPolicy() {
        return new Policy(Writers.OPEN, null, null, null, null);
    }

    private static KeySet keys(AppMessageSigner... signers) {
        return new KeySet(1, Arrays.stream(signers).map(AppMessageSigner::publicKeyHex).sorted().toList());
    }

    private static String nonce(int value) {
        return String.format("%032x", value);
    }

    private static AppMessageSigner signer(int seed) {
        byte[] bytes = new byte[32];
        Arrays.fill(bytes, (byte) seed);
        return new AppMessageSigner(HexUtil.encodeHexString(bytes));
    }

    /** A chain of blocks over one in-memory state, driven through the kernel. */
    private final class Chain {
        private final MapState state = new MapState();
        private final TopicRegistryKernel kernel;
        private long height;
        private long time = T0;
        private long sequence;

        Chain(Settings seed) {
            this(seed, Set.of(memberA.publicKeyHex(), memberB.publicKeyHex()), 2);
        }

        Chain(Settings seed, Set<String> members, int threshold) {
            kernel = new TopicRegistryKernel(CHAIN, () -> GENERATION, new MemberGroup(members, threshold), seed,
                    65_536, 100);
        }

        TopicState view() {
            return new TopicState(state, 65_536);
        }

        /** Apply one block and return the execution context the state machine received. */
        AppBlockExecutionContext blockSeenByMachine(AppMessage... messages) {
            AppBlockExecutionContext[] seen = new AppBlockExecutionContext[1];
            apply(messages, context -> seen[0] = context);
            return seen[0];
        }

        long block(AppMessage... messages) {
            return apply(messages, context -> { });
        }

        private long apply(AppMessage[] messages, java.util.function.Consumer<AppBlockExecutionContext> machine) {
            long next = height + 1;
            time += 1_000;
            AppBlock block = new AppBlock(AppBlock.BLOCK_VERSION, CHAIN, next, new byte[32], 0, new byte[0], time,
                    AppBlockCodec.messagesRoot(List.of(messages)), new byte[32], List.of(messages),
                    memberA.publicKey(), FinalityCert.empty());
            MapState candidate = state.copy();
            machine.accept(kernel.beforeMachine(AppBlockExecutionContext.fromValidatedBlock(block), candidate));
            kernel.afterMachine(block.height(), block.timestamp(), candidate);
            state.values.clear();
            state.values.putAll(candidate.values);
            height = next;
            return next;
        }

        void blocksUntil(long targetHeight) {
            while (height < targetHeight) {
                block();
            }
        }

        AppMessage command(CommandStatement statement, AppMessageSigner... signers) {
            byte[] bytes = TopicWireCodec.encodeCommandStatement(statement);
            List<Signature> signatures = new ArrayList<>();
            Arrays.stream(signers).sorted((a, b) -> a.publicKeyHex().compareTo(b.publicKeyHex()))
                    .forEach(s -> signatures.add(new Signature(s.publicKeyHex(), s.sign(bytes))));
            return message(memberA, TopicCommandRules.TOPIC,
                    TopicWireCodec.encodeRegistryCommand(new RegistryCommand(statement, signatures)));
        }

        AppMessage governance(AppMessageSigner member, TopicWire.GovernanceOperation operation) {
            return message(member, TopicCommandRules.GOVERNANCE_TOPIC, TopicWireCodec.encodeGovernanceCommand(
                    new GovernanceCommand(CHAIN, GENERATION, nonce(42), operation)));
        }

        TopicWire.Receipt receipt(AppMessage message) {
            return view().receipt(message.getMessageIdHex()).orElseThrow();
        }

        AppMessage message(AppMessageSigner sender, String topic, byte[] body) {
            long seq = ++sequence;
            long expiresAt = 4_000_000_000L;
            byte[] signed = AppMessage.signedBodyBytes(CHAIN, topic, sender.publicKey(), seq, expiresAt, body);
            return AppMessage.builder()
                    .version(AppMessage.ENVELOPE_VERSION)
                    .messageId(AppMessage.computeMessageId(CHAIN, topic, sender.publicKey(), seq, expiresAt, body))
                    .chainId(CHAIN).topic(topic).sender(sender.publicKey()).senderSeq(seq).expiresAt(expiresAt)
                    .body(body).authScheme(AuthScheme.ED25519.getValue()).authProof(sender.sign(signed))
                    .build();
        }
    }

    private static final class MapState implements AppStateWriter {
        private final Map<String, byte[]> values = new TreeMap<>();

        MapState copy() {
            MapState copy = new MapState();
            copy.values.putAll(values);
            return copy;
        }

        @Override
        public Optional<byte[]> get(byte[] key) {
            return Optional.ofNullable(values.get(HexUtil.encodeHexString(key))).map(byte[]::clone);
        }

        @Override
        public void put(byte[] key, byte[] value) {
            values.put(HexUtil.encodeHexString(key), value.clone());
        }

        @Override
        public void delete(byte[] key) {
            values.remove(HexUtil.encodeHexString(key));
        }

        @Override
        public byte[] stateRoot() {
            return new byte[32];
        }
    }
}
