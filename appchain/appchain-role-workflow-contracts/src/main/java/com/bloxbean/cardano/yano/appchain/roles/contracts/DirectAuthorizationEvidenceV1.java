package com.bloxbean.cardano.yano.appchain.roles.contracts;

/**
 * Product-neutral view of one actor-signed, one-use authorization statement.
 * The product contract owns canonical encoding and the exact signing domain;
 * the reusable verifier owns chain/application binding and role-state checks.
 */
public interface DirectAuthorizationEvidenceV1 {
    byte[] authorizationId();

    String chainId();

    byte[] applicationId();

    byte[] actionCommitment();

    String policyId();

    long policyRevision();

    String actorId();

    long actorRevision();

    String keyId();

    byte[] publicKey();

    long issuedHeight();

    long deadlineHeight();

    byte[] statementDigest();

    byte[] signature();

    boolean verifyClaimedKey();
}
