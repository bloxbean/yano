package com.bloxbean.cardano.yano.appchain.showcase;

import com.bloxbean.cardano.yano.api.appchain.AppBlockExecutionContext;
import com.bloxbean.cardano.yano.api.appchain.AppQueryContext;
import com.bloxbean.cardano.yano.api.appchain.AppQueryException;
import com.bloxbean.cardano.yano.api.appchain.AppStateMachine;
import com.bloxbean.cardano.yano.api.appchain.AppStateWriter;
import com.bloxbean.cardano.yano.api.appchain.effects.AppEffectEmitter;

import java.nio.charset.StandardCharsets;

/** State owner for document-review approval-consumption receipts. */
final class DocumentReviewReceiptStateMachine implements AppStateMachine {
    static final String ID = "document-review-receipts";
    static final String QUERY_RECEIPT = "receipt";
    private static final byte[] PREFIX = "approval/".getBytes(StandardCharsets.US_ASCII);

    @Override public String id() { return ID; }

    @Override
    public void apply(AppBlockExecutionContext context, AppStateWriter writer, AppEffectEmitter effects) {
        // Writes are owned by DocumentReviewWorkflow.
    }

    @Override
    public byte[] query(String path, byte[] params, AppQueryContext state) {
        if (!QUERY_RECEIPT.equals(path)) {
            throw new AppQueryException(AppQueryException.Code.UNSUPPORTED,
                    "unsupported document-review query");
        }
        return state.get(receiptKey(asciiId(params))).orElse(new byte[0]);
    }

    static byte[] receiptKey(String proposalId) {
        byte[] id = asciiId(proposalId.getBytes(StandardCharsets.US_ASCII))
                .getBytes(StandardCharsets.US_ASCII);
        byte[] key = new byte[PREFIX.length + id.length];
        System.arraycopy(PREFIX, 0, key, 0, PREFIX.length);
        System.arraycopy(id, 0, key, PREFIX.length, id.length);
        return key;
    }

    private static String asciiId(byte[] value) {
        String id = new String(value, StandardCharsets.US_ASCII);
        if (!id.matches("[a-z0-9][a-z0-9._-]{0,127}")) {
            throw new AppQueryException(AppQueryException.Code.INVALID_REQUEST,
                    "receipt query requires a canonical proposal id");
        }
        return id;
    }
}
