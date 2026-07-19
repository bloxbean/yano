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
    public static byte[] proposal(String id) {
        return key("q/" + RoleWorkflowIdentifiers.id(id, "proposalId"));
    }
    public static byte[] approvalStats() { return key("s/proposals/v1"); }
    public static byte[] evidenceApproval(String evidenceId, long businessVersion) {
        if (businessVersion < 1) throw new IllegalArgumentException("businessVersion must be positive");
        return key("e/" + RoleWorkflowIdentifiers.id(evidenceId, "evidenceId")
                + "/v/" + businessVersion + "/approval");
    }
    public static byte[] governedMutation(String id) {
        return key("g/" + RoleWorkflowIdentifiers.id(id, "mutationId"));
    }

    private static long positive(long value, String name) {
        if (value < 1) throw new IllegalArgumentException(name + " must be positive");
        return value;
    }

    private static byte[] key(String value) {
        return value.getBytes(StandardCharsets.US_ASCII);
    }
}
