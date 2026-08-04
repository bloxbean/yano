package com.bloxbean.cardano.yano.appchain.roles.internal;

import com.bloxbean.cardano.client.crypto.KeyGenUtil;
import com.bloxbean.cardano.yano.api.appchain.AppStateWriter;
import com.bloxbean.cardano.yano.appchain.roles.contracts.ActorGovernanceCommandV1;
import com.bloxbean.cardano.yano.appchain.roles.contracts.ActorKeyEpochV1;
import com.bloxbean.cardano.yano.appchain.roles.contracts.ActorRecordV1;
import com.bloxbean.cardano.yano.appchain.roles.contracts.AdministratorAuthorityV1;
import com.bloxbean.cardano.yano.appchain.roles.contracts.AdministratorStatementV1;
import com.bloxbean.cardano.yano.appchain.roles.contracts.GovernedAuthorizationLimitsV1;
import com.bloxbean.cardano.yano.appchain.roles.contracts.GovernedMutationRecordV1;
import com.bloxbean.cardano.yano.appchain.roles.contracts.OrganizationRecordV1;
import com.bloxbean.cardano.yano.appchain.roles.contracts.RecordStatus;
import com.bloxbean.cardano.yano.appchain.roles.contracts.RegistryMutationV1;
import com.bloxbean.cardano.yano.appchain.roles.contracts.RoleWorkflowKeys;
import com.bloxbean.cardano.yano.appchain.roles.contracts.RoleWorkflowResultCode;
import com.bloxbean.cardano.yano.appchain.roles.contracts.SignedAdministratorStatementV1;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class ActorGovernanceProcessorTest {
    private static final String CHAIN_ID = "governed-map";
    private static final byte[] GENESIS_ID = repeated(9);
    private static final byte[] SEED_A = repeated(1);
    private static final byte[] SEED_B = repeated(2);

    private final MemoryState state = new MemoryState();
    private ActorGovernanceProcessor processor;
    private AdministratorAuthorityV1 authority;

    @BeforeEach
    void setUp() {
        actor("admin-a", "org-a", "admin-a-key", SEED_A);
        actor("admin-b", "org-b", "admin-b-key", SEED_B);
        authority = new AdministratorAuthorityV1(
                "registry-admins", 1, List.of("admin-a", "admin-b"), 2, 100);
        state.put(RoleWorkflowKeys.authorityRevision("registry-admins", 1),
                authority.encode());
        RoleState.pointer(state, RoleWorkflowKeys.authorityCurrent("registry-admins"), 1);
        processor = new ActorGovernanceProcessor(CHAIN_ID, GENESIS_ID,
                "registry-admins", GovernedAuthorizationLimitsV1.defaults());
    }

    @Test
    void thresholdLifecycleIsActorAuthenticatedAndRelayNeutral() {
        byte[] mutation = new byte[]{1, 2, 3};
        Subject subject = new Subject("change-1", mutation, 4, 20);
        ActorGovernanceProcessor.MutationHandler handler = handler(false);

        assertThat(processor.apply(command(ActorGovernanceCommandV1.Operation.PROPOSE,
                        subject, 2, List.of("admin-a")),
                2, state, state, handler)).isEqualTo(RoleWorkflowResultCode.ACCEPTED);
        GovernedMutationRecordV1 proposed = record(subject.id());
        assertThat(proposed.approvals()).hasSize(1);

        assertThat(processor.apply(command(ActorGovernanceCommandV1.Operation.APPROVE,
                        subject, 3, List.of("admin-b")),
                3, state, state, handler)).isEqualTo(RoleWorkflowResultCode.ACCEPTED);
        assertThat(record(subject.id()).approvals()).hasSize(2);

        assertThat(processor.apply(command(ActorGovernanceCommandV1.Operation.ACTIVATE,
                        subject, 4, List.of()),
                4, state, state, handler)).isEqualTo(RoleWorkflowResultCode.ACCEPTED);
        assertThat(record(subject.id()).status())
                .isEqualTo(GovernedMutationRecordV1.Status.ACTIVATED);
        assertThat(GovernancePendingIndexV1.decode(state.get(
                RoleWorkflowKeys.governancePendingIndex()).orElseThrow()).entries())
                .isEmpty();
    }

    @Test
    void proposerCancellationExpiryAndAuthorityHandoverReclaimCapacity() {
        ActorGovernanceProcessor.MutationHandler handler = handler(true);
        Subject cancelled = new Subject("cancel-me", new byte[]{4}, 3, 10);
        processor.apply(command(ActorGovernanceCommandV1.Operation.PROPOSE,
                        cancelled, 2, List.of("admin-a")),
                2, state, state, handler);
        assertThat(processor.apply(command(ActorGovernanceCommandV1.Operation.CANCEL,
                        cancelled, 3, List.of("admin-a")),
                3, state, state, handler)).isEqualTo(RoleWorkflowResultCode.ACCEPTED);
        assertThat(record(cancelled.id()).status())
                .isEqualTo(GovernedMutationRecordV1.Status.CANCELLED);

        Subject expired = new Subject("expire-me", new byte[]{5}, 4, 5);
        processor.apply(command(ActorGovernanceCommandV1.Operation.PROPOSE,
                        expired, 4, List.of("admin-a")),
                4, state, state, handler);
        processor.prepareHeight(6, state, state);
        assertThat(record(expired.id()).status())
                .isEqualTo(GovernedMutationRecordV1.Status.EXPIRED);

        Subject stale = new Subject("stale-change", new byte[]{6}, 7, 30);
        processor.apply(command(ActorGovernanceCommandV1.Operation.PROPOSE,
                        stale, 7, List.of("admin-a")),
                7, state, state, handler);
        AdministratorAuthorityV1 successor = new AdministratorAuthorityV1(
                "registry-admins", 2, List.of("admin-b"), 1, 100);
        byte[] successorMutation = new RegistryMutationV1.PutAuthority(successor).encode();
        Subject handover = new Subject("authority-2", successorMutation, 9, 30);
        processor.apply(command(ActorGovernanceCommandV1.Operation.PROPOSE,
                        handover, 8, List.of("admin-a")),
                8, state, state, handler);
        processor.apply(command(ActorGovernanceCommandV1.Operation.APPROVE,
                        handover, 8, List.of("admin-b")),
                8, state, state, handler);
        assertThat(processor.apply(command(ActorGovernanceCommandV1.Operation.ACTIVATE,
                        handover, 9, List.of()),
                9, state, state, handler)).isEqualTo(RoleWorkflowResultCode.ACCEPTED);
        assertThat(record(stale.id()).status())
                .isEqualTo(GovernedMutationRecordV1.Status.SUPERSEDED);
        assertThat(RoleState.pointer(state,
                RoleWorkflowKeys.authorityCurrent("registry-admins"))).isEqualTo(2);
    }

    private ActorGovernanceProcessor.MutationHandler handler(boolean authorityChanges) {
        return new ActorGovernanceProcessor.MutationHandler() {
            @Override
            public void validate(byte[] mutation, AppStateWriter authorityState,
                                 AppStateWriter ownedState) {
                if (mutation.length == 0) throw new IllegalArgumentException();
            }

            @Override
            public boolean activate(byte[] mutation, long height,
                                    AppStateWriter authorityState,
                                    AppStateWriter ownedState) {
                if (authorityChanges) {
                    try {
                        RegistryMutationV1 decoded = RegistryMutationV1.decode(mutation);
                        if (decoded instanceof RegistryMutationV1.PutAuthority put) {
                            AdministratorAuthorityV1 successor = put.authority();
                            authorityState.put(RoleWorkflowKeys.authorityRevision(
                                    successor.authorityId(), successor.revision()),
                                    successor.encode());
                            RoleState.pointer(authorityState,
                                    RoleWorkflowKeys.authorityCurrent(
                                            successor.authorityId()),
                                    successor.revision());
                        }
                    } catch (RuntimeException ignored) {
                        // Other test mutations deliberately use opaque bytes.
                    }
                }
                return true;
            }
        };
    }

    private ActorGovernanceCommandV1 command(
            ActorGovernanceCommandV1.Operation operation,
            Subject subject,
            long issuedHeight,
            List<String> actors
    ) {
        List<SignedAdministratorStatementV1> votes = actors.stream()
                .map(actorId -> signed(operation, subject, issuedHeight, actorId))
                .toList();
        return new ActorGovernanceCommandV1(operation, subject.id(),
                operation == ActorGovernanceCommandV1.Operation.PROPOSE
                        ? subject.mutation() : new byte[0], votes);
    }

    private SignedAdministratorStatementV1 signed(
            ActorGovernanceCommandV1.Operation operation,
            Subject subject,
            long issuedHeight,
            String actorId
    ) {
        AdministratorStatementV1.Decision decision = switch (operation) {
            case PROPOSE -> AdministratorStatementV1.Decision.PROPOSE;
            case APPROVE -> AdministratorStatementV1.Decision.APPROVE;
            case CANCEL -> AdministratorStatementV1.Decision.CANCEL;
            case ACTIVATE -> throw new IllegalArgumentException();
        };
        byte[] seed = actorId.equals("admin-a") ? SEED_A : SEED_B;
        String keyId = actorId + "-key";
        AdministratorStatementV1 statement = new AdministratorStatementV1(
                decision, CHAIN_ID, GENESIS_ID, authority.authorityId(),
                authority.revision(), subject.id(),
                ActorGovernanceCommandV1.mutationHash(subject.mutation()),
                subject.notBefore(), subject.expiry(), actorId, 1, keyId,
                KeyGenUtil.getPublicKeyFromPrivateKey(seed),
                issuedHeight, subject.expiry(), AdministratorStatementV1.ED25519);
        return SignedAdministratorStatementV1.sign(statement, seed);
    }

    private void actor(String actorId, String organizationId,
                       String keyId, byte[] seed) {
        OrganizationRecordV1 organization = new OrganizationRecordV1(
                organizationId, 1, RecordStatus.ACTIVE, new byte[0]);
        state.put(RoleWorkflowKeys.organizationRevision(organizationId, 1),
                organization.encode());
        RoleState.pointer(state, RoleWorkflowKeys.organizationCurrent(organizationId), 1);
        ActorKeyEpochV1 key = new ActorKeyEpochV1(keyId,
                KeyGenUtil.getPublicKeyFromPrivateKey(seed),
                1, 0, RecordStatus.ACTIVE);
        ActorRecordV1 actor = new ActorRecordV1(actorId, organizationId, 1,
                RecordStatus.ACTIVE, List.of("registry-admin"),
                List.of(key), new byte[0]);
        state.put(RoleWorkflowKeys.actorRevision(actorId, 1), actor.encode());
        RoleState.pointer(state, RoleWorkflowKeys.actorCurrent(actorId), 1);
    }

    private GovernedMutationRecordV1 record(String mutationId) {
        return state.get(RoleWorkflowKeys.governedMutation(mutationId))
                .map(GovernedMutationRecordV1::decode).orElseThrow();
    }

    private record Subject(String id, byte[] mutation, long notBefore, long expiry) {
        private Subject {
            mutation = mutation.clone();
        }
        @Override public byte[] mutation() { return mutation.clone(); }
    }

    private static byte[] repeated(int value) {
        byte[] bytes = new byte[32];
        Arrays.fill(bytes, (byte) value);
        return bytes;
    }

    private static final class MemoryState implements AppStateWriter {
        private final Map<Key, byte[]> values = new LinkedHashMap<>();

        @Override
        public Optional<byte[]> get(byte[] key) {
            byte[] value = values.get(new Key(key));
            return value == null ? Optional.empty() : Optional.of(value.clone());
        }

        @Override public byte[] stateRoot() { return new byte[32]; }

        @Override public void put(byte[] key, byte[] value) {
            values.put(new Key(key), value.clone());
        }

        @Override public void delete(byte[] key) {
            values.remove(new Key(key));
        }
    }

    private record Key(byte[] value) {
        private Key {
            value = value.clone();
        }
        @Override public byte[] value() { return value.clone(); }
        @Override public boolean equals(Object other) {
            return other instanceof Key key && Arrays.equals(value, key.value);
        }
        @Override public int hashCode() { return Arrays.hashCode(value); }
    }
}
