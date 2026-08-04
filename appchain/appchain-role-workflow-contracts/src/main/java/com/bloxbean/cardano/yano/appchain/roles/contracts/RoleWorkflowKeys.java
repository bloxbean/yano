package com.bloxbean.cardano.yano.appchain.roles.contracts;

import java.nio.charset.StandardCharsets;

/** Frozen authenticated state-key layout used by exact queries and MPF proofs. */
public final class RoleWorkflowKeys {
    private RoleWorkflowKeys() {
    }

    public static byte[] organizationCurrent(String id) {
        return key("o/" + RoleWorkflowIdentifiers.id(id, "organizationId") + "/current");
    }
    public static byte[] organizationRevision(String id, long revision) {
        return key("o/" + RoleWorkflowIdentifiers.id(id, "organizationId")
                + "/r/" + positive(revision, "organization revision"));
    }
    public static byte[] actorCurrent(String id) {
        return key("a/" + RoleWorkflowIdentifiers.id(id, "actorId") + "/current");
    }
    public static byte[] actorRevision(String id, long revision) {
        return key("a/" + RoleWorkflowIdentifiers.id(id, "actorId")
                + "/r/" + positive(revision, "actor revision"));
    }
    public static byte[] policyCurrent(String id) {
        return key("p/" + RoleWorkflowIdentifiers.id(id, "policyId") + "/current");
    }
    public static byte[] policyRevision(String id, long revision) {
        return key("p/" + RoleWorkflowIdentifiers.id(id, "policyId")
                + "/r/" + positive(revision, "policy revision"));
    }
    public static byte[] directPolicyCurrent(String id) {
        return key("d/" + RoleWorkflowIdentifiers.id(id, "policyId") + "/current");
    }
    public static byte[] directPolicyRevision(String id, long revision) {
        return key("d/" + RoleWorkflowIdentifiers.id(id, "policyId")
                + "/r/" + positive(revision, "direct policy revision"));
    }
    public static byte[] authorityCurrent(String id) {
        return key("h/" + RoleWorkflowIdentifiers.id(id, "authorityId") + "/current");
    }
    public static byte[] authorityRevision(String id, long revision) {
        return key("h/" + RoleWorkflowIdentifiers.id(id, "authorityId")
                + "/r/" + positive(revision, "authority revision"));
    }
    public static byte[] proposal(String id) {
        return key("q/" + RoleWorkflowIdentifiers.id(id, "proposalId"));
    }
    public static byte[] approvalStats() { return key("s/proposals/v1"); }
    public static byte[] governedMutation(String id) {
        return key("g/" + RoleWorkflowIdentifiers.id(id, "mutationId"));
    }
    public static byte[] governanceDeadline(long height, String id) {
        return key("x/g/" + height(height) + "/"
                + RoleWorkflowIdentifiers.id(id, "mutationId"));
    }
    public static byte[] approvalDeadline(long height, String id) {
        return key("x/q/" + height(height) + "/"
                + RoleWorkflowIdentifiers.id(id, "proposalId"));
    }
    public static byte[] governanceByActor(String actorId, String mutationId) {
        return key("i/g/a/" + RoleWorkflowIdentifiers.id(actorId, "actorId") + "/"
                + RoleWorkflowIdentifiers.id(mutationId, "mutationId"));
    }
    public static byte[] governanceByAuthority(String authorityId, String mutationId) {
        return key("i/g/h/" + RoleWorkflowIdentifiers.id(authorityId, "authorityId") + "/"
                + RoleWorkflowIdentifiers.id(mutationId, "mutationId"));
    }
    public static byte[] approvalByActor(String actorId, String proposalId) {
        return key("i/q/a/" + RoleWorkflowIdentifiers.id(actorId, "actorId") + "/"
                + RoleWorkflowIdentifiers.id(proposalId, "proposalId"));
    }
    public static byte[] approvalByPolicy(String policyId, String proposalId) {
        return key("i/q/p/" + RoleWorkflowIdentifiers.id(policyId, "policyId") + "/"
                + RoleWorkflowIdentifiers.id(proposalId, "proposalId"));
    }
    public static byte[] pendingCount(String dimension, String id) {
        String canonicalDimension = switch (dimension) {
            case "governance", "approval", "actor", "policy", "authority", "deadline" ->
                    dimension;
            default -> throw new IllegalArgumentException("unsupported pending-count dimension");
        };
        return key("c/" + canonicalDimension + "/"
                + RoleWorkflowIdentifiers.id(id, "pendingCountId"));
    }

    private static long positive(long value, String name) {
        if (value < 1) throw new IllegalArgumentException(name + " must be positive");
        return value;
    }

    private static String height(long value) {
        if (value < 1) throw new IllegalArgumentException("height must be positive");
        return String.format(java.util.Locale.ROOT, "%016x", value);
    }

    private static byte[] key(String value) {
        return value.getBytes(StandardCharsets.US_ASCII);
    }
}
