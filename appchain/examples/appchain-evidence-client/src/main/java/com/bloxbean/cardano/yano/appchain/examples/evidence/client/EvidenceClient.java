package com.bloxbean.cardano.yano.appchain.examples.evidence.client;

import com.bloxbean.cardano.yano.appchain.client.AppChainClient;
import com.bloxbean.cardano.yano.appchain.examples.evidence.EvidenceContract;
import com.bloxbean.cardano.yano.appchain.examples.evidence.command.EvidenceCommandV1;
import com.bloxbean.cardano.yano.appchain.examples.evidence.command.NotifyEvidenceCommandV1;
import com.bloxbean.cardano.yano.appchain.examples.evidence.query.EvidenceGetRequestV1;
import com.bloxbean.cardano.yano.appchain.examples.evidence.query.EvidenceGetResponseV1;
import com.bloxbean.cardano.yano.appchain.examples.evidence.state.EvidenceKeys;

import java.util.Optional;

/**
 * Bounded orchestration and proof-aware reads for the reference evidence
 * registry. The supplied {@link AppChainClient} should be configured for the
 * same explicit chain id.
 */
public final class EvidenceClient {
    /** Conservative retry count for query/proof races caused by new blocks. */
    public static final int DEFAULT_SNAPSHOT_ATTEMPTS = 3;
    private static final int MAX_SNAPSHOT_ATTEMPTS = 8;
    private static final int MESSAGE_ID_BYTES = 32;

    private final AppChainClient client;
    private final String expectedChainId;
    private final int snapshotAttempts;

    /** Creates a client with three bounded snapshot attempts. */
    public EvidenceClient(AppChainClient client, String expectedChainId) {
        this(client, expectedChainId, DEFAULT_SNAPSHOT_ATTEMPTS);
    }

    /** Creates a client with an explicit bounded snapshot-attempt count. */
    public EvidenceClient(AppChainClient client, String expectedChainId,
                          int snapshotAttempts) {
        if (client == null) {
            throw failure(EvidenceClientError.INVALID_ARGUMENT);
        }
        this.client = client;
        this.expectedChainId = validateChainId(expectedChainId);
        if (snapshotAttempts < 1 || snapshotAttempts > MAX_SNAPSHOT_ATTEMPTS) {
            throw failure(EvidenceClientError.INVALID_ARGUMENT);
        }
        this.snapshotAttempts = snapshotAttempts;
    }

    /** Submits one strict canonical evidence command on the frozen topic. */
    public AppChainClient.SubmitResult submit(EvidenceCommandV1 command) {
        if (command == null) {
            throw failure(EvidenceClientError.INVALID_ARGUMENT);
        }
        final byte[] encoded;
        try {
            encoded = command.encode();
        } catch (RuntimeException invalid) {
            throw failure(EvidenceClientError.INVALID_ARGUMENT);
        }
        if (encoded.length == 0 || encoded.length > EvidenceContract.MAX_COMMAND_BYTES) {
            throw failure(EvidenceClientError.INVALID_ARGUMENT);
        }

        final AppChainClient.SubmitResult result;
        try {
            result = client.submit(EvidenceContract.COMMAND_TOPIC, encoded);
        } catch (RuntimeException transport) {
            throw failure(EvidenceClientError.TRANSPORT_FAILURE);
        }
        if (result == null || !expectedChainId.equals(result.chainId())
                || !EvidenceContract.COMMAND_TOPIC.equals(result.topic())
                || !canonicalLowerHex(result.messageId(), MESSAGE_ID_BYTES)) {
            throw failure(EvidenceClientError.RESPONSE_MISMATCH);
        }
        return result;
    }

    /** Submits the idempotent result-gated notification continuation. */
    public AppChainClient.SubmitResult notify(String evidenceId, long businessVersion) {
        final NotifyEvidenceCommandV1 command;
        try {
            command = new NotifyEvidenceCommandV1(evidenceId, businessVersion);
        } catch (RuntimeException invalid) {
            throw failure(EvidenceClientError.INVALID_ARGUMENT);
        }
        return submit(command);
    }

    /**
     * Looks up a version and verifies its head and immutable record against one
     * exact committed state root. Version zero means latest.
     */
    public Optional<VerifiedEvidence> queryVerified(String evidenceId,
                                                    long businessVersion) {
        final EvidenceGetRequestV1 request;
        try {
            request = new EvidenceGetRequestV1(evidenceId, businessVersion);
        } catch (RuntimeException invalid) {
            throw failure(EvidenceClientError.INVALID_ARGUMENT);
        }

        for (int attempt = 0; attempt < snapshotAttempts; attempt++) {
            AppChainClient.QueryResult query = executeQuery(request);
            EvidenceGetResponseV1 response = decodeResponse(query);
            if (!response.found()) {
                AppChainClient.Proof absenceProof = fetchProof(
                        EvidenceKeys.headKey(request.evidenceId()));
                try {
                    EvidenceProofVerifier.verifyAbsenceAttempt(query, absenceProof,
                            EvidenceKeys.headKey(request.evidenceId()), expectedChainId,
                            request.evidenceId(), request.businessVersion());
                    return Optional.empty();
                } catch (EvidenceProofVerifier.SnapshotChangedException changed) {
                    continue;
                }
            }

            EvidenceProofVerifier.validateResponseIdentity(response,
                    request.evidenceId(), request.businessVersion());

            AppChainClient.Proof headProof = fetchProof(response.headKey());
            AppChainClient.Proof recordProof = fetchProof(response.recordKey());
            try {
                return Optional.of(EvidenceProofVerifier.verifyAttempt(
                        query, response, headProof, recordProof, expectedChainId,
                        request.evidenceId(), request.businessVersion()));
            } catch (EvidenceProofVerifier.SnapshotChangedException changed) {
                // A newer committed block may land between query and proof reads.
                // Retry the complete sequence; never combine snapshots.
            }
        }
        throw failure(EvidenceClientError.SNAPSHOT_RACE_EXHAUSTED);
    }

    private AppChainClient.QueryResult executeQuery(EvidenceGetRequestV1 request) {
        final AppChainClient.QueryResult query;
        try {
            query = client.query(EvidenceContract.GET_QUERY_PATH, request.encode());
        } catch (RuntimeException transport) {
            throw failure(EvidenceClientError.TRANSPORT_FAILURE);
        }
        if (query == null) {
            throw failure(EvidenceClientError.TRANSPORT_FAILURE);
        }
        if (!expectedChainId.equals(query.chainId())) {
            throw failure(EvidenceClientError.WRONG_CHAIN);
        }
        if (!EvidenceContract.STATE_MACHINE_ID.equals(query.stateMachineId())) {
            throw failure(EvidenceClientError.WRONG_STATE_MACHINE);
        }
        return query;
    }

    private static EvidenceGetResponseV1 decodeResponse(AppChainClient.QueryResult query) {
        try {
            return EvidenceGetResponseV1.decode(query.payload());
        } catch (RuntimeException malformed) {
            throw failure(EvidenceClientError.RESPONSE_MISMATCH);
        }
    }

    private AppChainClient.Proof fetchProof(byte[] key) {
        try {
            return client.proof(key).orElseThrow(
                    () -> failure(EvidenceClientError.PROOF_MISSING));
        } catch (EvidenceClientException expected) {
            throw expected;
        } catch (RuntimeException transport) {
            throw failure(EvidenceClientError.TRANSPORT_FAILURE);
        }
    }

    private static String validateChainId(String chainId) {
        if (chainId == null || chainId.isBlank()) {
            throw failure(EvidenceClientError.INVALID_ARGUMENT);
        }
        return chainId;
    }

    private static boolean canonicalLowerHex(String value, int bytes) {
        if (value == null || value.length() != bytes * 2) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f'))) {
                return false;
            }
        }
        return true;
    }

    private static EvidenceClientException failure(EvidenceClientError code) {
        return new EvidenceClientException(code);
    }
}
