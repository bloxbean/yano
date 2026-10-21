package org.yanoproject.runtime.ledger.canonical;

import com.bloxbean.cardano.yaci.core.model.ProtocolParamUpdate;
import com.bloxbean.cardano.yaci.core.model.governance.GovActionType;
import com.bloxbean.cardano.yaci.core.model.governance.actions.GovAction;
import com.bloxbean.cardano.yaci.core.model.governance.actions.InfoAction;
import com.bloxbean.cardano.yaci.core.model.governance.actions.ParameterChangeAction;
import com.bloxbean.cardano.yaci.core.model.serializers.governance.GovActionSerializer;
import com.bloxbean.cardano.yaci.core.types.NonNegativeInterval;
import org.junit.jupiter.api.Test;
import org.yanoproject.ledger.rules.view.model.ProposalState;
import org.yanoproject.ledgerstate.governance.GovernanceSnapshotReader.StoredProposal;
import org.yanoproject.ledgerstate.governance.model.GovActionRecord;

import java.math.BigInteger;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ADR-057 Phase B deviation 4 / ADR-056 step 1d: the canonical view reads a pending ParameterChange's
 * {@code protocol_param_update} keys from the stored action bytes (yaci's re-serialisation), including the
 * Conway keys CCL cannot represent.
 */
class CanonicalParamUpdateKeysTest {

    private static final String TX = "aa".repeat(32);

    @Test
    void conwaySecurityGroupKeysSurviveYacisReserialisation() throws Exception {
        ParameterChangeAction action = new ParameterChangeAction(null, ProtocolParamUpdate.builder()
                .govActionDeposit(BigInteger.valueOf(100_000_000_000L))               // key 30
                .minFeeRefScriptCostPerByte(new NonNegativeInterval(BigInteger.valueOf(15), BigInteger.ONE)) // key 33
                .build(), null);
        StoredProposal stored = stored(action, GovActionType.PARAMETER_CHANGE_ACTION);

        assertThat(CanonicalLedgerView.paramUpdateKeys(stored)).containsExactlyInAnyOrder(30, 33);
        ProposalState state = CanonicalLedgerView.toProposalState(stored);
        assertThat(state.paramUpdateKeys()).containsExactlyInAnyOrder(30, 33);
        assertThat(state.anyInSecurityGroup()).isTrue();
    }

    @Test
    void nonSecurityKeysAreNotInTheSecurityGroup() throws Exception {
        ParameterChangeAction action = new ParameterChangeAction(null, ProtocolParamUpdate.builder()
                .govActionLifetime(12)                                                  // key 29
                .drepDeposit(BigInteger.valueOf(500_000_000L))                          // key 31
                .build(), null);
        ProposalState state = CanonicalLedgerView.toProposalState(stored(action,
                GovActionType.PARAMETER_CHANGE_ACTION));

        assertThat(state.paramUpdateKeys()).isEqualTo(Set.of(29, 31));
        assertThat(state.anyInSecurityGroup()).isFalse();
    }

    @Test
    void otherActionsAndMissingPayloadsCarryNoKeys() throws Exception {
        assertThat(CanonicalLedgerView.paramUpdateKeys(stored(new InfoAction(), GovActionType.INFO_ACTION)))
                .isNull();
        StoredProposal noPayload = new StoredProposal(TX, 1, record(GovActionType.PARAMETER_CHANGE_ACTION, null),
                null);
        assertThat(CanonicalLedgerView.paramUpdateKeys(noPayload)).isNull();
        assertThat(CanonicalLedgerView.toProposalState(noPayload).anyInSecurityGroup()).isNull();
    }

    private static StoredProposal stored(GovAction action,
                                         GovActionType type) {
        byte[] cbor = GovActionSerializer.INSTANCE.serialize(action);
        return new StoredProposal(TX, 0, record(type, action), cbor);
    }

    private static GovActionRecord record(GovActionType type,
                                          GovAction action) {
        return new GovActionRecord(BigInteger.valueOf(100_000_000_000L), "e0" + "11".repeat(28), 10, 16, type, null,
                null, action, 1234L);
    }
}
