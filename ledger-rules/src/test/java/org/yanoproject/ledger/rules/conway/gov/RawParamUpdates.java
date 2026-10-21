package org.yanoproject.ledger.rules.conway.gov;

import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.DataItem;
import co.nstant.in.cbor.model.Map;
import co.nstant.in.cbor.model.UnsignedInteger;

import java.util.List;
import java.util.function.Consumer;

/**
 * Body edits ({@code TxSpec.bodyEdit}) that write a parameter change's {@code protocol_param_update} directly, for the
 * Conway keys 25–33 CCL's {@code ProtocolParamUpdate} cannot express.
 */
final class RawParamUpdates {

    private static final UnsignedInteger PROPOSAL_PROCEDURES = new UnsignedInteger(20);

    private RawParamUpdates() {
    }

    /**
     * @param entries the integer-valued keys of the new update (every parameter change of the body gets it)
     * @return an edit replacing each parameter change's update with {@code entries}
     */
    static Consumer<Map> replaceParamUpdate(java.util.Map<Integer, Long> entries) {
        return body -> {
            Array procedures = (Array) body.get(PROPOSAL_PROCEDURES);
            for (DataItem item : procedures.getDataItems()) {
                List<DataItem> procedure = ((Array) item).getDataItems();
                List<DataItem> action = ((Array) procedure.get(2)).getDataItems();
                if (((UnsignedInteger) action.get(0)).getValue().intValue() != 0) {
                    continue;
                }
                Map update = new Map();
                entries.forEach((key, value) -> update.put(new UnsignedInteger(key), new UnsignedInteger(value)));
                action.set(2, update);
            }
        };
    }
}
