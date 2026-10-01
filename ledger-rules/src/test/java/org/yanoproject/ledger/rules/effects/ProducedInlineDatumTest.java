package org.yanoproject.ledger.rules.effects;

import com.bloxbean.cardano.client.common.cbor.CborSerializationUtil;
import com.bloxbean.cardano.client.transaction.spec.Transaction;
import com.bloxbean.cardano.client.util.HexUtil;
import org.junit.jupiter.api.Test;
import org.yanoproject.ledger.rules.conway.tx.RawTransaction;
import org.yanoproject.ledger.rules.fixtures.PublicNetworkTransactions;
import org.yanoproject.ledger.rules.view.Fixtures;
import org.yanoproject.ledger.rules.view.InMemoryLedgerView;
import org.yanoproject.ledger.rules.view.model.UtxoEntry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.yanoproject.ledger.rules.fixtures.PublicNetworkTransactions.PREPROD_SAME_BLOCK_DATUM_PRODUCER;

/**
 * ADR-056 Phase 7c: an output produced within a block or mempool chain keeps its inline datum's original bytes.
 *
 * <p>Preprod transaction {@code 1fc4d810bf7a14929c044c53d1186dc9444d58d4bb5d66403375139cd48210bc} (block 2634522,
 * slot 69176184) pays output 0 to script {@code d4156c1a…} with an inline datum whose map has the keys {@code endDate},
 * {@code price}, {@code startDate}, in that (lexicographic) order. Transaction {@code 4cad6278…} of the same block
 * spends it, and its PlutusV2 script checks the datum. Shadow sync reported that spend, and 132 like it, as
 * {@code UTXOS.ValidationTagMismatch}: {@code TxEffectsDeriver} produced the entry without the datum's bytes, so the
 * Scalus bridge used CCL's canonical re-encoding, which sorts map keys shorter first ({@code price} first), and the
 * script saw a different {@code Data}. Haskell keeps the bytes ({@code BinaryData}, cardano-ledger-core
 * Plutus/Data.hs:220-239). The transaction's CBOR comes from Koios ({@code tx_cbor}).</p>
 */
class ProducedInlineDatumTest {

    private static final String PRODUCER = PublicNetworkTransactions.txId(PREPROD_SAME_BLOCK_DATUM_PRODUCER);

    @Test
    void aProducedOutputKeepsItsInlineDatumBytes() throws Exception {
        byte[] txCbor = PublicNetworkTransactions.cbor(PREPROD_SAME_BLOCK_DATUM_PRODUCER);
        Transaction tx = Transaction.deserialize(txCbor);
        byte[] original = RawTransaction.parse(txCbor, tx).outputs().get(0).inlineDatum();
        assertThat(original).isNotNull();

        TxEffects effects = new TxEffectsDeriver().derive(txCbor, tx, null,
                InMemoryLedgerView.builder().protocolParams(Fixtures.protocolParams()).build(), Fixtures.env(9), true);

        UtxoEntry produced = effects.produced().get(0);
        assertThat(produced.outpoint().txHash()).isEqualTo(PRODUCER);
        assertThat(produced.inlineDatumCbor()).isEqualTo(original);
        // Why it matters: CCL's canonical re-encoding of the same datum is a different Data for a script.
        byte[] canonical = CborSerializationUtil.serialize(produced.output().getInlineDatum().serialize());
        assertThat(canonical).isNotEqualTo(original);
        assertThat(HexUtil.encodeHexString(canonical).indexOf("7072696365")) // "price"
                .isLessThan(HexUtil.encodeHexString(canonical).indexOf("656e6444617465")); // "endDate"
        assertThat(HexUtil.encodeHexString(original).indexOf("656e6444617465"))
                .isLessThan(HexUtil.encodeHexString(original).indexOf("7072696365"));
    }

    @Test
    void aPhase2InvalidTransactionsCollateralReturnKeepsItsBytesToo() throws Exception {
        byte[] txCbor = PublicNetworkTransactions.cbor(PREPROD_SAME_BLOCK_DATUM_PRODUCER);
        Transaction tx = Transaction.deserialize(txCbor);
        TxEffects effects = new TxEffectsDeriver().derive(txCbor, tx, null,
                InMemoryLedgerView.builder().protocolParams(Fixtures.protocolParams()).build(), Fixtures.env(9), false);
        // The collateral return has no datum; the entry is the chain's collateral return, at index |outputs|.
        assertThat(effects.produced()).singleElement().satisfies(e -> {
            assertThat(e.outpoint().index()).isEqualTo(tx.getBody().getOutputs().size());
            assertThat(e.inlineDatumCbor()).isNull();
        });
    }
}
