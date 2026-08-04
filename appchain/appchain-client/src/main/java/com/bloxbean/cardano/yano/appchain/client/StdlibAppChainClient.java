package com.bloxbean.cardano.yano.appchain.client;

import com.bloxbean.cardano.yano.appchain.stdlib.contracts.ApprovalsContract;
import com.bloxbean.cardano.yano.appchain.stdlib.contracts.AuthenticatedMapContract;
import com.bloxbean.cardano.yano.appchain.stdlib.contracts.BalancesContract;
import com.bloxbean.cardano.yano.appchain.stdlib.contracts.DocTrailContract;
import com.bloxbean.cardano.yano.appchain.stdlib.contracts.KvRegistryContract;
import com.bloxbean.cardano.yano.appchain.stdlib.contracts.AuthenticatedMapAuthorizationContract;
import com.bloxbean.cardano.yano.appchain.roles.contracts.ActorGovernanceCommandV1;
import com.bloxbean.cardano.yano.appchain.roles.contracts.SignedActorCommandV1;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

/** Typed Java facade for the stock standard-library state machines. */
public final class StdlibAppChainClient {
    private final AppChainClient client;
    private final TrustedRootResolver trustedRootResolver;

    /**
     * Compatibility mode that checks only proof/root internal consistency.
     * Prefer {@link #StdlibAppChainClient(AppChainClient, TrustedRootResolver)}
     * at every trust boundary.
     */
    @Deprecated(forRemoval = false)
    public StdlibAppChainClient(AppChainClient client) {
        this.client = Objects.requireNonNull(client, "client");
        this.trustedRootResolver = null;
    }

    /** Decode stock state only after binding each proof to independent trust. */
    public StdlibAppChainClient(
            AppChainClient client,
            TrustedRootResolver trustedRootResolver
    ) {
        this.client = Objects.requireNonNull(client, "client");
        this.trustedRootResolver = Objects.requireNonNull(
                trustedRootResolver, "trustedRootResolver");
    }

    public AppChainClient client() {
        return client;
    }

    public AppChainClient.SubmitResult kvPut(byte[] key, byte[] value) {
        return client.submit(KvRegistryContract.DEFAULT_TOPIC,
                KvRegistryContract.put(key, value));
    }

    public AppChainClient.SubmitResult kvDelete(byte[] key) {
        return client.submit(KvRegistryContract.DEFAULT_TOPIC,
                KvRegistryContract.delete(key));
    }

    public Optional<VerifiedState<KvRegistryContract.Entry>> kvEntry(byte[] key) {
        return verified(key, KvRegistryContract::decodeEntry);
    }

    public AppChainClient.SubmitResult authenticatedMapCommand(
            AuthenticatedMapContract.Command command) {
        return client.submit(AuthenticatedMapContract.DEFAULT_TOPIC,
                AuthenticatedMapContract.encodeCommand(command));
    }

    /** Execute a fully action-bound basic/direct-role/approval map command. */
    public AppChainClient.SubmitResult authenticatedMapGovernedCommand(
            AuthenticatedMapAuthorizationContract.AuthenticatedMapCommandV1 command) {
        return client.submit(AuthenticatedMapContract.DEFAULT_TOPIC,
                AuthenticatedMapAuthorizationContract.encodeCommand(command));
    }

    /** Submit one externally signed proposal/decision statement. */
    public AppChainClient.SubmitResult authenticatedMapApprovalCommand(
            SignedActorCommandV1 command) {
        return client.submit(SignedActorCommandV1.DEFAULT_TOPIC, command.encode());
    }

    /** Submit actor-authenticated policy governance through the approval workflow. */
    public AppChainClient.SubmitResult authenticatedMapPolicyGovernance(
            ActorGovernanceCommandV1 command) {
        return client.submit(ActorGovernanceCommandV1.POLICY_TOPIC, command.encode());
    }

    /** Submit actor/organization/authority governance through the actor registry. */
    public AppChainClient.SubmitResult authenticatedMapActorGovernance(
            ActorGovernanceCommandV1 command) {
        return client.submit(ActorGovernanceCommandV1.ACTOR_REGISTRY_TOPIC, command.encode());
    }

    /** Advisory local validation followed by the normal authoritative submission path. */
    public AppChainClient.SubmitResult authenticatedMapCommand(
            AuthenticatedMapContract.Command command,
            AuthenticatedMapPreflight preflight) {
        Objects.requireNonNull(preflight, "preflight").requireAccepted(command);
        return authenticatedMapCommand(command);
    }

    public AppChainClient.SubmitResult authenticatedMapMutate(
            AuthenticatedMapContract.Mutation mutation) {
        return authenticatedMapCommand(AuthenticatedMapContract.Command.single(mutation));
    }

    public AppChainClient.SubmitResult authenticatedMapMutate(
            AuthenticatedMapContract.Mutation mutation,
            AuthenticatedMapPreflight preflight) {
        return authenticatedMapCommand(
                AuthenticatedMapContract.Command.single(mutation), preflight);
    }

    public AppChainClient.SubmitResult authenticatedMapBatch(
            List<AuthenticatedMapContract.Mutation> mutations) {
        return authenticatedMapCommand(AuthenticatedMapContract.Command.batch(mutations));
    }

    public AppChainClient.SubmitResult authenticatedMapBatch(
            List<AuthenticatedMapContract.Mutation> mutations,
            AuthenticatedMapPreflight preflight) {
        return authenticatedMapCommand(
                AuthenticatedMapContract.Command.batch(mutations), preflight);
    }

    /** Current root-attested logical entry DTO; use its presence to distinguish tombstone/absence. */
    public AuthenticatedMapContract.PointResult authenticatedMapEntry(
            String collectionId, byte[] applicationKey) {
        AuthenticatedMapContract.PointQuery request =
                AuthenticatedMapContract.PointQuery.current(collectionId, applicationKey);
        AppChainClient.QueryResult result = client.query(
                AuthenticatedMapContract.POINT_QUERY_PATH,
                AuthenticatedMapContract.encodePointQuery(request));
        requireAuthenticatedMapQuery(result);
        AuthenticatedMapContract.PointResult point =
                AuthenticatedMapContract.decodePointResult(result.payload());
        if (point.committedHeight() != result.committedHeight()
                || !Arrays.equals(point.stateRoot(), result.stateRoot())
                || !point.collectionId().equals(collectionId)
                || !Arrays.equals(point.applicationKey(), applicationKey)) {
            throw new AppChainClient.AppChainClientException(
                    "Authenticated-map point payload differs from query envelope/request");
        }
        return point;
    }

    /** Current retained receipt DTO for an accepted message id. */
    public AuthenticatedMapContract.ReceiptResult authenticatedMapReceipt(byte[] messageId) {
        AppChainClient.QueryResult result = client.query(
                AuthenticatedMapContract.RECEIPT_QUERY_PATH,
                AuthenticatedMapContract.encodeReceiptQuery(
                        new AuthenticatedMapContract.ReceiptQuery(messageId)));
        requireAuthenticatedMapQuery(result);
        AuthenticatedMapContract.ReceiptResult receipt =
                AuthenticatedMapContract.decodeReceiptResult(result.payload());
        if (receipt.committedHeight() != result.committedHeight()
                || !Arrays.equals(receipt.stateRoot(), result.stateRoot())
                || !Arrays.equals(receipt.messageId(), messageId)) {
            throw new AppChainClient.AppChainClientException(
                    "Authenticated-map receipt payload differs from query envelope/request");
        }
        return receipt;
    }

    /** Phase-1 MPF proof helper for the canonical collection/key leaf. */
    public Optional<VerifiedState<AuthenticatedMapContract.Entry>> authenticatedMapProof(
            String collectionId, byte[] applicationKey) {
        return verified(AuthenticatedMapContract.canonicalKey(collectionId, applicationKey),
                AuthenticatedMapContract::decodeEntry);
    }

    public Optional<AuthenticatedMapAuthorizationContract.DirectConsumptionV1>
    authenticatedMapDirectConsumption(String actorId, byte[] authorizationId) {
        byte[] params = new AuthenticatedMapAuthorizationContract.DirectConsumptionQueryV1(
                actorId, authorizationId).encode();
        AppChainClient.QueryResult result = client.query(
                AuthenticatedMapContract.DIRECT_CONSUMPTION_QUERY_PATH, params);
        requireAuthenticatedMapQuery(result);
        if (result.payload().length == 0) return Optional.empty();
        var consumption = AuthenticatedMapAuthorizationContract.DirectConsumptionV1
                .decode(result.payload());
        if (!actorId.equals(consumption.actorId())
                || !Arrays.equals(authorizationId, consumption.authorizationId())) {
            throw new AppChainClient.AppChainClientException(
                    "Authenticated-map direct consumption differs from query subject");
        }
        return Optional.of(consumption);
    }

    public Optional<AuthenticatedMapAuthorizationContract.ApprovalConsumptionV1>
    authenticatedMapApprovalConsumption(String proposalId) {
        AppChainClient.QueryResult result = client.query(
                AuthenticatedMapContract.APPROVAL_CONSUMPTION_QUERY_PATH,
                proposalId.getBytes(StandardCharsets.US_ASCII));
        requireAuthenticatedMapQuery(result);
        if (result.payload().length == 0) return Optional.empty();
        var consumption = AuthenticatedMapAuthorizationContract.ApprovalConsumptionV1
                .decode(result.payload());
        if (!proposalId.equals(consumption.proposalId())) {
            throw new AppChainClient.AppChainClientException(
                    "Authenticated-map approval consumption differs from query subject");
        }
        return Optional.of(consumption);
    }

    public AppChainClient.SubmitResult propose(
            String itemId, byte[] payload, int required, long deadlineMillis) {
        return client.submit(ApprovalsContract.DEFAULT_TOPIC,
                ApprovalsContract.propose(itemId, payload, required, deadlineMillis));
    }

    public AppChainClient.SubmitResult approve(String itemId) {
        return client.submit(ApprovalsContract.DEFAULT_TOPIC,
                ApprovalsContract.approve(itemId));
    }

    public AppChainClient.SubmitResult reject(String itemId) {
        return client.submit(ApprovalsContract.DEFAULT_TOPIC,
                ApprovalsContract.reject(itemId));
    }

    public Optional<VerifiedState<ApprovalsContract.Item>> approval(String itemId) {
        return verified(ApprovalsContract.itemKey(itemId), ApprovalsContract::decodeItem);
    }

    public Optional<VerifiedState<ApprovalsContract.EffectState>> approvalEffect(String itemId) {
        return verified(ApprovalsContract.effectStateKey(itemId),
                ApprovalsContract::decodeEffectState);
    }

    public AppChainClient.SubmitResult mint(String account, BigInteger amount) {
        return client.submit(BalancesContract.DEFAULT_TOPIC,
                BalancesContract.mint(account, amount));
    }

    public AppChainClient.SubmitResult transfer(String account, BigInteger amount) {
        return client.submit(BalancesContract.DEFAULT_TOPIC,
                BalancesContract.transfer(account, amount));
    }

    public Optional<VerifiedState<BigInteger>> balance(String account) {
        return verified(BalancesContract.accountKey(account), BalancesContract::decodeBalance);
    }

    public AppChainClient.SubmitResult appendDocument(
            String entityId, byte[] entryHash, String reference) {
        return client.submit(DocTrailContract.DEFAULT_TOPIC,
                DocTrailContract.append(entityId, entryHash, reference));
    }

    public Optional<VerifiedState<DocTrailContract.Head>> documentTrail(String entityId) {
        return verified(DocTrailContract.entityKey(entityId), DocTrailContract::decodeHead);
    }

    private <T> Optional<VerifiedState<T>> verified(byte[] key, Function<byte[], T> decoder) {
        Optional<AppChainClient.Proof> result = client.proof(key);
        if (result.isEmpty()) return Optional.empty();
        AppChainClient.Proof proof = result.orElseThrow();
        boolean verified = trustedRootResolver != null
                ? ProofVerifier.verify(proof,
                Objects.requireNonNull(trustedRootResolver.resolve(proof),
                        "trusted root resolver result"))
                : ProofVerifier.verifyInternalConsistency(proof);
        if (!verified) {
            throw new IllegalStateException(
                    trustedRootResolver != null
                            ? "Trusted state proof verification failed for a stock value"
                            : "State proof internal-consistency check failed for a stock value");
        }
        if (proof.valueHex() == null) return Optional.empty();
        return Optional.of(new VerifiedState<>(decoder.apply(Hex.decode(proof.valueHex())), proof));
    }

    private static void requireAuthenticatedMapQuery(AppChainClient.QueryResult result) {
        if (!AuthenticatedMapContract.STATE_MACHINE_ID.equals(result.stateMachineId())) {
            throw new AppChainClient.AppChainClientException(
                    "Query response was not produced by authenticated-map");
        }
    }

    /** Resolve the exact independently authenticated root for one proof version. */
    @FunctionalInterface
    public interface TrustedRootResolver {
        ProofVerifier.TrustedStateRoot resolve(AppChainClient.Proof proof);
    }

    /** A decoded state value bound to a locally checked proof envelope. */
    public record VerifiedState<T>(T value, AppChainClient.Proof proof) {
        public VerifiedState {
            Objects.requireNonNull(value, "value");
            Objects.requireNonNull(proof, "proof");
        }
    }
}
