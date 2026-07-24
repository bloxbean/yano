package com.bloxbean.cardano.yano.appchain.eutxo.contracts;

import java.util.List;

/**
 * Pure deterministic optional commitment boundary for validity-proof modules.
 *
 * <p>Implementations must not perform proof generation, filesystem, network,
 * clock or randomness access from these methods. Proof generation consumes
 * the committed witness descriptor asynchronously.</p>
 */
public interface EutxoValidityCommitmentEngine {

    String id();

    /** Digest of the immutable validity circuit/profile semantics. */
    String profileDigest();

    /** Immutable L2 authorization profile selected by this engine. */
    default String authorizationProfile() {
        return "";
    }

    /** Digest of the immutable L2 authorization-profile semantics. */
    default String authorizationProfileDigest() {
        return "";
    }

    /**
     * Deterministic host verification for the selected L2 authorization.
     *
     * <p>Development profiles use this as a trusted-prover guard. It does not
     * replace equivalent in-circuit constraints against a malicious prover.</p>
     */
    default AuthorizationResult verifyAuthorization(
            EutxoL2Transaction transaction,
            List<EutxoL2KeyRegistration> registrations
    ) {
        return AuthorizationResult.reject(
                "L2_AUTHORIZATION_UNSUPPORTED",
                "selected validity engine has no L2 authorization profile");
    }

    EutxoValidityCommitment genesis();

    EutxoValidityCommitment commit(EutxoValidityTransition transition);

    record AuthorizationResult(boolean accepted, String code, String detail) {
        public AuthorizationResult {
            code = code == null ? "" : code;
            detail = detail == null ? "" : detail;
        }

        public static AuthorizationResult accept() {
            return new AuthorizationResult(true, "", "");
        }

        public static AuthorizationResult reject(String code, String detail) {
            return new AuthorizationResult(false, code, detail);
        }
    }
}
