package com.bloxbean.cardano.yano.appchain.roles;

import com.bloxbean.cardano.yano.api.appchain.AppChainMembershipEpoch;
import com.bloxbean.cardano.yano.api.appchain.AppStateMachineContext;
import com.bloxbean.cardano.yano.appchain.roles.contracts.RoleWorkflowLimits;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

/** Profile-owned v1 administration policy, derived from genesis membership. */
public record RoleWorkflowGovernanceConfig(List<byte[]> administrators,
                                           int threshold,
                                           int maximumMutationLifetimeBlocks) {
    public static final int DEFAULT_MAXIMUM_MUTATION_LIFETIME_BLOCKS = 1_000;

    public RoleWorkflowGovernanceConfig {
        if (administrators == null || administrators.isEmpty()
                || administrators.size() > RoleWorkflowLimits.MAX_ADMINISTRATORS
                || threshold < 1 || threshold > administrators.size()
                || maximumMutationLifetimeBlocks < 1
                || maximumMutationLifetimeBlocks > 1_000_000) {
            throw new IllegalArgumentException("invalid role-workflow governance configuration");
        }
        administrators = administrators.stream().map(byte[]::clone)
                .sorted(java.util.Arrays::compareUnsigned).toList();
        if (administrators.stream().anyMatch(key -> key.length != 32)
                || distinct(administrators) != administrators.size()) {
            throw new IllegalArgumentException("invalid role-workflow administrators");
        }
    }

    @Override public List<byte[]> administrators() {
        return administrators.stream().map(byte[]::clone).toList();
    }

    public static RoleWorkflowGovernanceConfig from(AppStateMachineContext context) {
        AppChainMembershipEpoch genesis = context.membershipView().orElseThrow(() ->
                new IllegalArgumentException("role workflow requires membershipView()"))
                .epochAt(1);
        int lifetime = integer(context.settings().get(
                "machines.composite.roles.maximum-mutation-lifetime-blocks"),
                DEFAULT_MAXIMUM_MUTATION_LIFETIME_BLOCKS);
        return new RoleWorkflowGovernanceConfig(genesis.members().stream()
                .map(HexFormat.of()::parseHex).toList(), genesis.threshold(), lifetime);
    }

    public boolean isAdministrator(byte[] sender) {
        return sender != null && administrators.stream()
                .anyMatch(key -> MessageDigest.isEqual(key, sender));
    }

    public String configurationId() {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update("yano:role-governance-config:v1\0"
                    .getBytes(StandardCharsets.US_ASCII));
            digest.update(ByteBuffer.allocate(12).putInt(threshold)
                    .putInt(maximumMutationLifetimeBlocks).putInt(administrators.size()).array());
            administrators.forEach(digest::update);
            return "genesis-members-v1:" + HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static int integer(String value, int fallback) {
        if (value == null) return fallback;
        if (!value.matches("[1-9][0-9]{0,6}")) throw new IllegalArgumentException(
                "invalid machines.composite.roles.maximum-mutation-lifetime-blocks");
        return Integer.parseInt(value);
    }

    private static long distinct(List<byte[]> keys) {
        return keys.stream().map(HexFormat.of()::formatHex).distinct().count();
    }
}
