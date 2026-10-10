package org.yanoproject.runtime.appchain;

import co.nstant.in.cbor.CborEncoder;
import co.nstant.in.cbor.model.UnicodeString;
import co.nstant.in.cbor.model.UnsignedInteger;
import com.bloxbean.cardano.yaci.core.model.Amount;
import com.bloxbean.cardano.yaci.core.model.AuxData;
import com.bloxbean.cardano.yaci.core.model.Block;
import com.bloxbean.cardano.yaci.core.model.TransactionBody;
import com.bloxbean.cardano.yaci.core.model.TransactionOutput;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import org.junit.jupiter.api.Test;
import org.yanoproject.api.appchain.l1view.L1Observation;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Cardano includes a transaction whose script fails, but takes only its collateral: its outputs are never created
 * and its metadata never takes effect. The built-in observers must report only the block's valid transactions.
 */
class InvalidL1TransactionObserverTest {
    private static final String WATCHED = "addr_test1qwatched";

    @Test
    void addressDepositObserverSkipsPhaseTwoInvalidTransactions() {
        AddressDepositObserver observer = new AddressDepositObserver("deposits", Map.of("address", WATCHED));
        Block block = Block.builder()
                .transactionBodies(List.of(payment("aa".repeat(32)), payment("bb".repeat(32))))
                .invalidTransactions(List.of(0))
                .build();

        assertThat(observer.observe(10, new byte[32], block))
                .singleElement()
                .satisfies(observation -> assertThat(HexUtil.encodeHexString(transactionHash(observation)))
                        .isEqualTo("bb".repeat(32)));
    }

    @Test
    void metadataLabelObserverSkipsPhaseTwoInvalidTransactions() throws Exception {
        MetadataLabelObserver observer = new MetadataLabelObserver("registry", Map.of("label", "7014"));
        String metadata = metadataCborHex(7014, "event");
        Block block = Block.builder()
                .transactionBodies(List.of(payment("aa".repeat(32)), payment("bb".repeat(32))))
                .auxiliaryDataMap(Map.of(
                        0, new AuxData(metadata, null, null, null, null, null),
                        1, new AuxData(metadata, null, null, null, null, null)))
                .invalidTransactions(List.of(0))
                .build();

        assertThat(observer.observe(10, new byte[32], block))
                .singleElement()
                .satisfies(observation -> assertThat(HexUtil.encodeHexString(transactionHash(observation)))
                        .isEqualTo("bb".repeat(32)));
    }

    private static byte[] transactionHash(L1Observation observation) {
        return ((L1Observation.TransactionAnchor) observation.anchor()).transactionHash();
    }

    private static TransactionBody payment(String txHash) {
        return TransactionBody.builder()
                .txHash(txHash)
                .outputs(List.of(TransactionOutput.builder()
                        .address(WATCHED)
                        .amounts(List.of(Amount.builder()
                                .unit("lovelace")
                                .quantity(BigInteger.valueOf(5_000_000L))
                                .build()))
                        .build()))
                .build();
    }

    private static String metadataCborHex(long label, String value) throws Exception {
        co.nstant.in.cbor.model.Map map = new co.nstant.in.cbor.model.Map();
        map.put(new UnsignedInteger(BigInteger.valueOf(label)), new UnicodeString(value));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        new CborEncoder(out).encode(map);
        return HexUtil.encodeHexString(out.toByteArray());
    }
}
