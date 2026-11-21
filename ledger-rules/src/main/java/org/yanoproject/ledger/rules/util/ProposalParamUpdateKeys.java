package org.yanoproject.ledger.rules.util;

import com.bloxbean.cardano.client.common.cbor.CborSpan;
import com.bloxbean.cardano.client.exception.CborRuntimeException;
import com.bloxbean.cardano.client.spec.Era;
import com.bloxbean.cardano.client.transaction.raw.GovActionKind;
import com.bloxbean.cardano.client.transaction.raw.RawGovAction;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * Reads which protocol parameters a {@code ParameterChange} proposal changes: the key set of its
 * {@code protocol_param_update} map (Conway CDDL numbering, 0–33), read with CCL's {@link RawGovAction}.
 *
 * <p>CCL's {@code ProtocolParamUpdate} model has no fields for the Conway keys 25–33 (voting thresholds,
 * committee, governance deposits and lifetimes, {@code minFeeRefScriptCostPerByte}), so a decoded action
 * cannot tell, for example, whether a proposal touches Haskell's security group. See
 * {@link org.yanoproject.ledger.rules.view.model.ProposalState#paramUpdateKeys()}.</p>
 *
 * <p>A transaction's proposals carry the same keys ({@code RawProposal#paramUpdate()}).</p>
 */
public final class ProposalParamUpdateKeys {

    private ProposalParamUpdateKeys() {
    }

    /**
     * @param govActionCbor one {@code gov_action}
     * @return the parameter-update keys when it is a {@code ParameterChange}, otherwise {@code null}
     * @throws IllegalArgumentException when the bytes are not well-formed
     */
    public static Set<Integer> fromGovAction(byte[] govActionCbor) {
        Objects.requireNonNull(govActionCbor, "govActionCbor");
        try {
            CborSpan action = CborSpan.of(govActionCbor);
            if (GovActionKind.of(action).orElse(null) != GovActionKind.PARAMETER_CHANGE) {
                return null;
            }
            Set<Integer> keys = new TreeSet<>();
            CborSpan update = RawGovAction.of(action, Era.Conway).protocolParamUpdate().span();
            for (Map.Entry<CborSpan, CborSpan> entry : update.entries()) {
                CborSpan key = entry.getKey();
                if (key.tag() != -1 || key.majorType() != 0 || key.asBigInteger().bitLength() > 31
                        || !keys.add(key.asBigInteger().intValue())) {
                    throw new IllegalArgumentException("bad or duplicate protocol_param_update key at " + key.offset());
                }
            }
            return Collections.unmodifiableSet(keys);
        } catch (CborRuntimeException e) {
            throw new IllegalArgumentException(e.getMessage(), e);
        }
    }
}
