package org.yanoproject.ledger.rules.conway;

import com.bloxbean.cardano.client.util.HexUtil;
import org.junit.jupiter.api.Test;
import org.yanoproject.ledger.rules.TxValidationOutcome;
import org.yanoproject.ledger.rules.conway.tx.RawScript;
import org.yanoproject.ledger.rules.conway.tx.TxDecodingException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ADR 0005 D7: where Yano calls CCL's strict reads, the whole {@code CborRuntimeException} family is a decoding
 * failure, as before the policy moved, and never an engine failure.
 */
class StrictReadBoundaryTest {

    private static final String BODY = "a3" + "0081" + "825820" + "00".repeat(32) + "00" + "0180" + "0200";

    @Test
    void aTruncatedOrRejectedTransactionIsADecodingFailure() {
        for (String tx : new String[]{"84" + BODY + "a0f5", "84", "", "84" + BODY + "a0f5f6" + "00",
                "84" + "a4" + BODY.substring(2) + "0c00" + "a0f5f6"}) {
            TxValidationOutcome outcome = EngineTestSupport.validate(new EngineTestSupport.StubEvaluator(),
                    HexUtil.decodeHexString(tx));
            assertThat(EngineTestSupport.names(outcome)).as(tx).containsExactly("ENGINE.DecodingFailure");
        }
    }

    @Test
    void aTruncatedReferenceScriptIsATxDecodingException() {
        for (String ref : new String[]{"d8184a82024746010000", "820247460100", "", "d818", "d8184382024a"}) {
            assertThatThrownBy(() -> RawScript.fromScriptRef(HexUtil.decodeHexString(ref))).as(ref)
                    .isInstanceOf(TxDecodingException.class);
        }
    }
}
