package org.yanoproject.ledger.rules.conway.tx;

import com.bloxbean.cardano.client.common.cbor.CborSpan;
import com.bloxbean.cardano.client.exception.CborRuntimeException;
import com.bloxbean.cardano.client.transaction.raw.FieldType;
import com.bloxbean.cardano.client.transaction.raw.HaskellPolicy;
import com.bloxbean.cardano.client.transaction.raw.RecordSpec;

/**
 * CCL's Haskell-strict walks as Yano's admission sees them (ADR 0005 D7): any {@code CborRuntimeException} of the
 * strict read, its {@code LedgerDecodingException} included, is a {@link TxDecodingException}.
 */
final class StrictReads {

    private StrictReads() {
    }

    static void walk(FieldType type, CborSpan value) {
        try {
            HaskellPolicy.CONWAY.walk(type, value);
        } catch (CborRuntimeException e) {
            throw new TxDecodingException(e.getMessage(), e);
        }
    }

    static void walk(RecordSpec record, CborSpan value) {
        try {
            HaskellPolicy.CONWAY.walk(record, value);
        } catch (CborRuntimeException e) {
            throw new TxDecodingException(e.getMessage(), e);
        }
    }
}
