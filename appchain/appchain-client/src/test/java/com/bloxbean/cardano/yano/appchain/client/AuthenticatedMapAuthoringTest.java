package com.bloxbean.cardano.yano.appchain.client;

import com.bloxbean.cardano.client.crypto.KeyGenUtil;
import com.bloxbean.cardano.client.crypto.config.CryptoConfiguration;
import com.bloxbean.cardano.yano.appchain.roles.contracts.ActorStatementV1;
import com.bloxbean.cardano.yano.appchain.stdlib.contracts.AuthenticatedMapAuthorizationContract;
import com.bloxbean.cardano.yano.appchain.stdlib.contracts.AuthenticatedMapContract;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuthenticatedMapAuthoringTest {
    private static final byte[] SEED = Hex.decode("11".repeat(32));
    private static final byte[] PUBLIC_KEY = KeyGenUtil.getPublicKeyFromPrivateKey(SEED);
    private static final byte[] GENESIS_ID = Hex.decode("22".repeat(32));

    @Test
    void externallySignsCompleteActionAndBindsApprovalPayload() {
        AuthenticatedMapContract.Command mutation = AuthenticatedMapContract.Command.single(
                AuthenticatedMapContract.Mutation.put("records",
                        "sku-1".getBytes(StandardCharsets.US_ASCII),
                        "value".getBytes(StandardCharsets.US_ASCII)));
        var assignment = new AuthenticatedMapAuthorizationContract.AuthorizationAssignmentV1(
                0, AuthenticatedMapContract.AUTH_GOVERNED_ROLE, "issuer-write", 1);
        var action = AuthenticatedMapAuthoring.action(mutation, List.of(assignment));
        var request = AuthenticatedMapAuthoring.directSigningRequest(
                Hex.decode("33".repeat(32)), "chain-a", GENESIS_ID, action,
                List.of(0), "issuer-write", 1, "issuer-a", 1,
                "issuer-a-key", PUBLIC_KEY, 10, 20);
        byte[] signature = CryptoConfiguration.INSTANCE.getSigningProvider()
                .sign(request.signingPreimage(), SEED);

        var authorization = AuthenticatedMapAuthoring.completeDirectSignature(
                request, signature);
        var command = AuthenticatedMapAuthoring.command(action, List.of(authorization));
        var decoded = AuthenticatedMapAuthorizationContract.decodeCommand(
                AuthenticatedMapAuthorizationContract.encodeCommand(command));
        ActorStatementV1 proposal = AuthenticatedMapAuthoring.approvalStatement(
                ActorStatementV1.Action.PROPOSE, "chain-a", GENESIS_ID, action,
                "release-1", "release-policy", 1, 30,
                "issuer-a", 1, "issuer-a-key", "");

        assertThat(authorization.verifyClaimedKey()).isTrue();
        assertThat(AuthenticatedMapAuthorizationContract.encodeAction(decoded.action()))
                .isEqualTo(AuthenticatedMapAuthorizationContract.encodeAction(action));
        assertThat(proposal.payloadDomain()).isEqualTo(
                AuthenticatedMapAuthorizationContract.APPROVAL_PAYLOAD_DOMAIN);
        assertThat(proposal.payloadHash()).isEqualTo(
                AuthenticatedMapAuthorizationContract.approvalPayloadHash(
                        GENESIS_ID,
                        AuthenticatedMapAuthorizationContract.actionCommitment(action)));
        assertThatThrownBy(() -> AuthenticatedMapAuthoring.completeDirectSignature(
                request, new byte[64]))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("external signature");
    }
}
