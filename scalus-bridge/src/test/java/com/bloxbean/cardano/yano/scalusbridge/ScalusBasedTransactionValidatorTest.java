package com.bloxbean.cardano.yano.scalusbridge;

import com.bloxbean.cardano.client.api.model.ProtocolParams;
import com.bloxbean.cardano.client.api.model.Utxo;
import com.bloxbean.cardano.client.address.Credential;
import com.bloxbean.cardano.client.common.model.SlotConfig;
import com.bloxbean.cardano.client.transaction.spec.Transaction;
import com.bloxbean.cardano.client.transaction.spec.TransactionBody;
import com.bloxbean.cardano.client.transaction.spec.ProtocolParamUpdate;
import com.bloxbean.cardano.client.transaction.spec.cert.PoolRetirement;
import com.bloxbean.cardano.client.transaction.spec.governance.ProposalProcedure;
import com.bloxbean.cardano.client.transaction.spec.governance.Vote;
import com.bloxbean.cardano.client.transaction.spec.governance.Voter;
import com.bloxbean.cardano.client.transaction.spec.governance.VoterType;
import com.bloxbean.cardano.client.transaction.spec.governance.VotingProcedure;
import com.bloxbean.cardano.client.transaction.spec.governance.VotingProcedures;
import com.bloxbean.cardano.client.transaction.spec.governance.actions.GovActionId;
import com.bloxbean.cardano.client.transaction.spec.governance.actions.ParameterChangeAction;
import com.bloxbean.cardano.yano.api.account.LedgerStateProvider;
import com.bloxbean.cardano.yano.ledgerrules.ValidationError;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.LongSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScalusBasedTransactionValidatorTest {

    @Test
    void supplementaryRuleExceptionRejectsTransaction() {
        LedgerStateProvider provider = new MinimalLedgerStateProvider();
        var validator = new ScalusBasedTransactionValidator(
                new ProtocolParams(), null, new SlotConfig(1000, 0, 0), 0, provider);
        var tx = Transaction.builder().body(null).build();

        var result = validator.runSupplementaryRules(tx, 0, new ProtocolParams());

        assertFalse(result.valid());
        assertEquals("SupplementaryRuleException", result.errors().get(0).rule());
        assertEquals(ValidationError.Phase.PHASE_1, result.errors().get(0).phase());
        assertTrue(result.errors().get(0).message().contains("CCL supplementary rule validation failed"));
    }

    @Test
    void validateRejectsWhenSupplementaryRulesThrowAfterScalusSuccess() {
        var validator = new ScalusSuccessValidator(new MinimalLedgerStateProvider());

        var result = validator.validate(new byte[]{1, 2, 3}, Set.of());

        assertFalse(result.valid());
        assertEquals("SupplementaryRuleException", result.errors().get(0).rule());
        assertEquals(ValidationError.Phase.PHASE_1, result.errors().get(0).phase());
        assertTrue(result.errors().get(0).message().contains("CCL supplementary rule validation failed"));
    }

    @Test
    void validateRejectsWhenRuntimeSlotCannotBeResolved() {
        var validator = new SlotUnavailableValidator(new MinimalLedgerStateProvider());

        var result = validator.validate(new byte[]{1, 2, 3}, Set.of());

        assertFalse(result.valid());
        assertEquals("InternalError", result.errors().get(0).rule());
        assertEquals(ValidationError.Phase.PHASE_1, result.errors().get(0).phase());
        assertTrue(result.errors().get(0).message().contains("Failed to resolve current slot from runtime"));
    }

    @Test
    void runtimeValidatorRejectsWhenLedgerStateProviderIsUnavailable() {
        var validator = new ScalusSuccessValidator(null);

        var result = validator.validate(new byte[]{1, 2, 3}, Set.of());

        assertFalse(result.valid());
        assertEquals("LedgerStateUnavailable", result.errors().get(0).rule());
        assertEquals(ValidationError.Phase.PHASE_1, result.errors().get(0).phase());
        assertTrue(result.errors().get(0).message().contains("Ledger state provider is required"));
    }

    @Test
    void supplementaryRulesUseResolvedCurrentEpochForEpochDependentChecks() {
        ProtocolParams pp = new ProtocolParams();
        pp.setEMax(5);

        var validator = new ScalusBasedTransactionValidator(
                slot -> pp, null, new SlotConfig(1000, 0, 0), 0,
                new RegisteredPoolLedgerStateProvider(), () -> 12_345L, slot -> 10);

        var result = validator.runSupplementaryRules(poolRetirementTx(10), 12_345L, pp);

        assertFalse(result.valid());
        assertEquals("CertificateValidation", result.errors().get(0).rule());
        assertTrue(result.errors().get(0).message().contains("not in valid range [11, 15]"));
    }

    @Test
    void supplementaryRulesTreatEpochZeroAsKnownEpoch() {
        ProtocolParams pp = new ProtocolParams();
        pp.setEMax(5);
        var validator = new ScalusBasedTransactionValidator(
                slot -> pp, null, new SlotConfig(1000, 0, 0), 0,
                new RegisteredPoolLedgerStateProvider(), () -> 0L, slot -> 0);

        var result = validator.runSupplementaryRules(poolRetirementTx(0), 0, pp);

        assertFalse(result.valid());
        assertEquals("CertificateValidation", result.errors().get(0).rule());
        assertTrue(result.errors().get(0).message().contains("not in valid range [1, 5]"));
    }

    @Test
    void supplementaryRulesRejectWhenRuntimeEpochCannotBeResolved() {
        ProtocolParams pp = new ProtocolParams();
        pp.setEMax(5);
        var validator = new ScalusBasedTransactionValidator(
                slot -> pp, null, new SlotConfig(1000, 0, 0), 0,
                new RegisteredPoolLedgerStateProvider(), () -> 12_345L, slot -> null);

        var result = validator.runSupplementaryRules(poolRetirementTx(11), 12_345L, pp);

        assertFalse(result.valid());
        assertEquals("SupplementaryRuleException", result.errors().get(0).rule());
        assertEquals(ValidationError.Phase.PHASE_1, result.errors().get(0).phase());
        assertTrue(result.errors().get(0).message().contains("Failed to resolve current epoch"));
    }

    @Test
    void supplementaryRulesValidateGovernanceVoteTargetsAndDisallowedVoters() {
        var validator = new ScalusBasedTransactionValidator(
                new ProtocolParams(), null, new SlotConfig(1000, 0, 0), 0,
                new GovernanceProposalLedgerStateProvider("PARAMETER_CHANGE_ACTION", true, true));

        var result = validator.runSupplementaryRules(votingTx(VoterType.STAKING_POOL_KEY_HASH), 0, new ProtocolParams());

        assertFalse(result.valid());
        assertEquals("GovernanceValidation", result.errors().get(0).rule());
        assertTrue(result.errors().get(0).message().contains("SPO voter is not allowed"));
    }

    @Test
    void supplementaryRulesRejectVotingForInactiveGovernanceAction() {
        var validator = new ScalusBasedTransactionValidator(
                new ProtocolParams(), null, new SlotConfig(1000, 0, 0), 0,
                new GovernanceProposalLedgerStateProvider("INFO_ACTION", false, true));

        var result = validator.runSupplementaryRules(votingTx(VoterType.STAKING_POOL_KEY_HASH), 0, new ProtocolParams());

        assertFalse(result.valid());
        assertEquals("GovernanceValidation", result.errors().get(0).rule());
        assertTrue(result.errors().get(0).message().contains("is not active"));
    }

    @Test
    void supplementaryRulesValidateCommitteeHotVoterAuthorizationWhenAvailable() {
        var validator = new ScalusBasedTransactionValidator(
                new ProtocolParams(), null, new SlotConfig(1000, 0, 0), 0,
                new GovernanceProposalLedgerStateProvider("INFO_ACTION", true, false));

        var result = validator.runSupplementaryRules(votingTx(VoterType.CONSTITUTIONAL_COMMITTEE_HOT_KEY_HASH),
                0, new ProtocolParams());

        assertFalse(result.valid());
        assertEquals("GovernanceValidation", result.errors().get(0).rule());
        assertTrue(result.errors().get(0).message().contains("committee hot voter"));
    }

    @Test
    void supplementaryRulesUseCurrentTransactionHashForLocalProposalVotes() {
        String txHash = filledHex(32, 9);
        var validator = new ScalusBasedTransactionValidator(
                new ProtocolParams(), null, new SlotConfig(1000, 0, 0), 0,
                new RegisteredPoolLedgerStateProvider());

        var result = validator.runSupplementaryRules(localProposalVoteTx(txHash), 0, new ProtocolParams(), txHash);

        assertFalse(result.valid());
        assertEquals("GovernanceValidation", result.errors().get(0).rule());
        assertTrue(result.errors().get(0).message().contains("SPO voter is not allowed"));
    }

    @Test
    void supplementaryRulesRejectPrevGovActionWithDifferentPurpose() {
        String prevHash = filledHex(32, 7);
        var validator = new ScalusBasedTransactionValidator(
                new ProtocolParams(), null, new SlotConfig(1000, 0, 0), 0,
                new GovernanceProposalLedgerStateProvider("INFO_ACTION", false, true));

        var result = validator.runSupplementaryRules(proposalWithPreviousAction(prevHash), 0, new ProtocolParams());

        assertFalse(result.valid());
        assertEquals("GovernanceValidation", result.errors().get(0).rule());
        assertTrue(result.errors().get(0).message().contains("expected same purpose"));
    }

    @Test
    void supplementaryRulesDoNotUseLocalProposalsForPreviousActionReferences() {
        String txHash = filledHex(32, 9);
        var validator = new ScalusBasedTransactionValidator(
                new ProtocolParams(), null, new SlotConfig(1000, 0, 0), 0,
                new RegisteredPoolLedgerStateProvider());

        var result = validator.runSupplementaryRules(proposalWithPreviousAction(txHash), 0, new ProtocolParams(),
                txHash);

        assertFalse(result.valid());
        assertEquals("GovernanceValidation", result.errors().get(0).rule());
        assertTrue(result.errors().get(0).message().contains("does not exist"));
    }

    @Test
    void supplementaryRulesUseTypedCommitteeHotCredentialAuthorization() {
        var validator = new ScalusBasedTransactionValidator(
                new ProtocolParams(), null, new SlotConfig(1000, 0, 0), 0,
                new ScriptCommitteeHotLedgerStateProvider());

        var result = validator.runSupplementaryRules(votingTx(VoterType.CONSTITUTIONAL_COMMITTEE_HOT_SCRIPT_HASH),
                0, new ProtocolParams());

        assertTrue(result.valid());
    }

    private static Transaction poolRetirementTx(long retireEpoch) {
        byte[] poolKeyHash = new byte[28];
        Arrays.fill(poolKeyHash, (byte) 1);
        return Transaction.builder()
                .body(TransactionBody.builder()
                        .certs(List.of(PoolRetirement.builder()
                                .poolKeyHash(poolKeyHash)
                                .epoch(retireEpoch)
                                .build()))
                        .build())
                .build();
    }

    private static Transaction votingTx(VoterType voterType) {
        var procedures = new VotingProcedures();
        procedures.add(Voter.builder()
                        .type(voterType)
                        .credential(credentialFor(voterType))
                        .build(),
                GovActionId.builder()
                        .transactionId(filledHex(32, 3))
                        .govActionIndex(0)
                        .build(),
                VotingProcedure.builder()
                        .vote(Vote.YES)
                        .build());
        return Transaction.builder()
                .body(TransactionBody.builder()
                        .votingProcedures(procedures)
                        .build())
                .build();
    }

    private static Transaction localProposalVoteTx(String txHash) {
        var body = TransactionBody.builder()
                .proposalProcedures(List.of(ProposalProcedure.builder()
                        .govAction(ParameterChangeAction.builder()
                                .protocolParamUpdate(ProtocolParamUpdate.builder()
                                        .maxTxSize(16_384)
                                        .build())
                                .build())
                        .build()))
                .votingProcedures(votingProcedures(
                        VoterType.STAKING_POOL_KEY_HASH,
                        GovActionId.builder()
                                .transactionId(txHash)
                                .govActionIndex(0)
                                .build()))
                .build();
        return Transaction.builder().body(body).build();
    }

    private static Transaction proposalWithPreviousAction(String prevHash) {
        return Transaction.builder()
                .body(TransactionBody.builder()
                        .proposalProcedures(List.of(ProposalProcedure.builder()
                                .govAction(ParameterChangeAction.builder()
                                        .prevGovActionId(GovActionId.builder()
                                                .transactionId(prevHash)
                                                .govActionIndex(0)
                                                .build())
                                        .protocolParamUpdate(ProtocolParamUpdate.builder()
                                                .maxTxSize(16_384)
                                                .build())
                                        .build())
                                .build()))
                        .build())
                .build();
    }

    private static VotingProcedures votingProcedures(VoterType voterType, GovActionId actionId) {
        var procedures = new VotingProcedures();
        procedures.add(Voter.builder()
                        .type(voterType)
                        .credential(credentialFor(voterType))
                        .build(),
                actionId,
                VotingProcedure.builder()
                        .vote(Vote.YES)
                        .build());
        return procedures;
    }

    private static Credential credentialFor(VoterType voterType) {
        if (voterType == VoterType.CONSTITUTIONAL_COMMITTEE_HOT_SCRIPT_HASH
                || voterType == VoterType.DREP_SCRIPT_HASH) {
            return Credential.fromScript(filledBytes(28, 2));
        }
        return Credential.fromKey(filledBytes(28, 2));
    }

    private static byte[] filledBytes(int length, int value) {
        byte[] bytes = new byte[length];
        Arrays.fill(bytes, (byte) value);
        return bytes;
    }

    private static String filledHex(int length, int value) {
        byte[] bytes = filledBytes(length, value);
        StringBuilder sb = new StringBuilder(length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    private static class ScalusSuccessValidator extends ScalusBasedTransactionValidator {
        ScalusSuccessValidator(LedgerStateProvider provider) {
            this(provider, () -> 0L);
        }

        ScalusSuccessValidator(LedgerStateProvider provider, LongSupplier currentSlotSupplier) {
            super(slot -> new ProtocolParams(), null, new SlotConfig(1000, 0, 0), 0,
                    provider, currentSlotSupplier);
        }

        @Override
        protected TransitResult runScalusValidation(byte[] txCbor, ProtocolParams protocolParams,
                                                   Set<Utxo> inputUtxos, long currentSlot) {
            return new TransitResult(true, null, null);
        }

        @Override
        protected Transaction deserializeTransaction(byte[] txCbor) {
            return Transaction.builder().body(null).build();
        }
    }

    private static class SlotUnavailableValidator extends ScalusSuccessValidator {
        SlotUnavailableValidator(LedgerStateProvider provider) {
            super(provider, () -> -1L);
        }
    }

    private static class MinimalLedgerStateProvider implements LedgerStateProvider {
        @Override
        public Optional<BigInteger> getRewardBalance(int credType, String credentialHash) {
            return Optional.empty();
        }

        @Override
        public Optional<BigInteger> getStakeDeposit(int credType, String credentialHash) {
            return Optional.empty();
        }

        @Override
        public Optional<String> getDelegatedPool(int credType, String credentialHash) {
            return Optional.empty();
        }

        @Override
        public Optional<LedgerStateProvider.DRepDelegation> getDRepDelegation(int credType, String credentialHash) {
            return Optional.empty();
        }

        @Override
        public boolean isStakeCredentialRegistered(int credType, String credentialHash) {
            return false;
        }

        @Override
        public BigInteger getTotalDeposited() {
            return BigInteger.ZERO;
        }

        @Override
        public boolean isPoolRegistered(String poolHash) {
            return false;
        }

        @Override
        public Optional<BigInteger> getPoolDeposit(String poolHash) {
            return Optional.empty();
        }

        @Override
        public Optional<Long> getPoolRetirementEpoch(String poolHash) {
            return Optional.empty();
        }
    }

    private static class RegisteredPoolLedgerStateProvider extends MinimalLedgerStateProvider {
        @Override
        public boolean isPoolRegistered(String poolHash) {
            return true;
        }
    }

    private static class GovernanceProposalLedgerStateProvider extends RegisteredPoolLedgerStateProvider {
        private final String actionType;
        private final boolean active;
        private final Optional<Boolean> committeeHotAuthorized;

        GovernanceProposalLedgerStateProvider(String actionType, boolean active, boolean committeeHotAuthorized) {
            this.actionType = actionType;
            this.active = active;
            this.committeeHotAuthorized = Optional.of(committeeHotAuthorized);
        }

        @Override
        public Optional<GovernanceActionInfo> getGovernanceAction(String txHash, int govActionIndex) {
            return Optional.of(new GovernanceActionInfo(actionType, active, !active));
        }

        @Override
        public Optional<Boolean> isCommitteeHotCredentialAuthorized(int hotCredType, String hotCredentialHash) {
            return committeeHotAuthorized;
        }

        @Override
        public Optional<Boolean> isCommitteeHotCredentialAuthorized(int hotCredType, String hotCredentialHash,
                                                                    long currentEpoch) {
            return committeeHotAuthorized;
        }
    }

    private static class ScriptCommitteeHotLedgerStateProvider extends GovernanceProposalLedgerStateProvider {
        ScriptCommitteeHotLedgerStateProvider() {
            super("INFO_ACTION", true, false);
        }

        @Override
        public Optional<Boolean> isCommitteeHotCredentialAuthorized(int hotCredType, String hotCredentialHash) {
            return Optional.of(hotCredType == 1);
        }

        @Override
        public Optional<Boolean> isCommitteeHotCredentialAuthorized(int hotCredType, String hotCredentialHash,
                                                                    long currentEpoch) {
            return Optional.of(hotCredType == 1);
        }
    }
}
