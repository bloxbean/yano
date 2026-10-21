package org.yanoproject.ledger.rules.conway.tx;

import com.bloxbean.cardano.client.exception.CborDeserializationException;
import com.bloxbean.cardano.client.transaction.spec.Transaction;
import com.bloxbean.cardano.client.transaction.spec.cert.PoolRegistration;
import com.bloxbean.cardano.client.util.HexUtil;
import org.junit.jupiter.api.Test;
import org.yanoproject.ledger.rules.TxIdentity;
import org.yanoproject.ledger.rules.fixtures.PublicNetworkTransactions;
import org.yanoproject.ledger.rules.util.DefiniteLengthCbor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.yanoproject.ledger.rules.fixtures.PublicNetworkTransactions.PREVIEW_INDEFINITE_POOL_OWNERS;

/**
 * ADR-056 Phase 7c: CCL's decoder and indefinite-length arrays.
 *
 * <p>Preview transaction {@code 1c09afd80edba3e530fa48fa34f1bc0b2c7999b7e0c76bda2d644444c53e1032} (slot 60896134,
 * block 2527148, protocol version 9) registers a pool whose owners and relays are indefinite-length arrays. Haskell
 * decodes both ({@code decodeSet} / {@code decodeSetLikeEnforceNoDuplicates}, cardano-ledger-binary
 * Decoder.hs:952-962 and :1081-1085; {@code decodeStrictSeq}, :1156-1157; {@code StakePoolParams},
 * cardano-ledger-core State/StakePool.hs:574-590), and the chain accepted it. CCL's
 * {@code PoolRegistration.deserialize} casts cbor-java's trailing {@code break} marker to a byte string, so the java
 * engine reported {@code ENGINE.DecodingFailure} in shadow sync. The CBOR comes from the shadow-sync dump.</p>
 */
class CclTransactionsTest {

    private static final String TX = PublicNetworkTransactions.txId(PREVIEW_INDEFINITE_POOL_OWNERS);

    @Test
    void aPoolRegistrationWithIndefiniteOwnersAndRelaysDecodes() throws Exception {
        byte[] txCbor = PublicNetworkTransactions.cbor(PREVIEW_INDEFINITE_POOL_OWNERS);
        assertThatThrownBy(() -> Transaction.deserialize(txCbor)).isInstanceOf(CborDeserializationException.class);

        Transaction tx = CclTransactions.deserialize(txCbor);

        PoolRegistration registration = (PoolRegistration) tx.getBody().getCerts().get(0);
        assertThat(registration.getPoolOwners()).containsExactly("89218aeaab042f371399f159a08168b43a23f7c3b3db5c3a4c77a18e");
        assertThat(registration.getRelays()).hasSize(1);
        // The id and every hash still come from the original bytes.
        assertThat(RawTransaction.parse(txCbor, tx).txIdHex()).isEqualTo(TX);
        assertThat(TxIdentity.txIdHex(txCbor)).isEqualTo(TX);
    }

    @Test
    void bytesThatDoNotDecodeEvenDefiniteFailWithTheOriginalError() {
        // [{0: 1}, {}, true, null]: inputs must be a set of inputs, not an integer.
        byte[] bad = HexUtil.decodeHexString("84a10001a0f5f6");
        assertThatThrownBy(() -> CclTransactions.deserialize(bad)).isInstanceOf(CborDeserializationException.class);
    }

    @Test
    void setTagsCanBeDroppedFromTheBody() {
        // [{0: 258([[h'00..', 0]])}, {}, true, null] -> [{0: [[h'00..', 0]]}, {}, true, null]
        String input = "825820" + "00".repeat(32) + "00";
        byte[] tagged = HexUtil.decodeHexString("84a100d9010281" + input + "a0f5f6");
        assertThat(HexUtil.encodeHexString(DefiniteLengthCbor.normalizeBody(tagged, true)))
                .isEqualTo("84a10081" + input + "a0f5f6");
        assertThat(DefiniteLengthCbor.normalizeBody(tagged, false)).isEqualTo(tagged);
    }

    @Test
    void theCclCopyIsDefiniteInEveryItemAndTheBodyCopyOnlyInTheBody() {
        // [{0: [_ input]}, {1: [_ ]}, true, {1: [_ 1]}]: indefinite arrays in the body, the witness set and the metadata.
        String input = "825820" + "00".repeat(32) + "00";
        byte[] tx = HexUtil.decodeHexString("84a1009f" + input + "ffa1019fff" + "f5" + "a1019f01ff");
        assertThat(HexUtil.encodeHexString(DefiniteLengthCbor.normalizeTransaction(tx)))
                .isEqualTo("84a10081" + input + "a10180" + "f5" + "a1018101");
        assertThat(HexUtil.encodeHexString(DefiniteLengthCbor.normalizeBody(tx, false)))
                .isEqualTo("84a10081" + input + "a1019fff" + "f5" + "a1019f01ff");
    }
}
