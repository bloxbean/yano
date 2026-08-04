package com.bloxbean.cardano.yano.appchain.stdlib;

import com.bloxbean.cardano.yano.api.appchain.AppQueryException;
import com.bloxbean.cardano.yano.api.appchain.AppQueryResult;
import com.bloxbean.cardano.yano.api.plugin.domain.DomainApi;
import com.bloxbean.cardano.yano.api.plugin.domain.DomainApiAccess;
import com.bloxbean.cardano.yano.api.plugin.domain.DomainApiContext;
import com.bloxbean.cardano.yano.api.plugin.domain.DomainApiException;
import com.bloxbean.cardano.yano.api.plugin.domain.DomainApiMediaType;
import com.bloxbean.cardano.yano.api.plugin.domain.DomainApiRequest;
import com.bloxbean.cardano.yano.api.plugin.domain.DomainApiResponse;
import com.bloxbean.cardano.yano.api.plugin.domain.DomainApiRoute;
import com.bloxbean.cardano.yano.api.plugin.domain.DomainHttpMethod;
import com.bloxbean.cardano.yano.appchain.composite.contracts.CompositeCommitmentV1;
import com.bloxbean.cardano.yano.appchain.roles.DomainActorRegistryComponent;
import com.bloxbean.cardano.yano.appchain.roles.RoleApprovalsDomainApi;
import com.bloxbean.cardano.yano.appchain.roles.RoleAwareApprovalsComponent;
import com.bloxbean.cardano.yano.appchain.roles.contracts.AdministratorAuthorityV1;
import com.bloxbean.cardano.yano.appchain.roles.contracts.DirectRolePolicyV1;
import com.bloxbean.cardano.yano.appchain.roles.contracts.GovernedMutationRecordV1;
import com.bloxbean.cardano.yano.appchain.roles.contracts.RoleCommandResultV1;
import com.bloxbean.cardano.yano.appchain.roles.contracts.RolePendingQueriesV1;
import com.bloxbean.cardano.yano.appchain.roles.contracts.RoleWorkflowIdentifiers;
import com.bloxbean.cardano.yano.appchain.roles.contracts.RoleWorkflowKeys;
import com.bloxbean.cardano.yano.appchain.stdlib.contracts.AuthenticatedMapAuthorizationContract;
import com.bloxbean.cardano.yano.appchain.stdlib.contracts.AuthenticatedMapContract;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Root-fixed JSON projections over the authenticated-map composite query surface. */
public final class AuthenticatedMapDomainApi implements DomainApi {
    public static final String API_VERSION = "authenticated-map-domain-v1";

    private static final String METADATA = "authenticated-map-metadata";
    private static final String ENTRY = "authenticated-map-entry";
    private static final String RECEIPT = "authenticated-map-receipt";
    private static final String DIRECT_CONSUMPTION = "authenticated-map-direct-consumption";
    private static final String APPROVAL_CONSUMPTION = "authenticated-map-approval-consumption";
    private static final String DIRECT_POLICY = "authenticated-map-direct-policy";
    private static final String AUTHORITY = "authenticated-map-authority";
    private static final String ACTOR_GOVERNANCE = "authenticated-map-actor-governance";
    private static final String POLICY_GOVERNANCE = "authenticated-map-policy-governance";
    private static final String COMMAND_RESULT = "authenticated-map-command-result";
    private static final String PENDING_APPROVALS = "authenticated-map-pending-approvals";
    private static final String PENDING_ACTOR_GOVERNANCE =
            "authenticated-map-pending-actor-governance";
    private static final String PENDING_POLICY_GOVERNANCE =
            "authenticated-map-pending-policy-governance";
    private static final Set<String> QUERY_PARAMETERS =
            Set.of("chain", "revision", "after", "limit");
    private static final List<DomainApiRoute> OWN_ROUTES = List.of(
            route(METADATA, "authenticated-map"),
            route(ENTRY, "authenticated-map/entries/{collection}/{key}"),
            route(RECEIPT, "authenticated-map/receipts/{message_id}"),
            route(DIRECT_CONSUMPTION,
                    "authenticated-map/direct-consumptions/{actor}/{authorization_id}"),
            route(APPROVAL_CONSUMPTION,
                    "authenticated-map/approval-consumptions/{proposal}"),
            route(DIRECT_POLICY, "authenticated-map/direct-policies/{id}"),
            route(AUTHORITY, "authenticated-map/administrator-authorities/{id}"),
            route(ACTOR_GOVERNANCE, "authenticated-map/actor-governance/{id}"),
            route(POLICY_GOVERNANCE, "authenticated-map/policy-governance/{id}"),
            route(COMMAND_RESULT,
                    "authenticated-map/command-results/{component}/{message_id}"),
            route(PENDING_APPROVALS, "authenticated-map/pending/approvals"),
            route(PENDING_ACTOR_GOVERNANCE,
                    "authenticated-map/pending/actor-governance"),
            route(PENDING_POLICY_GOVERNANCE,
                    "authenticated-map/pending/policy-governance"));

    private final DomainApiContext context;
    private final RoleApprovalsDomainApi roles;
    private final List<DomainApiRoute> routes;

    public AuthenticatedMapDomainApi(DomainApiContext context) {
        this.context = Objects.requireNonNull(context, "context");
        this.roles = new RoleApprovalsDomainApi(
                context, AuthenticatedMapContract.STATE_MACHINE_ID);
        this.routes = java.util.stream.Stream.concat(
                roles.routes().stream(), OWN_ROUTES.stream()).toList();
    }

    @Override public List<DomainApiRoute> routes() { return routes; }

    @Override
    public DomainApiResponse handle(DomainApiRequest request) {
        if (request != null && RoleApprovalsDomainApi.supports(request.routeId())) {
            return roles.handle(request);
        }
        if (request == null || request.method() != DomainHttpMethod.GET
                || OWN_ROUTES.stream().noneMatch(route ->
                route.routeId().equals(request.routeId()))
                || !QUERY_PARAMETERS.containsAll(request.queryParameters().keySet())) {
            throw invalid();
        }
        String chain = chain(request.queryParameters());
        return switch (request.routeId()) {
            case METADATA -> metadata(chain, request);
            case ENTRY -> entry(chain, request);
            case RECEIPT -> receipt(chain, request);
            case DIRECT_CONSUMPTION -> directConsumption(chain, request);
            case APPROVAL_CONSUMPTION -> approvalConsumption(chain, request);
            case DIRECT_POLICY -> directPolicy(chain, request);
            case AUTHORITY -> authority(chain, request);
            case ACTOR_GOVERNANCE -> governance(chain, request, true);
            case POLICY_GOVERNANCE -> governance(chain, request, false);
            case COMMAND_RESULT -> commandResult(chain, request);
            case PENDING_APPROVALS -> pending(chain, request, PENDING_APPROVALS);
            case PENDING_ACTOR_GOVERNANCE -> pending(
                    chain, request, PENDING_ACTOR_GOVERNANCE);
            case PENDING_POLICY_GOVERNANCE -> pending(
                    chain, request, PENDING_POLICY_GOVERNANCE);
            default -> throw invalid();
        };
    }

    private DomainApiResponse metadata(String chain, DomainApiRequest request) {
        requireKeys(request, Set.of(), Set.of("chain"));
        AppQueryResult result = query(chain, AuthenticatedMapContract.CAPABILITIES_QUERY_PATH,
                new byte[0]);
        AuthenticatedMapContract.Genesis genesis = decode(
                () -> AuthenticatedMapContract.decodeGenesis(result.payload()));
        byte[] genesisId = AuthenticatedMapContract.genesisId(genesis);
        String collections = genesis.collections().stream().map(collection ->
                        "{\"id\":" + string(collection.id())
                                + ",\"authorization\":" + collection.authorization()
                                + ",\"authorizationPolicy\":"
                                + string(collection.authorizationPolicyId())
                                + ",\"restoreAllowed\":" + collection.restoreAllowed()
                                + ",\"maxKeyBytes\":" + collection.maxKeyBytes()
                                + ",\"maxValueBytes\":" + collection.maxValueBytes()
                                + ",\"valueEncoding\":" + collection.valueEncoding()
                                + ",\"validator\":" + string(collection.validatorId()) + "}")
                .collect(java.util.stream.Collectors.joining(",", "[", "]"));
        String record = "{\"type\":\"metadata\",\"apiVersion\":"
                + string(API_VERSION) + ",\"profile\":"
                + string(genesis.commitmentProfileId()) + ",\"genesisId\":"
                + string(hex(genesisId))
                + ",\"governed\":" + (genesis.governedGenesis() != null)
                + ",\"collections\":" + collections + "}";
        return response(result, record, mapProofKey(
                AuthenticatedMapContract.genesisMarkerKey()), genesisId, null);
    }

    private DomainApiResponse entry(String chain, DomainApiRequest request) {
        requireKeys(request, Set.of("collection", "key"), Set.of("chain"));
        String collection = request.pathParameters().get("collection");
        byte[] key = exactHex(request.pathParameters().get("key"), 1,
                AuthenticatedMapContract.MAX_APPLICATION_KEY_BYTES);
        AppQueryResult result = query(chain, AuthenticatedMapContract.POINT_QUERY_PATH,
                AuthenticatedMapContract.encodePointQuery(
                        AuthenticatedMapContract.PointQuery.current(collection, key)));
        AuthenticatedMapContract.PointResult point = decode(
                () -> AuthenticatedMapContract.decodePointResult(result.payload()));
        requireEnvelope(result, point.committedHeight(), point.stateRoot());
        byte[] value = point.entry() == null ? new byte[0]
                : AuthenticatedMapContract.encodeEntry(point.entry());
        String record = "{\"type\":\"entry\",\"collectionId\":"
                + string(point.collectionId()) + ",\"applicationKey\":"
                + string(hex(point.applicationKey())) + ",\"presence\":"
                + point.presence() + (point.entry() == null ? ""
                : ",\"revision\":" + point.entry().revision()
                + ",\"status\":" + point.entry().status()
                + ",\"logicalValueHash\":"
                + string(hex(point.entry().logicalValueHash()))) + "}";
        return response(result, record,
                mapProofKey(AuthenticatedMapContract.canonicalKey(collection, key)),
                value, null);
    }

    private DomainApiResponse receipt(String chain, DomainApiRequest request) {
        requireKeys(request, Set.of("message_id"), Set.of("chain"));
        byte[] messageId = exactHex(request.pathParameters().get("message_id"), 32, 32);
        AppQueryResult result = query(chain, AuthenticatedMapContract.RECEIPT_QUERY_PATH,
                AuthenticatedMapContract.encodeReceiptQuery(
                        new AuthenticatedMapContract.ReceiptQuery(messageId)));
        AuthenticatedMapContract.ReceiptResult value = decode(
                () -> AuthenticatedMapContract.decodeReceiptResult(result.payload()));
        requireEnvelope(result, value.committedHeight(), value.stateRoot());
        byte[] encoded = value.receipt() == null ? new byte[0]
                : AuthenticatedMapContract.encodeReceipt(value.receipt());
        String record = "{\"type\":\"receipt\",\"messageId\":"
                + string(hex(messageId)) + ",\"presence\":" + value.presence()
                + (value.receipt() == null ? "" : ",\"status\":"
                + value.receipt().status() + ",\"errorCode\":"
                + value.receipt().errorCode() + ",\"actionCommitment\":"
                + string(hex(value.receipt().batchCommitment()))) + "}";
        return response(result, record,
                mapProofKey(AuthenticatedMapContract.receiptKey(messageId)), encoded, null);
    }

    private DomainApiResponse directConsumption(String chain, DomainApiRequest request) {
        requireKeys(request, Set.of("actor", "authorization_id"), Set.of("chain"));
        String actor = id(request.pathParameters().get("actor"));
        byte[] authorization = exactHex(
                request.pathParameters().get("authorization_id"), 32, 32);
        var subject = new AuthenticatedMapAuthorizationContract.DirectConsumptionQueryV1(
                actor, authorization);
        AppQueryResult result = query(chain,
                AuthenticatedMapContract.DIRECT_CONSUMPTION_QUERY_PATH, subject.encode());
        if (result.payload().length == 0) return notFound();
        var value = decode(() -> AuthenticatedMapAuthorizationContract.DirectConsumptionV1
                .decode(result.payload()));
        String record = "{\"type\":\"direct-consumption\",\"actorId\":"
                + string(value.actorId()) + ",\"authorizationId\":"
                + string(hex(value.authorizationId())) + ",\"actionCommitment\":"
                + string(hex(value.actionCommitment())) + ",\"appliedHeight\":"
                + value.appliedHeight() + ",\"policyId\":"
                + string(value.policyId()) + ",\"policyRevision\":"
                + value.policyRevision() + "}";
        return response(result, record, mapProofKey(
                AuthenticatedMapContract.directConsumptionKey(actor, authorization)),
                result.payload(), null);
    }

    private DomainApiResponse approvalConsumption(String chain, DomainApiRequest request) {
        requireKeys(request, Set.of("proposal"), Set.of("chain"));
        String proposal = id(request.pathParameters().get("proposal"));
        AppQueryResult result = query(chain,
                AuthenticatedMapContract.APPROVAL_CONSUMPTION_QUERY_PATH,
                proposal.getBytes(StandardCharsets.US_ASCII));
        if (result.payload().length == 0) return notFound();
        var value = decode(() -> AuthenticatedMapAuthorizationContract.ApprovalConsumptionV1
                .decode(result.payload()));
        String record = "{\"type\":\"approval-consumption\",\"proposalId\":"
                + string(value.proposalId()) + ",\"actionCommitment\":"
                + string(hex(value.actionCommitment())) + ",\"appliedHeight\":"
                + value.appliedHeight() + ",\"policyId\":"
                + string(value.policyId()) + ",\"policyRevision\":"
                + value.policyRevision() + "}";
        return response(result, record, mapProofKey(
                AuthenticatedMapContract.approvalConsumptionKey(proposal)),
                result.payload(), null);
    }

    private DomainApiResponse directPolicy(String chain, DomainApiRequest request) {
        requireKeys(request, Set.of("id"), Set.of("chain", "revision"));
        String id = id(request.pathParameters().get("id"));
        long revision = revision(request.queryParameters());
        Resolved resolved = resolveCurrent(chain, RoleAwareApprovalsComponent.COMPONENT_ID,
                RoleAwareApprovalsComponent.QUERY_DIRECT_POLICY,
                RoleAwareApprovalsComponent.QUERY_DIRECT_POLICY_CURRENT, id, revision,
                RoleWorkflowKeys::directPolicyRevision,
                RoleWorkflowKeys::directPolicyCurrent);
        if (resolved.record().payload().length == 0) return notFound();
        DirectRolePolicyV1 value = decode(
                () -> DirectRolePolicyV1.decode(resolved.record().payload()));
        requireResolvedRevision(resolved, value.revision());
        String record = "{\"type\":\"direct-policy\",\"policyId\":"
                + string(value.policyId()) + ",\"revision\":" + value.revision()
                + ",\"status\":" + string(value.status().name())
                + ",\"requiredRole\":" + string(value.requiredRole())
                + ",\"maximumAuthorizationLifetimeBlocks\":"
                + value.maximumAuthorizationLifetimeBlocks() + "}";
        return response(resolved.record(), record, resolved.proofKey(),
                resolved.record().payload(), resolved.pointer());
    }

    private DomainApiResponse authority(String chain, DomainApiRequest request) {
        requireKeys(request, Set.of("id"), Set.of("chain", "revision"));
        String id = id(request.pathParameters().get("id"));
        long revision = revision(request.queryParameters());
        Resolved resolved = resolveCurrent(chain, DomainActorRegistryComponent.COMPONENT_ID,
                DomainActorRegistryComponent.QUERY_AUTHORITY,
                DomainActorRegistryComponent.QUERY_AUTHORITY_CURRENT, id, revision,
                RoleWorkflowKeys::authorityRevision, RoleWorkflowKeys::authorityCurrent);
        if (resolved.record().payload().length == 0) return notFound();
        AdministratorAuthorityV1 value = decode(
                () -> AdministratorAuthorityV1.decode(resolved.record().payload()));
        requireResolvedRevision(resolved, value.revision());
        String actors = value.administratorActorIds().stream()
                .map(AuthenticatedMapDomainApi::string)
                .collect(java.util.stream.Collectors.joining(",", "[", "]"));
        String record = "{\"type\":\"administrator-authority\",\"authorityId\":"
                + string(value.authorityId()) + ",\"revision\":" + value.revision()
                + ",\"administratorActors\":" + actors + ",\"threshold\":"
                + value.distinctActorThreshold() + "}";
        return response(resolved.record(), record, resolved.proofKey(),
                resolved.record().payload(), resolved.pointer());
    }

    private DomainApiResponse governance(
            String chain, DomainApiRequest request, boolean actorComponent) {
        requireKeys(request, Set.of("id"), Set.of("chain"));
        String id = id(request.pathParameters().get("id"));
        String component = actorComponent ? DomainActorRegistryComponent.COMPONENT_ID
                : RoleAwareApprovalsComponent.COMPONENT_ID;
        AppQueryResult result = componentQuery(chain, component,
                "governance-mutation", id.getBytes(StandardCharsets.US_ASCII));
        if (result.payload().length == 0) return notFound();
        GovernedMutationRecordV1 value = decode(
                () -> GovernedMutationRecordV1.decode(result.payload()));
        String record = "{\"type\":\"governance-mutation\",\"mutationId\":"
                + string(value.mutationId()) + ",\"status\":"
                + string(value.status().name()) + ",\"authorityId\":"
                + string(value.authorityId()) + ",\"authorityRevision\":"
                + value.authorityRevision() + ",\"expiryHeight\":"
                + value.expiryHeight() + "}";
        return response(result, record, componentProofKey(component,
                RoleWorkflowKeys.governedMutation(id)), result.payload(), null);
    }

    private DomainApiResponse commandResult(String chain, DomainApiRequest request) {
        requireKeys(request, Set.of("component", "message_id"), Set.of("chain"));
        String component = request.pathParameters().get("component");
        if (!Set.of(DomainActorRegistryComponent.COMPONENT_ID,
                RoleAwareApprovalsComponent.COMPONENT_ID).contains(component)) throw invalid();
        byte[] messageId = exactHex(request.pathParameters().get("message_id"), 32, 32);
        AppQueryResult result = componentQuery(chain, component,
                "command-result", messageId);
        if (result.payload().length == 0) return notFound();
        RoleCommandResultV1 value = decode(
                () -> RoleCommandResultV1.decode(result.payload()));
        String record = "{\"type\":\"command-result\",\"commandKind\":"
                + value.commandKind() + ",\"subjectId\":" + string(value.subjectId())
                + ",\"resultCode\":" + string(value.resultCode().name())
                + ",\"appliedHeight\":" + value.appliedHeight() + "}";
        return response(result, record, componentProofKey(component,
                RoleWorkflowKeys.commandResult(messageId)), result.payload(), null);
    }

    private DomainApiResponse pending(
            String chain, DomainApiRequest request, String kind) {
        requireKeys(request, Set.of(), Set.of("chain", "after", "limit"));
        String after = single(request.queryParameters(), "after", "");
        int limit;
        try { limit = Integer.parseInt(single(request.queryParameters(), "limit", "50")); }
        catch (NumberFormatException malformed) { throw invalid(); }
        RolePendingQueriesV1.PageQuery page;
        try {
            page = new RolePendingQueriesV1.PageQuery(after, limit);
        } catch (RuntimeException malformed) {
            throw invalid();
        }
        String component = PENDING_ACTOR_GOVERNANCE.equals(kind)
                ? DomainActorRegistryComponent.COMPONENT_ID
                : RoleAwareApprovalsComponent.COMPONENT_ID;
        String path = PENDING_APPROVALS.equals(kind) ? "pending-approvals"
                : "pending-governance";
        AppQueryResult result = componentQuery(chain, component, path, page.encode());
        String record;
        byte[] proofKey;
        if (PENDING_APPROVALS.equals(kind)) {
            RolePendingQueriesV1.ApprovalPage value = decode(
                    () -> RolePendingQueriesV1.ApprovalPage.decode(result.payload()));
            String ids = value.entries().stream().map(entry -> string(entry.proposalId()))
                    .collect(java.util.stream.Collectors.joining(",", "[", "]"));
            record = "{\"type\":\"pending-approvals\",\"ids\":" + ids
                    + ",\"nextAfterId\":" + string(value.nextAfterId()) + "}";
            proofKey = componentProofKey(component, RoleWorkflowKeys.approvalPendingIndex());
        } else {
            RolePendingQueriesV1.GovernancePage value = decode(
                    () -> RolePendingQueriesV1.GovernancePage.decode(result.payload()));
            String ids = value.entries().stream().map(entry -> string(entry.mutationId()))
                    .collect(java.util.stream.Collectors.joining(",", "[", "]"));
            record = "{\"type\":\"pending-governance\",\"ids\":" + ids
                    + ",\"nextAfterId\":" + string(value.nextAfterId()) + "}";
            proofKey = componentProofKey(component, RoleWorkflowKeys.governancePendingIndex());
        }
        return derivedResponse(result, record, proofKey, result.payload());
    }

    private Resolved resolveCurrent(
            String chain, String component, String recordPath, String pointerPath,
            String id, long requested,
            java.util.function.BiFunction<String, Long, byte[]> revisionKey,
            java.util.function.Function<String, byte[]> pointerKey) {
        if (requested > 0) {
            AppQueryResult record = componentQuery(chain, component, recordPath,
                    (id + "@" + requested).getBytes(StandardCharsets.US_ASCII));
            return new Resolved(record, componentProofKey(component,
                    revisionKey.apply(id, requested)), null);
        }
        AppQueryResult pointer = componentQuery(chain, component, pointerPath,
                id.getBytes(StandardCharsets.US_ASCII));
        if (pointer.payload().length == 0) {
            return new Resolved(pointer, componentProofKey(component, pointerKey.apply(id)), null);
        }
        if (pointer.payload().length != Long.BYTES) throw corrupt();
        long revision = ByteBuffer.wrap(pointer.payload()).getLong();
        if (revision < 1) throw corrupt();
        AppQueryResult record = componentQuery(chain, component, recordPath,
                (id + "@" + revision).getBytes(StandardCharsets.US_ASCII));
        if (record.committedHeight() != pointer.committedHeight()
                || !MessageDigest.isEqual(record.stateRoot(), pointer.stateRoot())) throw corrupt();
        return new Resolved(record, componentProofKey(component,
                revisionKey.apply(id, revision)), new Pointer(
                componentProofKey(component, pointerKey.apply(id)), pointer.payload()));
    }

    private AppQueryResult query(String chain, String path, byte[] params) {
        return checkedQuery(chain, path, params);
    }

    private AppQueryResult componentQuery(
            String chain, String component, String path, byte[] params) {
        return checkedQuery(chain, "components/" + component + "/" + path, params);
    }

    private AppQueryResult checkedQuery(String chain, String path, byte[] params) {
        try {
            AppQueryResult result = context.queryService().query(chain, path, params);
            if (!chain.equals(result.chainId())
                    || !AuthenticatedMapContract.STATE_MACHINE_ID.equals(
                    result.stateMachineId())) throw corrupt();
            return result;
        } catch (DomainApiException failure) {
            throw failure;
        } catch (AppQueryException failure) {
            throw new DomainApiException(switch (failure.code()) {
                case INVALID_REQUEST, REQUEST_TOO_LARGE ->
                        DomainApiException.Code.INVALID_REQUEST;
                case UNSUPPORTED -> DomainApiException.Code.NOT_FOUND;
                case BUSY -> DomainApiException.Code.BUSY;
                case TIMEOUT -> DomainApiException.Code.TIMEOUT;
                case RESULT_TOO_LARGE -> DomainApiException.Code.RESULT_TOO_LARGE;
                case UNAVAILABLE -> DomainApiException.Code.UNAVAILABLE;
                case FAILED -> DomainApiException.Code.FAILED;
            }, "Authenticated-map query failed", failure);
        } catch (RuntimeException failure) {
            throw new DomainApiException(DomainApiException.Code.FAILED,
                    "Authenticated-map query failed", failure);
        }
    }

    private String chain(Map<String, List<String>> parameters) {
        List<String> available = context.queryService().chainIds();
        String supplied = single(parameters, "chain", null);
        if (supplied == null) {
            if (available.size() != 1) throw invalid();
            return available.getFirst();
        }
        if (!available.contains(supplied)) throw invalid();
        return supplied;
    }

    private static long revision(Map<String, List<String>> parameters) {
        String value = single(parameters, "revision", "0");
        if (!value.matches("0|[1-9][0-9]{0,18}")) throw invalid();
        try { return Long.parseLong(value); }
        catch (NumberFormatException malformed) { throw invalid(); }
    }

    private static String single(
            Map<String, List<String>> parameters, String name, String fallback) {
        List<String> values = parameters.get(name);
        if (values == null) return fallback;
        if (values.size() != 1 || values.getFirst() == null) throw invalid();
        return values.getFirst();
    }

    private static void requireKeys(
            DomainApiRequest request, Set<String> pathKeys, Set<String> queryKeys) {
        if (!request.pathParameters().keySet().equals(pathKeys)
                || !queryKeys.containsAll(request.queryParameters().keySet())) throw invalid();
    }

    private static String id(String value) {
        try { return RoleWorkflowIdentifiers.id(value, "id"); }
        catch (RuntimeException malformed) { throw invalid(); }
    }

    private static byte[] exactHex(String value, int minimum, int maximum) {
        try {
            if (value == null || !value.matches("(?:[0-9a-f]{2})+")) throw invalid();
            byte[] decoded = HexFormat.of().parseHex(value);
            if (decoded.length < minimum || decoded.length > maximum) throw invalid();
            return decoded;
        } catch (DomainApiException failure) {
            throw failure;
        } catch (RuntimeException malformed) {
            throw invalid();
        }
    }

    private static void requireEnvelope(
            AppQueryResult result, long height, byte[] root) {
        if (height != result.committedHeight()
                || !MessageDigest.isEqual(root, result.stateRoot())) throw corrupt();
    }

    private static void requireResolvedRevision(Resolved resolved, long revision) {
        if (resolved.pointer() != null
                && (resolved.pointer().value().length != Long.BYTES
                || ByteBuffer.wrap(resolved.pointer().value()).getLong() != revision)) {
            throw corrupt();
        }
    }

    private static byte[] mapProofKey(byte[] local) {
        return componentProofKey(AuthenticatedMapComponent.COMPONENT_ID, local);
    }

    private static byte[] componentProofKey(String component, byte[] local) {
        return CompositeCommitmentV1.componentKey(component, local);
    }

    private static DomainApiResponse response(
            AppQueryResult result, String record, byte[] proofKey, byte[] recordValue,
            Pointer pointer) {
        return response(result, record, proofKey, recordValue, pointer, "authenticated-record");
    }

    private static DomainApiResponse response(
            AppQueryResult result, String record, byte[] proofKey, byte[] recordValue,
            Pointer pointer, String verificationLevel) {
        String current = pointer == null ? "" : ",\"currentPointerProofKey\":"
                + string(hex(pointer.proofKey())) + ",\"currentPointerValue\":"
                + string(hex(pointer.value()));
        String body = "{\"apiVersion\":" + string(API_VERSION)
                + ",\"chainId\":" + string(result.chainId())
                + ",\"stateMachineId\":" + string(result.stateMachineId())
                + ",\"committedHeight\":" + result.committedHeight()
                + ",\"stateRoot\":" + string(hex(result.stateRoot()))
                + ",\"proofKey\":" + string(hex(proofKey))
                + ",\"recordValue\":" + string(hex(recordValue))
                + ",\"verificationLevel\":" + string(verificationLevel)
                + current + ",\"record\":" + record + "}";
        return json(200, body);
    }

    private static DomainApiResponse derivedResponse(
            AppQueryResult result, String record, byte[] sourceIndexProofKey,
            byte[] queryValue) {
        String body = "{\"apiVersion\":" + string(API_VERSION)
                + ",\"chainId\":" + string(result.chainId())
                + ",\"stateMachineId\":" + string(result.stateMachineId())
                + ",\"committedHeight\":" + result.committedHeight()
                + ",\"stateRoot\":" + string(hex(result.stateRoot()))
                + ",\"sourceIndexProofKey\":" + string(hex(sourceIndexProofKey))
                + ",\"queryValue\":" + string(hex(queryValue))
                + ",\"verificationLevel\":"
                + string("DERIVED_FROM_PENDING_INDEX")
                + ",\"record\":" + record + "}";
        return json(200, body);
    }

    private static DomainApiResponse notFound() {
        return json(404, "{\"error\":\"not-found\"}");
    }

    private static DomainApiResponse json(int status, String body) {
        return new DomainApiResponse(status, DomainApiMediaType.JSON,
                body.getBytes(StandardCharsets.UTF_8));
    }

    private static DomainApiRoute route(String id, String template) {
        return new DomainApiRoute(id, DomainHttpMethod.GET, template, DomainApiAccess.READ);
    }

    private static String hex(byte[] value) { return HexFormat.of().formatHex(value); }

    private static String string(String value) {
        String safe = value == null ? "" : value;
        StringBuilder result = new StringBuilder(safe.length() + 2).append('"');
        for (int index = 0; index < safe.length(); index++) {
            char character = safe.charAt(index);
            if (character == '"' || character == '\\') result.append('\\').append(character);
            else if (character >= 0x20 && character <= 0x7e) result.append(character);
            else result.append(String.format("\\u%04x", (int) character));
        }
        return result.append('"').toString();
    }

    private static <T> T decode(java.util.function.Supplier<T> decoder) {
        try { return decoder.get(); }
        catch (RuntimeException malformed) { throw corrupt(); }
    }

    private static DomainApiException invalid() {
        return new DomainApiException(DomainApiException.Code.INVALID_REQUEST,
                "Invalid authenticated-map domain request");
    }

    private static DomainApiException corrupt() {
        return new DomainApiException(DomainApiException.Code.FAILED,
                "Authenticated-map query returned an invalid or mixed-root record");
    }

    private record Pointer(byte[] proofKey, byte[] value) {
        private Pointer {
            proofKey = proofKey.clone();
            value = value.clone();
        }
        @Override public byte[] proofKey() { return proofKey.clone(); }
        @Override public byte[] value() { return value.clone(); }
    }

    private record Resolved(AppQueryResult record, byte[] proofKey, Pointer pointer) {
        private Resolved { proofKey = proofKey.clone(); }
        @Override public byte[] proofKey() { return proofKey.clone(); }
    }
}
