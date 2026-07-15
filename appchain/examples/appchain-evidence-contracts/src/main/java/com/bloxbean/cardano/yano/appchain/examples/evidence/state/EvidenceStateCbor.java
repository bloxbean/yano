package com.bloxbean.cardano.yano.appchain.examples.evidence.state;

import co.nstant.in.cbor.model.ByteString;
import co.nstant.in.cbor.model.DataItem;
import co.nstant.in.cbor.model.SimpleValue;
import co.nstant.in.cbor.model.SimpleValueType;

/** Package-local nullable nested-state helpers. */
final class EvidenceStateCbor {
    private EvidenceStateCbor() {
    }

    static DataItem nullable(EvidenceEffectRef reference) {
        return reference == null ? SimpleValue.NULL : reference.toDataItem();
    }

    static DataItem nullable(EvidenceTerminalResultV1 result) {
        return result == null ? SimpleValue.NULL : result.toDataItem();
    }

    static DataItem nullable(byte[] bytes) {
        return bytes == null ? SimpleValue.NULL : new ByteString(bytes);
    }

    static EvidenceEffectRef nullableEffectRef(DataItem item) {
        return isNull(item) ? null : EvidenceEffectRef.fromDataItem(item);
    }

    static EvidenceTerminalResultV1 nullableTerminal(DataItem item) {
        return isNull(item) ? null : EvidenceTerminalResultV1.fromDataItem(item);
    }

    static byte[] nullableBytes(DataItem item) {
        if (isNull(item)) {
            return null;
        }
        if (!(item instanceof ByteString bytes)) {
            throw com.bloxbean.cardano.yano.appchain.examples.evidence.internal
                    .EvidenceValidation.invalid();
        }
        return bytes.getBytes().clone();
    }

    private static boolean isNull(DataItem item) {
        return item instanceof SimpleValue value
                && value.getSimpleValueType() == SimpleValueType.NULL;
    }
}
