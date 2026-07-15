package com.bloxbean.cardano.yano.appchain.examples.evidence.client;

import com.bloxbean.cardano.yano.appchain.client.AppChainClient;
import com.bloxbean.cardano.yano.appchain.client.ProofVerifier;
import com.bloxbean.cardano.yano.appchain.examples.evidence.EvidenceContract;
import com.bloxbean.cardano.yano.appchain.examples.evidence.query.EvidenceGetResponseV1;
import com.bloxbean.cardano.yano.appchain.examples.evidence.state.EvidenceHeadV1;
import com.bloxbean.cardano.yano.appchain.examples.evidence.state.EvidenceKeys;
import com.bloxbean.cardano.yano.appchain.examples.evidence.state.EvidenceRecordV1;
import com.bloxbean.cardano.yano.appchain.examples.evidence.state.EvidenceStatus;

import java.util.Arrays;

/** Offline binding and cryptographic verification for a found evidence query. */
public final class EvidenceProofVerifier {
    private static final int HASH_BYTES = 32;
    private static final int MAX_STATE_KEY_BYTES = 256;
    private static final int MAX_STATE_VALUE_BYTES = 16 * 1024;
    private static final int MAX_PROOF_WIRE_BYTES = 1024 * 1024;

    private EvidenceProofVerifier() {
    }

    /**
     * Verifies two inclusion proofs against the exact query snapshot and
     * requested identity. This single-shot form treats a snapshot mismatch as
     * an invalid proof; {@link EvidenceClient} performs bounded race retries.
     */
    public static VerifiedEvidence verify(AppChainClient.QueryResult query,
                                          EvidenceGetResponseV1 response,
                                          AppChainClient.Proof headProof,
                                          AppChainClient.Proof recordProof,
                                          String expectedChainId,
                                          String requestedEvidenceId,
                                          long requestedVersion) {
        try {
            return verifyAttempt(query, response, headProof, recordProof,
                    expectedChainId, requestedEvidenceId, requestedVersion);
        } catch (SnapshotChangedException changed) {
            throw failure(EvidenceClientError.PROOF_INVALID);
        }
    }

    /**
     * Verifies that a committed evidence query canonically returned not found.
     * The proof must either exclude the deterministic head key or, for an
     * explicit future version, include a matching head with a lower latest
     * version.
     */
    public static void verifyAbsence(AppChainClient.QueryResult query,
                                     AppChainClient.Proof headProof,
                                     String expectedChainId,
                                     String requestedEvidenceId,
                                     long requestedVersion) {
        final byte[] headKey;
        try {
            headKey = EvidenceKeys.headKey(requestedEvidenceId);
        } catch (RuntimeException invalid) {
            throw failure(EvidenceClientError.INVALID_ARGUMENT);
        }
        try {
            verifyAbsenceAttempt(query, headProof, headKey, expectedChainId,
                    requestedEvidenceId, requestedVersion);
        } catch (SnapshotChangedException changed) {
            throw failure(EvidenceClientError.PROOF_INVALID);
        }
    }

    static VerifiedEvidence verifyAttempt(AppChainClient.QueryResult query,
                                          EvidenceGetResponseV1 response,
                                          AppChainClient.Proof headProof,
                                          AppChainClient.Proof recordProof,
                                          String expectedChainId,
                                          String requestedEvidenceId,
                                          long requestedVersion) {
        if (query == null || response == null || headProof == null || recordProof == null
                || expectedChainId == null || requestedEvidenceId == null
                || requestedVersion < 0) {
            throw failure(EvidenceClientError.INVALID_ARGUMENT);
        }
        if (!expectedChainId.equals(query.chainId())) {
            throw failure(EvidenceClientError.WRONG_CHAIN);
        }
        if (!EvidenceContract.STATE_MACHINE_ID.equals(query.stateMachineId())) {
            throw failure(EvidenceClientError.WRONG_STATE_MACHINE);
        }
        if (!response.found()) {
            throw failure(EvidenceClientError.RESPONSE_MISMATCH);
        }
        try {
            if (!Arrays.equals(query.payload(), response.encode())) {
                throw failure(EvidenceClientError.RESPONSE_MISMATCH);
            }
        } catch (EvidenceClientException mismatch) {
            throw mismatch;
        } catch (RuntimeException malformed) {
            throw failure(EvidenceClientError.RESPONSE_MISMATCH);
        }

        validateResponseIdentity(response, requestedEvidenceId, requestedVersion);
        final EvidenceHeadV1 head;
        final EvidenceRecordV1 record;
        try {
            head = response.head();
            record = response.record();
        } catch (RuntimeException malformed) {
            throw failure(EvidenceClientError.RESPONSE_MISMATCH);
        }

        verifyLeaf(query, response.headKey(), response.headValue(), headProof,
                expectedChainId);
        verifyLeaf(query, response.recordKey(), response.recordValue(), recordProof,
                expectedChainId);

        return new VerifiedEvidence(query.chainId(), query.committedHeight(),
                query.stateRoot(), head, record, EvidenceStatus.derive(record),
                response.headKey(), response.headValue(), response.recordKey(),
                response.recordValue());
    }

    static void validateResponseIdentity(EvidenceGetResponseV1 response,
                                         String requestedEvidenceId,
                                         long requestedVersion) {
        if (response == null || requestedEvidenceId == null || requestedVersion < 0
                || !response.found()) {
            throw failure(EvidenceClientError.RESPONSE_MISMATCH);
        }
        try {
            EvidenceHeadV1 head = response.head();
            EvidenceRecordV1 record = response.record();
            if (!requestedEvidenceId.equals(head.evidenceId())
                    || !requestedEvidenceId.equals(record.evidenceId())
                    || record.businessVersion() != (requestedVersion == 0
                    ? head.latestVersion() : requestedVersion)) {
                throw failure(EvidenceClientError.RESPONSE_MISMATCH);
            }
        } catch (EvidenceClientException mismatch) {
            throw mismatch;
        } catch (RuntimeException malformed) {
            throw failure(EvidenceClientError.RESPONSE_MISMATCH);
        }
    }

    /**
     * Verifies a committed not-found result using the deterministic evidence
     * head key and either its exclusion or a lower-version inclusion.
     */
    static void verifyAbsenceAttempt(AppChainClient.QueryResult query,
                                     AppChainClient.Proof headProof,
                                     byte[] expectedHeadKey,
                                     String expectedChainId,
                                     String requestedEvidenceId,
                                     long requestedVersion) {
        if (query == null || headProof == null || expectedHeadKey == null
                || expectedChainId == null || requestedEvidenceId == null
                || requestedVersion < 0) {
            throw failure(EvidenceClientError.INVALID_ARGUMENT);
        }
        if (!expectedChainId.equals(query.chainId())
                || !expectedChainId.equals(headProof.chainId())) {
            throw failure(EvidenceClientError.WRONG_CHAIN);
        }
        if (!EvidenceContract.STATE_MACHINE_ID.equals(query.stateMachineId())) {
            throw failure(EvidenceClientError.WRONG_STATE_MACHINE);
        }
        try {
            EvidenceGetResponseV1 response = EvidenceGetResponseV1.decode(query.payload());
            if (response.found()) {
                throw failure(EvidenceClientError.RESPONSE_MISMATCH);
            }
        } catch (EvidenceClientException mismatch) {
            throw mismatch;
        } catch (RuntimeException malformed) {
            throw failure(EvidenceClientError.RESPONSE_MISMATCH);
        }

        byte[] key = decodeCanonicalHex(headProof.keyHex(), MAX_STATE_KEY_BYTES);
        byte[] root = decodeCanonicalHex(headProof.stateRootHex(), HASH_BYTES, HASH_BYTES);
        byte[] wire = decodeCanonicalHex(headProof.proofWireHex(), MAX_PROOF_WIRE_BYTES);
        if (!Arrays.equals(key, expectedHeadKey)) {
            throw failure(EvidenceClientError.PROOF_INVALID);
        }
        if (!Arrays.equals(root, query.stateRoot())) {
            throw SnapshotChangedException.INSTANCE;
        }
        Long proofHeight = headProof.committedHeight();
        if (proofHeight == null) {
            throw failure(EvidenceClientError.PROOF_INVALID);
        }
        if (proofHeight != query.committedHeight()) {
            throw SnapshotChangedException.INSTANCE;
        }
        if (wire.length == 0) {
            throw failure(EvidenceClientError.PROOF_INVALID);
        }
        if (headProof.valueHex() == null) {
            if (!ProofVerifier.verifyExclusion(query.stateRoot(), expectedHeadKey, wire)) {
                throw failure(EvidenceClientError.PROOF_INVALID);
            }
            return;
        }

        final byte[] headValue = decodeCanonicalHex(
                headProof.valueHex(), MAX_STATE_VALUE_BYTES);
        final EvidenceHeadV1 head;
        try {
            head = EvidenceHeadV1.decode(headValue);
        } catch (RuntimeException malformed) {
            throw failure(EvidenceClientError.PROOF_INVALID);
        }
        if (requestedVersion == 0 || !requestedEvidenceId.equals(head.evidenceId())
                || requestedVersion <= head.latestVersion()
                || !ProofVerifier.verifyInclusion(query.stateRoot(), expectedHeadKey,
                headValue, wire)) {
            throw failure(EvidenceClientError.PROOF_INVALID);
        }
    }

    private static void verifyLeaf(AppChainClient.QueryResult query,
                                   byte[] expectedKey,
                                   byte[] expectedValue,
                                   AppChainClient.Proof proof,
                                   String expectedChainId) {
        if (!expectedChainId.equals(proof.chainId())) {
            throw failure(EvidenceClientError.WRONG_CHAIN);
        }

        byte[] key = decodeCanonicalHex(proof.keyHex(), MAX_STATE_KEY_BYTES);
        byte[] value = decodeCanonicalHex(proof.valueHex(), MAX_STATE_VALUE_BYTES);
        byte[] root = decodeCanonicalHex(proof.stateRootHex(), HASH_BYTES, HASH_BYTES);
        byte[] wire = decodeCanonicalHex(proof.proofWireHex(), MAX_PROOF_WIRE_BYTES);
        if (!Arrays.equals(key, expectedKey)) {
            throw failure(EvidenceClientError.PROOF_INVALID);
        }

        Long proofHeight = proof.committedHeight();
        if (!Arrays.equals(root, query.stateRoot())) {
            throw SnapshotChangedException.INSTANCE;
        }
        if (proofHeight == null) {
            throw failure(EvidenceClientError.PROOF_INVALID);
        }
        if (proofHeight != query.committedHeight()) {
            throw SnapshotChangedException.INSTANCE;
        }
        if (!Arrays.equals(value, expectedValue) || wire.length == 0) {
            throw failure(EvidenceClientError.PROOF_INVALID);
        }
        if (!ProofVerifier.verifyInclusion(query.stateRoot(), expectedKey, expectedValue, wire)) {
            throw failure(EvidenceClientError.PROOF_INVALID);
        }
    }

    private static byte[] decodeCanonicalHex(String encoded, int maxBytes) {
        return decodeCanonicalHex(encoded, 0, maxBytes);
    }

    private static byte[] decodeCanonicalHex(String encoded, int minBytes, int maxBytes) {
        if (encoded == null || (encoded.length() & 1) != 0
                || encoded.length() < minBytes * 2 || encoded.length() > maxBytes * 2) {
            throw failure(EvidenceClientError.PROOF_INVALID);
        }
        byte[] result = new byte[encoded.length() / 2];
        for (int i = 0; i < result.length; i++) {
            int high = lowerHex(encoded.charAt(i * 2));
            int low = lowerHex(encoded.charAt(i * 2 + 1));
            if (high < 0 || low < 0) {
                throw failure(EvidenceClientError.PROOF_INVALID);
            }
            result[i] = (byte) ((high << 4) | low);
        }
        return result;
    }

    private static int lowerHex(char value) {
        if (value >= '0' && value <= '9') {
            return value - '0';
        }
        return value >= 'a' && value <= 'f' ? value - 'a' + 10 : -1;
    }

    private static EvidenceClientException failure(EvidenceClientError code) {
        return new EvidenceClientException(code);
    }

    static final class SnapshotChangedException extends RuntimeException {
        private static final SnapshotChangedException INSTANCE = new SnapshotChangedException();

        private SnapshotChangedException() {
            super(null, null, false, false);
        }
    }
}
