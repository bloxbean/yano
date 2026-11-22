package org.yanoproject.ledger.rules.conway.rule;

import com.bloxbean.cardano.client.address.AddressProvider;
import com.bloxbean.cardano.client.address.Credential;
import com.bloxbean.cardano.client.common.model.Networks;
import com.bloxbean.cardano.client.crypto.Blake2bUtil;
import com.bloxbean.cardano.client.transaction.spec.Transaction;
import com.bloxbean.cardano.client.transaction.spec.TransactionBody;
import com.bloxbean.cardano.client.transaction.spec.TransactionInput;
import com.bloxbean.cardano.client.transaction.spec.TransactionOutput;
import com.bloxbean.cardano.client.transaction.spec.TransactionWitnessSet;
import com.bloxbean.cardano.client.transaction.spec.Value;
import com.bloxbean.cardano.client.util.HexUtil;
import org.junit.jupiter.api.Test;
import org.yanoproject.ledger.rules.conway.LedgerContext;
import org.yanoproject.ledger.rules.conway.RuleValidationError;
import org.yanoproject.ledger.rules.view.slice.SimpleUtxoSlice;

import java.math.BigInteger;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class WitnessValidationRuleTest {

    private static final TransactionInput SPENT = new TransactionInput("11".repeat(32), 0);
    private static final TransactionInput REFERENCE = new TransactionInput("22".repeat(32), 0);

    /**
     * A reference native script provides the hash of its bytes, as Haskell's {@code MemoBytes} keeps them: here
     * {@code [0, keyhash]} with its type written {@code 18 00}, which CCL's model would re-encode as {@code 00}.
     */
    @Test
    void aReferenceNativeScriptProvidesTheHashOfItsBytes() {
        byte[] nativeScript = HexUtil.decodeHexString("82" + "1800" + "581c" + "33".repeat(28));
        byte[] tagged = new byte[nativeScript.length + 1];
        System.arraycopy(nativeScript, 0, tagged, 1, nativeScript.length);
        byte[] hash = Blake2bUtil.blake2bHash224(tagged);
        byte[] scriptRef = HexUtil.decodeHexString("8200" + HexUtil.encodeHexString(nativeScript));

        String locked = AddressProvider.getEntAddress(Credential.fromScript(hash), Networks.testnet()).toBech32();
        TransactionOutput spent = output(locked, null);
        TransactionOutput reference = output(locked, scriptRef);
        LedgerContext context = LedgerContext.builder()
                .utxoSlice(new SimpleUtxoSlice(Map.of(SPENT, spent, REFERENCE, reference)))
                .build();
        Transaction tx = Transaction.builder()
                .body(TransactionBody.builder()
                        .inputs(List.of(SPENT))
                        .referenceInputs(List.of(REFERENCE))
                        .fee(BigInteger.ONE)
                        .build())
                .witnessSet(new TransactionWitnessSet())
                .build();

        assertThat(new WitnessValidationRule().validate(context, tx)).map(RuleValidationError::toString)
                .noneMatch(message -> message.contains("Missing script witnesses"));
    }

    private static TransactionOutput output(String address, byte[] scriptRef) {
        return TransactionOutput.builder().address(address).value(Value.builder().coin(BigInteger.TEN).build())
                .scriptRef(scriptRef).build();
    }
}
