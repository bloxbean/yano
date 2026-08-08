package com.bloxbean.cardano.yano.appchain.roles.contracts;

/** Product-neutral reference to one approved, application-bound action. */
public interface ApprovalReferenceV1 {
    String proposalId();

    byte[] actionCommitment();

    String policyId();

    long policyRevision();
}
