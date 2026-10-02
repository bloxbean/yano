package org.yanoproject.ledger.rules.conway.tx;

import com.bloxbean.cardano.client.transaction.spec.Transaction;

import org.junit.jupiter.api.Test;
import org.yanoproject.ledger.rules.TxValidationOutcome;
import org.yanoproject.ledger.rules.conway.EngineTestSupport;
import org.yanoproject.ledger.rules.fixtures.PublicNetworkTransactions;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Preprod transaction {@code f90dce57…24c9} (block 5183974) carries a witness native script nested 5,383 levels deep.
 * Its decoding, hashing and evaluation once overflowed the stack (CCL's recursive decoder and Yano's recursive
 * {@code Timelock}); every step is now iterative, so it runs on a platform thread with the default stack. Full ledger
 * validation needs the transaction's UTxO, so that part is proven by a preprod sync.
 */
class DeepNativeScriptTest {

    private static final String NAME = PublicNetworkTransactions.PREPROD_DEEP_NATIVE_SCRIPT;

    @Test
    void decodesHashesAndEvaluatesTheDeepNativeScriptOnADefaultStackThread() throws Exception {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread thread = new Thread(() -> {
            try {
                check();
            } catch (Throwable t) {
                failure.set(t);
            }
        });
        thread.start();
        thread.join();
        if (failure.get() != null) {
            throw new AssertionError(failure.get());
        }
    }

    private static void check() throws Exception {
        byte[] txCbor = PublicNetworkTransactions.cbor(NAME);
        Transaction decoded = CclTransactions.deserialize(txCbor);
        RawTransaction raw = RawTransaction.parse(txCbor, decoded);

        assertThat(raw.txIdHex()).isEqualTo(PublicNetworkTransactions.txId(NAME));
        List<RawScript> natives = raw.witnessScripts().stream().filter(RawScript::isNative).toList();
        assertThat(natives).hasSize(1);
        RawScript script = natives.getFirst();
        assertThat(script.hashHex()).isEqualTo("ff3efca65569f6b0b868a3d34abdb1ad8eccf745e0da71fa94fb4f18");

        // The chain accepted the transaction, so its only native script, which it must need, holds.
        Set<String> vkeyHashes = new HashSet<>();
        raw.vkeyWitnesses().forEach(w -> vkeyHashes.add(w.keyHashHex()));
        assertThat(script.nativeScriptHolds(vkeyHashes, raw.validityStart(), raw.ttl())).isTrue();

        // The whole engine runs it to a ledger verdict: without the transaction's UTxO the inputs are missing, but no
        // step fails as an engine failure (ENGINE.JavaEngineFailure was a StackOverflowError).
        TxValidationOutcome outcome = EngineTestSupport.validate(null, txCbor);
        assertThat(EngineTestSupport.names(outcome)).contains("UTXO.BadInputsUTxO")
                .noneMatch(name -> name.startsWith("ENGINE"));
    }
}
