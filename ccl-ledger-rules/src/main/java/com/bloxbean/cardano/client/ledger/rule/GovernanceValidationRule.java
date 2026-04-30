package com.bloxbean.cardano.client.ledger.rule;

import com.bloxbean.cardano.client.address.Address;
import com.bloxbean.cardano.client.address.Credential;
import com.bloxbean.cardano.client.api.model.ProtocolParams;
import com.bloxbean.cardano.client.api.model.ValidationError;
import com.bloxbean.cardano.client.ledger.LedgerContext;
import com.bloxbean.cardano.client.ledger.slice.CommitteeSlice;
import com.bloxbean.cardano.client.ledger.slice.DRepsSlice;
import com.bloxbean.cardano.client.ledger.slice.PoolsSlice;
import com.bloxbean.cardano.client.ledger.slice.ProposalsSlice;
import com.bloxbean.cardano.client.spec.NetworkId;
import com.bloxbean.cardano.client.transaction.spec.Transaction;
import com.bloxbean.cardano.client.transaction.spec.TransactionBody;
import com.bloxbean.cardano.client.transaction.spec.Withdrawal;
import com.bloxbean.cardano.client.transaction.spec.governance.*;
import com.bloxbean.cardano.client.transaction.spec.governance.actions.*;
import com.bloxbean.cardano.client.transaction.util.TransactionUtil;
import com.bloxbean.cardano.client.util.HexUtil;

import com.bloxbean.cardano.client.ledger.slice.AccountsSlice;

import java.math.BigInteger;
import java.util.*;

/**
 * Category G: Governance Validation Rule.
 * <p>
 * Validates governance proposals and voting procedures: deposits, network consistency,
 * previous action references, treasury withdrawals, committee expiration, voter eligibility.
 * Stateful checks gracefully skip when the required state slice is null.
 */
public class GovernanceValidationRule implements LedgerRule {

    private static final String RULE_NAME = "GovernanceValidation";

    @Override
    public List<ValidationError> validate(LedgerContext context, Transaction transaction) {
        List<ValidationError> errors = new ArrayList<>();
        TransactionBody body = transaction.getBody();
        ProposalsSlice baseProposalsSlice = context.getProposalsSlice();
        ProposalsSlice votingProposalsSlice = withTransactionProposals(context, transaction);

        // G.A: Validate proposal procedures
        List<ProposalProcedure> proposals = body.getProposalProcedures();
        if (proposals != null && !proposals.isEmpty()) {
            for (int i = 0; i < proposals.size(); i++) {
                validateProposal(context, baseProposalsSlice, proposals.get(i), i, errors);
            }
        }

        // G.B: Validate voting procedures
        VotingProcedures votingProcedures = body.getVotingProcedures();
        if (votingProcedures != null && votingProcedures.getVoting() != null) {
            validateVoting(context, votingProposalsSlice, votingProcedures, errors);
        }

        return errors;
    }

    // ---- Proposal Validation ----

    private void validateProposal(LedgerContext context, ProposalsSlice proposalsSlice, ProposalProcedure proposal,
                                  int index, List<ValidationError> errors) {
        ProtocolParams pp = context.getProtocolParams();

        // G-1: deposit == pp.govActionDeposit
        if (pp != null && pp.getGovActionDeposit() != null && proposal.getDeposit() != null) {
            if (proposal.getDeposit().compareTo(pp.getGovActionDeposit()) != 0) {
                errors.add(error("Proposal[" + index + "]: deposit " + proposal.getDeposit()
                        + " does not match pp.govActionDeposit " + pp.getGovActionDeposit()));
            }
        }

        // G-2: reward account network matches context.networkId
        if (context.getNetworkId() != null && proposal.getRewardAccount() != null) {
            validateRewardAccountNetwork(proposal.getRewardAccount(), context, index, errors);
        }

        // ProposalReturnAccountDoesNotExist: reward account credential must be registered
        AccountsSlice accounts = context.getAccountsSlice();
        if (accounts != null && proposal.getRewardAccount() != null) {
            try {
                Address addr = new Address(proposal.getRewardAccount());
                byte[] delegationHash = addr.getDelegationCredentialHash().orElse(null);
                if (delegationHash != null) {
                    String hash = HexUtil.encodeHexString(delegationHash);
                    if (!accounts.isRegistered(hash)) {
                        errors.add(error("Proposal[" + index + "]: return account credential "
                                + hash + " is not registered"));
                    }
                }
            } catch (Exception e) {
                // Skip unparseable addresses
            }
        }

        // Action-specific validation
        GovAction action = proposal.getGovAction();
        if (action != null) {
            validateGovAction(context, proposalsSlice, action, index, errors);
        }
    }

    private void validateGovAction(LedgerContext context, ProposalsSlice proposals, GovAction action, int proposalIndex,
                                   List<ValidationError> errors) {
        // G-3 + G-4: TreasuryWithdrawalsAction
        if (action instanceof TreasuryWithdrawalsAction twa) {
            validateTreasuryWithdrawals(context, twa, proposalIndex, errors);
        }

        // G-5: UpdateCommittee member expiration > currentEpoch
        if (action instanceof UpdateCommittee uc) {
            validateUpdateCommittee(context, uc, proposalIndex, errors);
        }

        // G-6: prevGovActionId references exist
        GovActionId prevId = getPrevGovActionId(action);
        if (prevId != null) {
            if (proposals != null) {
                if (!proposals.exists(prevId.getTransactionId(), prevId.getGovActionIndex())) {
                    errors.add(error("Proposal[" + proposalIndex + "]: prevGovActionId "
                            + prevId.getTransactionId() + "#" + prevId.getGovActionIndex()
                            + " does not exist"));
                } else {
                    String prevActionType = proposals.getActionType(prevId.getTransactionId(), prevId.getGovActionIndex());
                    String expectedPurpose = purposeType(action);
                    String actualPurpose = purposeType(prevActionType);
                    if (expectedPurpose != null && !expectedPurpose.equals(actualPurpose)) {
                        errors.add(error("Proposal[" + proposalIndex + "]: prevGovActionId "
                                + prevId.getTransactionId() + "#" + prevId.getGovActionIndex()
                                + " has action type " + prevActionType
                                + " but expected same purpose " + expectedPurpose));
                    }
                }
            }
        }
    }

    private void validateTreasuryWithdrawals(LedgerContext context, TreasuryWithdrawalsAction twa,
                                             int proposalIndex, List<ValidationError> errors) {
        List<Withdrawal> withdrawals = twa.getWithdrawals();
        if (withdrawals == null || withdrawals.isEmpty()) return;

        int expectedNetworkInt = context.getNetworkId() != null
                ? (context.getNetworkId() == NetworkId.MAINNET ? 1 : 0)
                : -1;
        AccountsSlice accounts = context.getAccountsSlice();

        BigInteger totalSum = BigInteger.ZERO;

        for (int i = 0; i < withdrawals.size(); i++) {
            Withdrawal w = withdrawals.get(i);

            // G-4: amount > 0
            if (w.getCoin() != null && w.getCoin().signum() <= 0) {
                errors.add(error("Proposal[" + proposalIndex + "] TreasuryWithdrawal[" + i
                        + "]: amount must be > 0, got " + w.getCoin()));
            }

            if (w.getCoin() != null) {
                totalSum = totalSum.add(w.getCoin());
            }

            // G-3: network consistency
            if (expectedNetworkInt >= 0 && w.getRewardAddress() != null) {
                try {
                    Address addr = new Address(w.getRewardAddress());
                    if (addr.getNetwork() != null && addr.getNetwork().getNetworkId() != expectedNetworkInt) {
                        errors.add(error("Proposal[" + proposalIndex + "] TreasuryWithdrawal[" + i
                                + "]: address network " + addr.getNetwork().getNetworkId()
                                + " does not match expected " + expectedNetworkInt));
                    }
                } catch (Exception e) {
                    // Skip unparseable addresses
                }
            }

            // TreasuryWithdrawalReturnAccountsDoNotExist: destination account must exist
            if (accounts != null && w.getRewardAddress() != null) {
                try {
                    Address addr = new Address(w.getRewardAddress());
                    byte[] delegationHash = addr.getDelegationCredentialHash().orElse(null);
                    if (delegationHash != null) {
                        String hash = HexUtil.encodeHexString(delegationHash);
                        if (!accounts.isRegistered(hash)) {
                            errors.add(error("Proposal[" + proposalIndex + "] TreasuryWithdrawal[" + i
                                    + "]: destination account credential " + hash + " is not registered"));
                        }
                    }
                } catch (Exception e) {
                    // Skip unparseable addresses
                }
            }
        }

        // ZeroTreasuryWithdrawals (aggregate): total sum must be > 0
        if (totalSum.signum() <= 0) {
            errors.add(error("Proposal[" + proposalIndex
                    + "] TreasuryWithdrawals: aggregate withdrawal sum must be > 0, got " + totalSum));
        }
    }

    private void validateUpdateCommittee(LedgerContext context, UpdateCommittee uc,
                                         int proposalIndex, List<ValidationError> errors) {
        // G-5: new member expiration > currentEpoch
        long currentEpoch = context.getCurrentEpoch();
        if (currentEpoch >= 0 && uc.getNewMembersAndTerms() != null) {
            for (Map.Entry<Credential, Integer> entry : uc.getNewMembersAndTerms().entrySet()) {
                int expiration = entry.getValue();
                if (expiration <= currentEpoch) {
                    String credHashStr = credHash(entry.getKey());
                    errors.add(error("Proposal[" + proposalIndex + "] UpdateCommittee: member "
                            + credHashStr + " expiration epoch " + expiration
                            + " is not greater than currentEpoch " + currentEpoch));
                }
            }
        }

        // ConflictingCommitteeUpdate: membersForRemoval ∩ newMembersAndTerms.keys == ∅
        Set<Credential> toRemove = uc.getMembersForRemoval();
        Map<Credential, Integer> toAdd = uc.getNewMembersAndTerms();
        if (toRemove != null && !toRemove.isEmpty() && toAdd != null && !toAdd.isEmpty()) {
            for (Credential removeCred : toRemove) {
                if (toAdd.containsKey(removeCred)) {
                    errors.add(error("Proposal[" + proposalIndex
                            + "] UpdateCommittee: credential " + credHash(removeCred)
                            + " is in both membersForRemoval and newMembersAndTerms"));
                }
            }
        }
    }

    /**
     * Extract prevGovActionId from governance actions that reference a previous action.
     */
    private GovActionId getPrevGovActionId(GovAction action) {
        if (action instanceof ParameterChangeAction a) return a.getPrevGovActionId();
        if (action instanceof HardForkInitiationAction a) return a.getPrevGovActionId();
        if (action instanceof NoConfidence a) return a.getPrevGovActionId();
        if (action instanceof UpdateCommittee a) return a.getPrevGovActionId();
        if (action instanceof NewConstitution a) return a.getPrevGovActionId();
        return null;
    }

    // ---- Voting Validation ----

    private void validateVoting(LedgerContext context, ProposalsSlice proposals, VotingProcedures votingProcedures,
                                List<ValidationError> errors) {
        Map<Voter, Map<GovActionId, VotingProcedure>> voting = votingProcedures.getVoting();

        for (Map.Entry<Voter, Map<GovActionId, VotingProcedure>> entry : voting.entrySet()) {
            Voter voter = entry.getKey();

            // Validate voter eligibility
            validateVoterEligibility(context, voter, errors);

            // G-7: vote targets (GovActionId) must be active/votable
            if (proposals != null && entry.getValue() != null) {
                for (GovActionId actionId : entry.getValue().keySet()) {
                    if (!proposals.isActive(actionId.getTransactionId(), actionId.getGovActionIndex(),
                            context.getCurrentEpoch())) {
                        errors.add(error("Vote: target governance action "
                                + actionId.getTransactionId() + "#" + actionId.getGovActionIndex()
                                + " is not active"));
                    } else {
                        // DisallowedVoters: voter type must be permitted for this action type
                        validateVoterForActionType(voter, actionId, proposals, errors);
                    }
                }
            }
        }
    }

    private void validateVoterEligibility(LedgerContext context, Voter voter,
                                          List<ValidationError> errors) {
        if (voter == null || voter.getType() == null) return;
        String hash = credHash(voter.getCredential());
        if (hash == null) return;

        switch (voter.getType()) {
            // G-8: DRep voter IS registered
            case DREP_KEY_HASH:
            case DREP_SCRIPT_HASH: {
                DRepsSlice dreps = context.getDrepsSlice();
                if (dreps != null && !dreps.isRegistered(hash)) {
                    errors.add(error("Vote: DRep voter " + hash + " is not registered"));
                }
                break;
            }
            // G-9: Pool voter IS registered
            case STAKING_POOL_KEY_HASH: {
                PoolsSlice pools = context.getPoolsSlice();
                if (pools != null && !pools.isRegistered(hash)) {
                    errors.add(error("Vote: pool voter " + hash + " is not registered"));
                }
                break;
            }
            // G-10: Committee hot voter IS authorized
            case CONSTITUTIONAL_COMMITTEE_HOT_KEY_HASH:
            case CONSTITUTIONAL_COMMITTEE_HOT_SCRIPT_HASH: {
                CommitteeSlice committee = context.getCommitteeSlice();
                if (committee != null) {
                    int hotCredType = voter.getType() == VoterType.CONSTITUTIONAL_COMMITTEE_HOT_KEY_HASH ? 0 : 1;
                    Optional<Boolean> authorized = committee.isHotCredentialAuthorized(hotCredType, hash,
                            context.getCurrentEpoch());
                    if (authorized.isPresent() && !authorized.get()) {
                        errors.add(error("Vote: committee hot voter " + hash + " is not authorized"));
                    }
                }
                break;
            }
        }
    }

    private ProposalsSlice withTransactionProposals(LedgerContext context, Transaction transaction) {
        ProposalsSlice base = context.getProposalsSlice();
        if (transaction == null || transaction.getBody() == null
                || transaction.getBody().getProposalProcedures() == null
                || transaction.getBody().getProposalProcedures().isEmpty()) {
            return base;
        }

        Map<String, String> local = new HashMap<>();
        String txHash = context.getCurrentTransactionHash();
        if (txHash == null || txHash.isBlank()) {
            try {
                txHash = TransactionUtil.getTxHash(transaction);
            } catch (Exception e) {
                return base;
            }
        }
        if (txHash == null || txHash.isBlank()) {
            return base;
        }

        List<ProposalProcedure> procedures = transaction.getBody().getProposalProcedures();
        for (int i = 0; i < procedures.size(); i++) {
            GovAction action = procedures.get(i).getGovAction();
            if (action != null && action.getType() != null) {
                local.put(key(txHash, i), action.getType().name());
            }
        }
        if (local.isEmpty()) return base;

        return new ProposalsSlice() {
            @Override
            public boolean exists(String txHash, int index) {
                return local.containsKey(key(txHash, index)) || (base != null && base.exists(txHash, index));
            }

            @Override
            public boolean isActive(String txHash, int index) {
                return local.containsKey(key(txHash, index)) || (base != null && base.isActive(txHash, index));
            }

            @Override
            public boolean isActive(String txHash, int index, long currentEpoch) {
                return local.containsKey(key(txHash, index))
                        || (base != null && base.isActive(txHash, index, currentEpoch));
            }

            @Override
            public String getActionType(String txHash, int index) {
                String localType = local.get(key(txHash, index));
                return localType != null ? localType : (base != null ? base.getActionType(txHash, index) : null);
            }
        };
    }

    private String key(String txHash, int index) {
        return txHash + "#" + index;
    }

    /**
     * DisallowedVoters: Validates that a voter type is permitted to vote on the given action type.
     */
    private void validateVoterForActionType(Voter voter, GovActionId actionId,
                                            ProposalsSlice proposals, List<ValidationError> errors) {
        if (voter == null || voter.getType() == null) return;

        String actionType = proposals.getActionType(actionId.getTransactionId(), actionId.getGovActionIndex());
        if (actionType == null) return;

        boolean isCC = voter.getType() == VoterType.CONSTITUTIONAL_COMMITTEE_HOT_KEY_HASH
                || voter.getType() == VoterType.CONSTITUTIONAL_COMMITTEE_HOT_SCRIPT_HASH;
        boolean isDRep = voter.getType() == VoterType.DREP_KEY_HASH
                || voter.getType() == VoterType.DREP_SCRIPT_HASH;
        boolean isSPO = voter.getType() == VoterType.STAKING_POOL_KEY_HASH;

        boolean allowed = switch (actionType.toUpperCase()) {
            case "NO_CONFIDENCE", "NOCONFIDENCE" -> isDRep || isSPO;
            case "UPDATE_COMMITTEE", "UPDATECOMMITTEE" -> isDRep || isSPO;
            case "NEW_CONSTITUTION", "NEWCONSTITUTION" -> isCC || isDRep;
            case "HARD_FORK_INITIATION", "HARDFORKINITIATION", "HARD_FORK_INITIATION_ACTION" -> isCC || isDRep || isSPO;
            case "PARAMETER_CHANGE", "PARAMETERCHANGE", "PARAMETER_CHANGE_ACTION" -> isCC || isDRep;
            case "TREASURY_WITHDRAWAL", "TREASURYWITHDRAWAL", "TREASURY_WITHDRAWALS_ACTION" -> isCC || isDRep;
            case "INFO_ACTION", "INFOACTION", "INFO" -> isCC || isDRep || isSPO;
            default -> true; // Unknown action type — don't block
        };

        if (!allowed) {
            String voterTypeStr = isCC ? "CC" : (isDRep ? "DRep" : "SPO");
            errors.add(error("Vote: " + voterTypeStr + " voter is not allowed to vote on action type "
                    + actionType + " (" + actionId.getTransactionId() + "#" + actionId.getGovActionIndex() + ")"));
        }
    }

    // ---- Helper methods ----

    private void validateRewardAccountNetwork(String rewardAddress, LedgerContext context,
                                              int proposalIndex, List<ValidationError> errors) {
        try {
            Address addr = new Address(rewardAddress);
            int expectedNetworkInt = context.getNetworkId() == NetworkId.MAINNET ? 1 : 0;
            if (addr.getNetwork() != null && addr.getNetwork().getNetworkId() != expectedNetworkInt) {
                errors.add(error("Proposal[" + proposalIndex + "]: reward account network "
                        + addr.getNetwork().getNetworkId()
                        + " does not match expected " + expectedNetworkInt));
            }
        } catch (Exception e) {
            // Skip unparseable addresses
        }
    }

    private String credHash(Credential cred) {
        if (cred == null || cred.getBytes() == null) return null;
        return HexUtil.encodeHexString(cred.getBytes());
    }

    private String purposeType(GovAction action) {
        if (action instanceof TreasuryWithdrawalsAction || action instanceof InfoAction) return null;
        if (action instanceof NoConfidence || action instanceof UpdateCommittee) return "UPDATE_COMMITTEE";
        if (action instanceof ParameterChangeAction) return "PARAMETER_CHANGE_ACTION";
        if (action instanceof HardForkInitiationAction) return "HARD_FORK_INITIATION_ACTION";
        if (action instanceof NewConstitution) return "NEW_CONSTITUTION";
        return null;
    }

    private String purposeType(String actionType) {
        if (actionType == null) return null;
        return switch (actionType.toUpperCase()) {
            case "NO_CONFIDENCE", "NOCONFIDENCE", "UPDATE_COMMITTEE", "UPDATECOMMITTEE" -> "UPDATE_COMMITTEE";
            case "PARAMETER_CHANGE", "PARAMETERCHANGE", "PARAMETER_CHANGE_ACTION" -> "PARAMETER_CHANGE_ACTION";
            case "HARD_FORK_INITIATION", "HARDFORKINITIATION", "HARD_FORK_INITIATION_ACTION" -> "HARD_FORK_INITIATION_ACTION";
            case "NEW_CONSTITUTION", "NEWCONSTITUTION" -> "NEW_CONSTITUTION";
            default -> null;
        };
    }

    private ValidationError error(String message) {
        return ValidationError.builder()
                .rule(RULE_NAME)
                .message(message)
                .phase(ValidationError.Phase.PHASE_1)
                .build();
    }
}
