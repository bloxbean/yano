package com.bloxbean.cardano.yano.appchain.roles.contracts;

import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.ByteString;
import co.nstant.in.cbor.model.UnicodeString;
import co.nstant.in.cbor.model.UnsignedInteger;
import com.bloxbean.cardano.yano.appchain.roles.contracts.internal.RoleWorkflowCbor;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Canonical, referentially closed revision-1 business-governance genesis state. */
public record GovernedGenesisV1(
        String chainId,
        AdministratorAuthorityV1 administratorAuthority,
        List<OrganizationRecordV1> organizations,
        List<GenesisActorV1> actors,
        List<DirectRolePolicyV1> directPolicies,
        List<ApprovalPolicyV1> approvalPolicies,
        GovernedAuthorizationLimitsV1 limits
) {
    private static final int FIELD_COUNT = 8;

    public GovernedGenesisV1 {
        chainId = RoleWorkflowIdentifiers.chainId(chainId);
        if (administratorAuthority == null || administratorAuthority.revision() != 1
                || organizations == null || actors == null || directPolicies == null
                || approvalPolicies == null || limits == null) {
            throw OrganizationRecordV1.invalid();
        }

        organizations = new ArrayList<>(organizations);
        organizations.sort(Comparator.comparing(OrganizationRecordV1::organizationId));
        actors = new ArrayList<>(actors);
        actors.sort(Comparator.comparing(entry -> entry.actor().actorId()));
        directPolicies = new ArrayList<>(directPolicies);
        directPolicies.sort(Comparator.comparing(DirectRolePolicyV1::policyId));
        approvalPolicies = new ArrayList<>(approvalPolicies);
        approvalPolicies.sort(Comparator.comparing(ApprovalPolicyV1::policyId));

        checkCounts(organizations, actors, directPolicies, approvalPolicies, limits);
        Map<String, OrganizationRecordV1> organizationsById = organizationsById(organizations);
        Map<String, ActorRecordV1> actorsById = actorsById(chainId, actors, organizationsById);
        checkPolicies(directPolicies, approvalPolicies);
        checkAdministratorClosure(administratorAuthority, actorsById, organizationsById);

        organizations = List.copyOf(organizations);
        actors = List.copyOf(actors);
        directPolicies = List.copyOf(directPolicies);
        approvalPolicies = List.copyOf(approvalPolicies);
    }

    @Override public List<OrganizationRecordV1> organizations() {
        return List.copyOf(organizations);
    }
    @Override public List<GenesisActorV1> actors() { return List.copyOf(actors); }
    @Override public List<DirectRolePolicyV1> directPolicies() {
        return List.copyOf(directPolicies);
    }
    @Override public List<ApprovalPolicyV1> approvalPolicies() {
        return List.copyOf(approvalPolicies);
    }

    public DirectRolePolicyV1 directPolicy(String id) {
        return directPolicies.stream().filter(policy -> policy.policyId().equals(id))
                .findFirst().orElse(null);
    }

    public ApprovalPolicyV1 approvalPolicy(String id) {
        return approvalPolicies.stream().filter(policy -> policy.policyId().equals(id))
                .findFirst().orElse(null);
    }

    public byte[] encode() {
        Array organizationValues = bytesArray(organizations.stream()
                .map(OrganizationRecordV1::encode).toList());
        Array actorValues = bytesArray(actors.stream().map(GenesisActorV1::encode).toList());
        Array directValues = bytesArray(directPolicies.stream()
                .map(DirectRolePolicyV1::encode).toList());
        Array approvalValues = bytesArray(approvalPolicies.stream()
                .map(ApprovalPolicyV1::encode).toList());
        Array value = new Array();
        value.add(new UnsignedInteger(1));
        value.add(new UnicodeString(chainId));
        value.add(new ByteString(administratorAuthority.encode()));
        value.add(organizationValues);
        value.add(actorValues);
        value.add(directValues);
        value.add(approvalValues);
        value.add(new ByteString(limits.encode()));
        byte[] encoded = RoleWorkflowCbor.encode(value);
        if (encoded.length > limits.maximumGenesisRecordBytes()) {
            throw new RoleWorkflowException(RoleWorkflowResultCode.LIMIT_EXCEEDED);
        }
        return encoded;
    }

    public static GovernedGenesisV1 decode(byte[] bytes) {
        if (bytes == null || bytes.length == 0
                || bytes.length > RoleWorkflowLimits.MAX_GENESIS_RECORD_BYTES) {
            throw OrganizationRecordV1.invalid();
        }
        List<co.nstant.in.cbor.model.DataItem> values =
                RoleWorkflowCbor.decodeArray(bytes, FIELD_COUNT).getDataItems();
        OrganizationRecordV1.requireVersion(values.get(0));
        GovernedGenesisV1 decoded = new GovernedGenesisV1(
                RoleWorkflowCbor.text(values.get(1)),
                AdministratorAuthorityV1.decode(RoleWorkflowCbor.bytes(values.get(2))),
                decodeBytes(values.get(3), RoleWorkflowLimits.MAX_GENESIS_ORGANIZATIONS)
                        .stream().map(OrganizationRecordV1::decode).toList(),
                decodeBytes(values.get(4), RoleWorkflowLimits.MAX_GENESIS_ACTORS)
                        .stream().map(GenesisActorV1::decode).toList(),
                decodeBytes(values.get(5), RoleWorkflowLimits.MAX_GENESIS_POLICIES)
                        .stream().map(DirectRolePolicyV1::decode).toList(),
                decodeBytes(values.get(6), RoleWorkflowLimits.MAX_GENESIS_POLICIES)
                        .stream().map(ApprovalPolicyV1::decode).toList(),
                GovernedAuthorizationLimitsV1.decode(RoleWorkflowCbor.bytes(values.get(7))));
        RoleWorkflowCbor.requireCanonical(bytes, decoded.encode());
        return decoded;
    }

    private static void checkCounts(
            List<OrganizationRecordV1> organizations,
            List<GenesisActorV1> actors,
            List<DirectRolePolicyV1> directPolicies,
            List<ApprovalPolicyV1> approvalPolicies,
            GovernedAuthorizationLimitsV1 limits
    ) {
        int keys = actors.stream().mapToInt(entry -> entry.actor().keys().size()).sum();
        if (organizations.isEmpty() || actors.isEmpty()
                || organizations.size() > limits.maximumGenesisOrganizations()
                || actors.size() > limits.maximumGenesisActors()
                || keys > limits.maximumGenesisKeys()
                || directPolicies.size() + approvalPolicies.size()
                > limits.maximumGenesisPolicies()) {
            throw new RoleWorkflowException(RoleWorkflowResultCode.LIMIT_EXCEEDED);
        }
    }

    private static Map<String, OrganizationRecordV1> organizationsById(
            List<OrganizationRecordV1> organizations
    ) {
        Map<String, OrganizationRecordV1> records = new HashMap<>();
        for (OrganizationRecordV1 organization : organizations) {
            if (organization.revision() != 1
                    || records.put(organization.organizationId(), organization) != null) {
                throw OrganizationRecordV1.invalid();
            }
        }
        return records;
    }

    private static Map<String, ActorRecordV1> actorsById(
            String chainId,
            List<GenesisActorV1> actors,
            Map<String, OrganizationRecordV1> organizations
    ) {
        Map<String, ActorRecordV1> records = new HashMap<>();
        for (GenesisActorV1 genesisActor : actors) {
            ActorRecordV1 actor = genesisActor.actor();
            if (actor.revision() != 1 || !organizations.containsKey(actor.organizationId())
                    || records.put(actor.actorId(), actor) != null
                    || genesisActor.keyProofs().stream()
                    .anyMatch(proof -> !proof.chainId().equals(chainId))) {
                throw OrganizationRecordV1.invalid();
            }
        }
        return records;
    }

    private static void checkPolicies(
            List<DirectRolePolicyV1> directPolicies,
            List<ApprovalPolicyV1> approvalPolicies
    ) {
        Set<String> identifiers = new HashSet<>();
        for (DirectRolePolicyV1 policy : directPolicies) {
            if (policy.revision() != 1 || !identifiers.add(policy.policyId())) {
                throw OrganizationRecordV1.invalid();
            }
        }
        for (ApprovalPolicyV1 policy : approvalPolicies) {
            if (policy.revision() != 1 || !identifiers.add(policy.policyId())) {
                throw OrganizationRecordV1.invalid();
            }
        }
    }

    private static void checkAdministratorClosure(
            AdministratorAuthorityV1 authority,
            Map<String, ActorRecordV1> actors,
            Map<String, OrganizationRecordV1> organizations
    ) {
        for (String actorId : authority.administratorActorIds()) {
            ActorRecordV1 actor = actors.get(actorId);
            OrganizationRecordV1 organization = actor == null
                    ? null : organizations.get(actor.organizationId());
            if (actor == null || actor.status() != RecordStatus.ACTIVE
                    || organization == null || organization.status() != RecordStatus.ACTIVE
                    || actor.keys().stream().noneMatch(key -> key.activeAt(1))) {
                throw new RoleWorkflowException(RoleWorkflowResultCode.UNAUTHORIZED_ACTOR);
            }
        }
    }

    private static Array bytesArray(List<byte[]> values) {
        Array array = new Array();
        values.forEach(value -> array.add(new ByteString(value)));
        return array;
    }

    private static List<byte[]> decodeBytes(
            co.nstant.in.cbor.model.DataItem value,
            int maximum
    ) {
        return RoleWorkflowCbor.array(value, maximum).getDataItems().stream()
                .map(RoleWorkflowCbor::bytes)
                .toList();
    }
}
