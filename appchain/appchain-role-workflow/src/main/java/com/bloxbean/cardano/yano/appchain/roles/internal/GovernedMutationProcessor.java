package com.bloxbean.cardano.yano.appchain.roles.internal;

import com.bloxbean.cardano.yano.api.appchain.AppStateWriter;
import com.bloxbean.cardano.yano.appchain.roles.RoleWorkflowGovernanceConfig;
import com.bloxbean.cardano.yano.appchain.roles.contracts.GovernedMutationCommandV1;
import com.bloxbean.cardano.yano.appchain.roles.contracts.RoleWorkflowKeys;
import com.bloxbean.cardano.yano.appchain.roles.contracts.RoleWorkflowLimits;

import java.security.MessageDigest;

public final class GovernedMutationProcessor {
    private final RoleWorkflowGovernanceConfig config;

    public GovernedMutationProcessor(RoleWorkflowGovernanceConfig config) {
        this.config = java.util.Objects.requireNonNull(config, "config");
    }

    public void apply(GovernedMutationCommandV1 command, byte[] sender, long height,
                      AppStateWriter state, MutationHandler handler) {
        if (!config.isAdministrator(sender)) return;
        try {
            if (command instanceof GovernedMutationCommandV1.Propose proposed) {
                propose(proposed, sender, height, state, handler);
                return;
            }
            byte[] key = RoleWorkflowKeys.governedMutation(command.mutationId());
            GovernanceRecordV1 existing = state.get(key).map(GovernanceRecordV1::decode).orElse(null);
            if (existing != null) validateExisting(existing, command.mutationId());
            if (existing == null || existing.status() != GovernanceRecordV1.Status.PENDING) return;
            if (height > existing.expiryHeight()) {
                terminal(state, key, existing.withStatus(GovernanceRecordV1.Status.EXPIRED));
                return;
            }
            byte[] expectedHash = switch (command) {
                case GovernedMutationCommandV1.Approve value -> value.mutationHash();
                case GovernedMutationCommandV1.Activate value -> value.mutationHash();
                case GovernedMutationCommandV1.Cancel value -> value.mutationHash();
                default -> new byte[0];
            };
            if (!MessageDigest.isEqual(existing.mutationHash(), expectedHash)) return;
            if (command instanceof GovernedMutationCommandV1.Approve) {
                state.put(key, existing.withApproval(sender).encode());
            } else if (command instanceof GovernedMutationCommandV1.Activate) {
                if (existing.approvals().size() < config.threshold()) return;
                boolean applied = handler.activate(existing.mutation(), height, state);
                terminal(state, key, existing.withStatus(applied
                        ? GovernanceRecordV1.Status.ACTIVATED : GovernanceRecordV1.Status.FAILED));
            } else if (command instanceof GovernedMutationCommandV1.Cancel
                    && MessageDigest.isEqual(sender, existing.proposer())) {
                terminal(state, key, existing.withStatus(GovernanceRecordV1.Status.CANCELLED));
            }
        } catch (IllegalArgumentException invalid) {
            // Invalid finalized commands are deterministic no-ops. State-corruption
            // exceptions use IllegalStateException and still fail loudly.
        }
    }

    private void propose(GovernedMutationCommandV1.Propose command, byte[] sender, long height,
                         AppStateWriter state, MutationHandler handler) {
        long maximumExpiry;
        try {
            maximumExpiry = Math.addExact(height, config.maximumMutationLifetimeBlocks());
        } catch (ArithmeticException exhausted) {
            maximumExpiry = Long.MAX_VALUE;
        }
        if (command.expiryHeight() <= height || command.expiryHeight() > maximumExpiry) return;
        handler.validate(command.mutation());
        byte[] key = RoleWorkflowKeys.governedMutation(command.mutationId());
        GovernanceRecordV1 existing = state.get(key).map(GovernanceRecordV1::decode).orElse(null);
        if (existing != null) return;
        int pending = RoleState.pendingCount(state);
        if (pending >= RoleWorkflowLimits.MAX_PENDING_MUTATIONS) return;
        GovernanceRecordV1 created = new GovernanceRecordV1(command.mutationId(),
                command.mutation(), command.mutationHash(), command.expiryHeight(), sender,
                java.util.List.of(sender), GovernanceRecordV1.Status.PENDING);
        state.put(key, created.encode());
        RoleState.pendingCount(state, pending + 1);
    }

    private static void terminal(AppStateWriter state, byte[] key, GovernanceRecordV1 record) {
        state.put(key, record.encode());
        RoleState.pendingCount(state, RoleState.pendingCount(state) - 1);
    }

    private void validateExisting(GovernanceRecordV1 record, String mutationId) {
        if (!record.mutationId().equals(mutationId)
                || !config.isAdministrator(record.proposer())
                || record.approvals().stream().noneMatch(
                approval -> MessageDigest.isEqual(approval, record.proposer()))
                || record.approvals().stream().anyMatch(
                approval -> !config.isAdministrator(approval))) {
            throw new IllegalStateException("corrupt role-workflow governance authority");
        }
    }

    public interface MutationHandler {
        void validate(byte[] mutation);
        boolean activate(byte[] mutation, long height, AppStateWriter state);
    }
}
