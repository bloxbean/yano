package org.yanoproject.ledger.rules.util;

import com.bloxbean.cardano.client.common.cbor.CborSpan;
import com.bloxbean.cardano.client.exception.CborRuntimeException;

import java.math.BigInteger;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * Reads which protocol parameters a {@code ParameterChange} proposal changes, straight from the original
 * CBOR: the key set of its {@code protocol_param_update} map (Conway CDDL numbering, 0–33).
 *
 * <p>CCL's {@code ProtocolParamUpdate} has no fields for the Conway keys 25–33 (voting thresholds,
 * committee, governance deposits and lifetimes, {@code minFeeRefScriptCostPerByte}), so a decoded action
 * cannot tell, for example, whether a proposal touches Haskell's security group. See
 * {@link org.yanoproject.ledger.rules.view.model.ProposalState#paramUpdateKeys()}.</p>
 *
 * <pre>
 * gov_action = [0, gov_action_id / null, protocol_param_update, policy_hash / null] / …
 * </pre>
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
            CborSpan action = untagged(CborSpan.at(govActionCbor, 0), 4);
            List<CborSpan> fields = action.items();
            if (fields.isEmpty()) {
                throw new IllegalArgumentException("a gov_action is an empty array");
            }
            if (untagged(fields.get(0), 0).asBigInteger().signum() != 0) {
                return null;
            }
            if (action.isIndefinite() || fields.size() != 4) {
                throw new IllegalArgumentException("parameter_change_action must have 4 fields");
            }
            Set<Integer> keys = new TreeSet<>();
            for (Map.Entry<CborSpan, CborSpan> entry : untagged(fields.get(2), 5).entries()) {
                BigInteger key = untagged(entry.getKey(), 0).asBigInteger();
                if (key.bitLength() > 31 || !keys.add(key.intValue())) {
                    throw new IllegalArgumentException("bad or duplicate protocol_param_update key " + key);
                }
            }
            return Collections.unmodifiableSet(keys);
        } catch (CborRuntimeException e) {
            throw new IllegalArgumentException(e.getMessage(), e);
        }
    }

    private static CborSpan untagged(CborSpan item, int major) {
        if (item.tag() != -1 || item.majorType() != major) {
            throw new IllegalArgumentException("expected CBOR major type " + major + " at " + item.offset());
        }
        return item;
    }
}
