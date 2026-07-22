package com.bloxbean.cardano.yano.appchain.evidence.profile;

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
import com.bloxbean.cardano.yano.appchain.evidence.profile.contracts.RoleEvidenceKeys;
import com.bloxbean.cardano.yano.appchain.roles.DomainActorRegistryComponent;
import com.bloxbean.cardano.yano.appchain.roles.RoleAwareApprovalsComponent;
import com.bloxbean.cardano.yano.appchain.roles.contracts.ActorRecordV1;
import com.bloxbean.cardano.yano.appchain.roles.contracts.ApprovalPolicyV1;
import com.bloxbean.cardano.yano.appchain.roles.contracts.ApprovalProposalV1;
import com.bloxbean.cardano.yano.appchain.roles.contracts.OrganizationRecordV1;
import com.bloxbean.cardano.yano.appchain.roles.contracts.RoleApprovalStatsV1;
import com.bloxbean.cardano.yano.appchain.roles.contracts.RoleWorkflowIdentifiers;
import com.bloxbean.cardano.yano.appchain.roles.contracts.RoleWorkflowKeys;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Stateless JSON projection over root-fixed component queries. */
public final class RoleEvidenceDomainApi implements DomainApi {
    private static final String ORGANIZATION = "get-organization";
    private static final String ACTOR = "get-actor";
    private static final String POLICY = "get-policy";
    private static final String PROPOSAL = "get-proposal";
    private static final String EVIDENCE_APPROVAL = "get-evidence-approval";
    private static final String STATS = "get-approval-stats";
    private static final Set<String> QUERY_PARAMETERS = Set.of("chain", "revision");
    private static final List<DomainApiRoute> ROUTES = List.of(
            route(ORGANIZATION, "organizations/{id}"),
            route(ACTOR, "actors/{id}"),
            route(POLICY, "policies/{id}"),
            route(PROPOSAL, "proposals/{id}"),
            route(EVIDENCE_APPROVAL,
                    "evidence/{evidence_id}/versions/{version}/approval"),
            route(STATS, "stats"));

    private final DomainApiContext context;

    RoleEvidenceDomainApi(DomainApiContext context) {
        this.context = Objects.requireNonNull(context, "context");
    }

    @Override public List<DomainApiRoute> routes() { return ROUTES; }

    @Override
    public DomainApiResponse handle(DomainApiRequest request) {
        if (request == null || request.method() != DomainHttpMethod.GET
                || ROUTES.stream().noneMatch(route -> route.routeId().equals(request.routeId()))
                || !QUERY_PARAMETERS.containsAll(request.queryParameters().keySet())) {
            throw invalid();
        }
        String id = "";
        if (!STATS.equals(request.routeId())) {
            id = EVIDENCE_APPROVAL.equals(request.routeId())
                    ? request.pathParameters().get("evidence_id")
                    : request.pathParameters().get("id");
            try {
                id = RoleWorkflowIdentifiers.id(id, EVIDENCE_APPROVAL.equals(request.routeId())
                        ? "evidenceId" : "id");
            } catch (RuntimeException invalidIdentifier) {
                throw invalid();
            }
        }
        String chain = chain(request.queryParameters());
        long revision = EVIDENCE_APPROVAL.equals(request.routeId())
                ? positive(request.pathParameters().get("version"))
                : revision(request.queryParameters());
        if ((PROPOSAL.equals(request.routeId()) || EVIDENCE_APPROVAL.equals(request.routeId())
                || STATS.equals(request.routeId()))
                && request.queryParameters().containsKey("revision")) throw invalid();
        String params = STATS.equals(request.routeId()) ? ""
                : id + (revision == 0 ? "" : "@" + revision);
        String path = switch (request.routeId()) {
            case ORGANIZATION -> "components/domain-actors/organization";
            case ACTOR -> "components/domain-actors/actor";
            case POLICY -> "components/role-approvals/policy";
            case PROPOSAL -> "components/role-approvals/proposal";
            case EVIDENCE_APPROVAL -> "components/role-approvals/evidence-approval";
            case STATS -> "components/role-approvals/stats";
            default -> throw invalid();
        };
        ResolvedQuery resolved = resolve(chain, request.routeId(), id, revision, path, params);
        AppQueryResult result = resolved.record();
        if (result.payload().length == 0) return json(404, "{\"error\":\"not-found\"}");
        try {
            String record = switch (request.routeId()) {
                case ORGANIZATION -> organization(OrganizationRecordV1.decode(result.payload()));
                case ACTOR -> actor(ActorRecordV1.decode(result.payload()));
                case POLICY -> policy(ApprovalPolicyV1.decode(result.payload()));
                case PROPOSAL -> proposal(ApprovalProposalV1.decode(result.payload()));
                case EVIDENCE_APPROVAL -> evidenceApproval(id, revision, result.payload());
                case STATS -> stats(RoleApprovalStatsV1.decode(result.payload()));
                default -> throw invalid();
            };
            return json(200, envelope(result, record,
                    proofKey(request.routeId(), id, revision, result.payload()),
                    resolved.currentPointer()));
        } catch (DomainApiException failure) {
            throw failure;
        } catch (RuntimeException corrupt) {
            throw new DomainApiException(DomainApiException.Code.FAILED,
                    "Role workflow query returned an invalid record", corrupt);
        }
    }

    private String chain(Map<String, List<String>> parameters) {
        List<String> available = context.queryService().chainIds();
        List<String> supplied = parameters.get("chain");
        if (supplied == null) {
            if (available.size() != 1) throw invalid();
            return available.getFirst();
        }
        if (supplied.size() != 1 || !available.contains(supplied.getFirst())) throw invalid();
        return supplied.getFirst();
    }

    private static long revision(Map<String, List<String>> parameters) {
        List<String> supplied = parameters.get("revision");
        if (supplied == null) return 0;
        if (supplied.size() != 1 || !supplied.getFirst().matches("[1-9][0-9]{0,18}")) {
            throw invalid();
        }
        try { return Long.parseLong(supplied.getFirst()); }
        catch (NumberFormatException invalid) { throw invalid(); }
    }

    private static long positive(String supplied) {
        if (supplied == null || !supplied.matches("[1-9][0-9]{0,18}")) throw invalid();
        try { return Long.parseLong(supplied); }
        catch (NumberFormatException invalid) { throw invalid(); }
    }

    private ResolvedQuery resolve(String chain, String route, String id, long revision,
                                  String recordPath, String recordParams) {
        if (revision != 0 || !isVersioned(route)) {
            return new ResolvedQuery(query(chain, recordPath, recordParams), null);
        }
        AppQueryResult pointer = query(chain, currentPath(route), id);
        if (pointer.payload().length == 0) return new ResolvedQuery(pointer, null);
        long currentRevision = pointer(pointer.payload());
        AppQueryResult record = query(chain, recordPath, id + "@" + currentRevision);
        if (record.payload().length == 0
                || recordRevision(route, record.payload()) != currentRevision
                || record.committedHeight() != pointer.committedHeight()
                || !MessageDigest.isEqual(record.stateRoot(), pointer.stateRoot())) {
            throw new DomainApiException(DomainApiException.Code.FAILED,
                    "Role workflow current record changed during proof resolution");
        }
        return new ResolvedQuery(record, new CurrentPointer(
                currentProofKey(route, id), pointer.payload()));
    }

    private static long recordRevision(String route, byte[] payload) {
        try {
            return switch (route) {
                case ORGANIZATION -> OrganizationRecordV1.decode(payload).revision();
                case ACTOR -> ActorRecordV1.decode(payload).revision();
                case POLICY -> ApprovalPolicyV1.decode(payload).revision();
                default -> throw invalid();
            };
        } catch (DomainApiException failure) {
            throw failure;
        } catch (RuntimeException corrupt) {
            throw new DomainApiException(DomainApiException.Code.FAILED,
                    "Role workflow current record is invalid", corrupt);
        }
    }

    private AppQueryResult query(String chain, String path, String params) {
        final AppQueryResult result;
        try {
            result = context.queryService().query(
                    chain, path, params.getBytes(StandardCharsets.US_ASCII));
        } catch (AppQueryException failure) {
            throw translate(failure);
        } catch (RuntimeException failure) {
            throw new DomainApiException(DomainApiException.Code.FAILED,
                    "Role workflow query failed", failure);
        }
        if (!chain.equals(result.chainId())
                || !RoleEvidenceStateMachineProvider.ID.equals(result.stateMachineId())) {
            throw new DomainApiException(DomainApiException.Code.FAILED,
                    "Role workflow query identity mismatch");
        }
        return result;
    }

    private static boolean isVersioned(String route) {
        return ORGANIZATION.equals(route) || ACTOR.equals(route) || POLICY.equals(route);
    }

    private static String currentPath(String route) {
        return switch (route) {
            case ORGANIZATION -> "components/domain-actors/organization-current";
            case ACTOR -> "components/domain-actors/actor-current";
            case POLICY -> "components/role-approvals/policy-current";
            default -> throw invalid();
        };
    }

    private static long pointer(byte[] value) {
        if (value.length != Long.BYTES) throw new DomainApiException(
                DomainApiException.Code.FAILED, "Role workflow current pointer is invalid");
        long revision = ByteBuffer.wrap(value).getLong();
        if (revision < 1) throw new DomainApiException(
                DomainApiException.Code.FAILED, "Role workflow current pointer is invalid");
        return revision;
    }

    private static byte[] currentProofKey(String route, String id) {
        byte[] local = switch (route) {
            case ORGANIZATION -> RoleWorkflowKeys.organizationCurrent(id);
            case ACTOR -> RoleWorkflowKeys.actorCurrent(id);
            case POLICY -> RoleWorkflowKeys.policyCurrent(id);
            default -> throw invalid();
        };
        return CompositeCommitmentV1.componentKey(
                ORGANIZATION.equals(route) || ACTOR.equals(route)
                        ? DomainActorRegistryComponent.COMPONENT_ID
                        : RoleAwareApprovalsComponent.COMPONENT_ID,
                local);
    }

    private static String envelope(AppQueryResult result, String record, byte[] proofKey,
                                   CurrentPointer currentPointer) {
        String current = currentPointer == null ? ""
                : ",\"currentPointerProofKey\":"
                + string(HexFormat.of().formatHex(currentPointer.proofKey()))
                + ",\"currentPointerValue\":"
                + string(HexFormat.of().formatHex(currentPointer.value()));
        return "{\"chainId\":" + string(result.chainId())
                + ",\"stateMachineId\":" + string(result.stateMachineId())
                + ",\"committedHeight\":" + result.committedHeight()
                + ",\"stateRoot\":" + string(HexFormat.of().formatHex(result.stateRoot()))
                + ",\"proofKey\":" + string(HexFormat.of().formatHex(proofKey))
                + ",\"recordValue\":"
                + string(HexFormat.of().formatHex(result.payload()))
                + current + ",\"record\":" + record + "}";
    }

    private static byte[] proofKey(String route, String id, long revision, byte[] payload) {
        byte[] local = switch (route) {
            case ORGANIZATION -> {
                OrganizationRecordV1 record = OrganizationRecordV1.decode(payload);
                yield RoleWorkflowKeys.organizationRevision(record.organizationId(),
                        record.revision());
            }
            case ACTOR -> {
                ActorRecordV1 record = ActorRecordV1.decode(payload);
                yield RoleWorkflowKeys.actorRevision(record.actorId(), record.revision());
            }
            case POLICY -> {
                ApprovalPolicyV1 record = ApprovalPolicyV1.decode(payload);
                yield RoleWorkflowKeys.policyRevision(record.policyId(), record.revision());
            }
            case PROPOSAL -> RoleWorkflowKeys.proposal(id);
            case EVIDENCE_APPROVAL -> RoleEvidenceKeys.evidenceApproval(id, revision);
            case STATS -> RoleWorkflowKeys.approvalStats();
            default -> throw invalid();
        };
        return CompositeCommitmentV1.componentKey(
                ORGANIZATION.equals(route) || ACTOR.equals(route)
                        ? DomainActorRegistryComponent.COMPONENT_ID
                        : RoleAwareApprovalsComponent.COMPONENT_ID,
                local);
    }

    private static String organization(OrganizationRecordV1 value) {
        return "{\"type\":\"organization\",\"organizationId\":"
                + string(value.organizationId()) + ",\"revision\":" + value.revision()
                + ",\"status\":" + string(value.status().name())
                + ",\"metadataCommitment\":"
                + string(HexFormat.of().formatHex(value.metadataCommitment())) + "}";
    }

    private static String actor(ActorRecordV1 value) {
        String roles = value.roles().stream().map(RoleEvidenceDomainApi::string)
                .collect(java.util.stream.Collectors.joining(",", "[", "]"));
        String keys = value.keys().stream().map(key -> "{\"keyId\":" + string(key.keyId())
                        + ",\"publicKey\":" + string(HexFormat.of().formatHex(key.publicKey()))
                        + ",\"validFromHeight\":" + key.validFromHeight()
                        + ",\"validUntilHeight\":" + key.validUntilHeight()
                        + ",\"status\":" + string(key.status().name()) + "}")
                .collect(java.util.stream.Collectors.joining(",", "[", "]"));
        return "{\"type\":\"actor\",\"actorId\":" + string(value.actorId())
                + ",\"organizationId\":" + string(value.organizationId())
                + ",\"revision\":" + value.revision()
                + ",\"status\":" + string(value.status().name())
                + ",\"roles\":" + roles + ",\"keys\":" + keys + "}";
    }

    private static String policy(ApprovalPolicyV1 value) {
        String proposers = value.proposerRoles().stream().map(RoleEvidenceDomainApi::string)
                .collect(java.util.stream.Collectors.joining(",", "[", "]"));
        String clauses = value.clauses().stream().map(clause -> "{\"clauseId\":"
                        + string(clause.clauseId()) + ",\"role\":" + string(clause.role())
                        + ",\"minimumCount\":" + clause.minimumCount()
                        + ",\"distinctBy\":" + string(clause.distinctBy().name()) + "}")
                .collect(java.util.stream.Collectors.joining(",", "[", "]"));
        return "{\"type\":\"policy\",\"policyId\":" + string(value.policyId())
                + ",\"revision\":" + value.revision()
                + ",\"proposerRoles\":" + proposers + ",\"clauses\":" + clauses
                + ",\"rejectionMode\":" + string(value.rejectionMode().name())
                + ",\"maximumLifetimeBlocks\":" + value.maximumLifetimeBlocks() + "}";
    }

    private static String proposal(ApprovalProposalV1 value) {
        String decisions = value.decisions().stream().map(decision -> "{\"decision\":"
                        + string(decision.action().name()) + ",\"actorId\":"
                        + string(decision.actorId()) + ",\"organizationId\":"
                        + string(decision.organizationId()) + ",\"organizationRevision\":"
                        + decision.organizationRevision() + ",\"role\":"
                        + string(decision.role()) + ",\"clauseId\":"
                        + string(decision.clauseId()) + ",\"actorRevision\":"
                        + decision.actorRevision() + ",\"keyId\":"
                        + string(decision.keyId()) + ",\"acceptedHeight\":"
                        + decision.acceptedHeight() + "}")
                .collect(java.util.stream.Collectors.joining(",", "[", "]"));
        return "{\"type\":\"proposal\",\"proposalId\":" + string(value.proposalId())
                + ",\"policyId\":" + string(value.policyId())
                + ",\"policyRevision\":" + value.policyRevision()
                + ",\"payloadDomain\":" + string(value.payloadDomain())
                + ",\"payloadHash\":" + string(HexFormat.of().formatHex(value.payloadHash()))
                + ",\"deadlineHeight\":" + value.deadlineHeight()
                + ",\"status\":" + string(value.status().name())
                + ",\"proposerActorId\":" + string(value.proposerActorId())
                + ",\"proposerOrganizationId\":" + string(value.proposerOrganizationId())
                + ",\"proposerRole\":" + string(value.proposerRole())
                + ",\"decisions\":" + decisions + "}";
    }

    private static String evidenceApproval(String evidenceId, long businessVersion,
                                           byte[] value) {
        String proposalId = new String(value, StandardCharsets.US_ASCII);
        try {
            RoleWorkflowIdentifiers.id(proposalId, "proposalId");
        } catch (RuntimeException invalid) {
            throw new DomainApiException(DomainApiException.Code.FAILED,
                    "Role workflow query returned an invalid record", invalid);
        }
        return "{\"type\":\"evidence-approval\",\"evidenceId\":"
                + string(evidenceId) + ",\"businessVersion\":" + businessVersion
                + ",\"proposalId\":" + string(proposalId) + "}";
    }

    private static String stats(RoleApprovalStatsV1 value) {
        return "{\"type\":\"approval-stats\",\"created\":" + value.created()
                + ",\"pending\":" + value.pending()
                + ",\"approved\":" + value.approved()
                + ",\"rejected\":" + value.rejected()
                + ",\"cancelled\":" + value.cancelled()
                + ",\"expired\":" + value.expired() + "}";
    }

    private static DomainApiRoute route(String id, String template) {
        return new DomainApiRoute(id, DomainHttpMethod.GET, template, DomainApiAccess.READ);
    }

    private static DomainApiException translate(AppQueryException failure) {
        return new DomainApiException(switch (failure.code()) {
            case INVALID_REQUEST, REQUEST_TOO_LARGE -> DomainApiException.Code.INVALID_REQUEST;
            case UNSUPPORTED -> DomainApiException.Code.NOT_FOUND;
            case BUSY -> DomainApiException.Code.BUSY;
            case TIMEOUT -> DomainApiException.Code.TIMEOUT;
            case RESULT_TOO_LARGE -> DomainApiException.Code.RESULT_TOO_LARGE;
            case UNAVAILABLE -> DomainApiException.Code.UNAVAILABLE;
            case FAILED -> DomainApiException.Code.FAILED;
        }, "Role workflow query failed", failure);
    }

    private static DomainApiException invalid() {
        return new DomainApiException(DomainApiException.Code.INVALID_REQUEST,
                "Invalid role workflow request");
    }

    private static DomainApiResponse json(int status, String body) {
        return new DomainApiResponse(status, DomainApiMediaType.JSON,
                body.getBytes(StandardCharsets.UTF_8));
    }

    private static String string(String value) {
        StringBuilder result = new StringBuilder(value.length() + 2).append('"');
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character == '"' || character == '\\') result.append('\\').append(character);
            else if (character >= 0x20 && character <= 0x7e) result.append(character);
            else result.append(String.format("\\u%04x", (int) character));
        }
        return result.append('"').toString();
    }

    private record ResolvedQuery(AppQueryResult record, CurrentPointer currentPointer) {
    }

    private record CurrentPointer(byte[] proofKey, byte[] value) {
        private CurrentPointer {
            proofKey = proofKey.clone();
            value = value.clone();
        }
        @Override public byte[] proofKey() { return proofKey.clone(); }
        @Override public byte[] value() { return value.clone(); }
    }
}
