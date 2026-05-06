package com.bloxbean.cardano.yano.app.api.governance.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

public class GovernanceDtos {

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ProposalDto(
            @JsonProperty("id") String id,
            @JsonProperty("tx_hash") String txHash,
            @JsonProperty("cert_index") int certIndex,
            @JsonProperty("governance_type") String governanceType,
            @JsonProperty("governance_description") Object governanceDescription,
            @JsonProperty("deposit") String deposit,
            @JsonProperty("return_address") String returnAddress,
            @JsonProperty("expiration") int expiration,
            @JsonProperty("ratified_epoch") Integer ratifiedEpoch,
            @JsonProperty("enacted_epoch") Integer enactedEpoch,
            @JsonProperty("dropped_epoch") Integer droppedEpoch,
            @JsonProperty("expired_epoch") Integer expiredEpoch,
            @JsonProperty("status") String status,
            @JsonProperty("proposed_epoch") int proposedEpoch,
            @JsonProperty("expires_after_epoch") int expiresAfterEpoch,
            @JsonProperty("proposal_slot") long proposalSlot,
            @JsonProperty("prev_action_tx_hash") String prevActionTxHash,
            @JsonProperty("prev_action_index") Integer prevActionIndex
    ) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ProposalVoteDto(
            @JsonProperty("proposal_tx_hash") String proposalTxHash,
            @JsonProperty("proposal_cert_index") int proposalCertIndex,
            @JsonProperty("voter_role") String voterRole,
            @JsonProperty("committee_voter") String committeeVoter,
            @JsonProperty("drep_voter") String drepVoter,
            @JsonProperty("pool_voter") String poolVoter,
            @JsonProperty("voter_type") String voterType,
            @JsonProperty("voter_hash") String voterHash,
            @JsonProperty("vote") String vote
    ) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record DRepListDto(
            @JsonProperty("drep_id") String drepId,
            @JsonProperty("hex") String hex
    ) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record DRepDto(
            @JsonProperty("drep_id") String drepId,
            @JsonProperty("hex") String hex,
            @JsonProperty("drep_type") String drepType,
            @JsonProperty("amount") String amount,
            @JsonProperty("active") boolean active,
            @JsonProperty("active_epoch") int activeEpoch,
            @JsonProperty("has_script") boolean hasScript,
            @JsonProperty("retired") boolean retired,
            @JsonProperty("expired") boolean expired,
            @JsonProperty("last_active_epoch") Integer lastActiveEpoch,
            @JsonProperty("registered_epoch") int registeredEpoch,
            @JsonProperty("expiry_epoch") int expiryEpoch,
            @JsonProperty("last_interaction_epoch") Integer lastInteractionEpoch,
            @JsonProperty("deposit") String deposit,
            @JsonProperty("anchor_url") String anchorUrl,
            @JsonProperty("anchor_hash") String anchorHash,
            @JsonProperty("registered_slot") long registeredSlot,
            @JsonProperty("protocol_version_at_registration") int protocolVersionAtRegistration,
            @JsonProperty("previous_deregistration_slot") Long previousDeregistrationSlot
    ) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record DRepDistributionDto(
            @JsonProperty("drep_id") String drepId,
            @JsonProperty("hex") String hex,
            @JsonProperty("drep_type") String drepType,
            @JsonProperty("epoch") int epoch,
            @JsonProperty("amount") String amount
    ) {}

    private GovernanceDtos() {}
}
