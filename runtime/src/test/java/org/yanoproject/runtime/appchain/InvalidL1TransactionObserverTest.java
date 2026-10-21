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
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import org.yanoproject.api.appchain.l1view.L1Observation;
import org.yanoproject.runtime.plugins.PluginProviderRegistry;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Cardano includes a transaction whose script fails, but takes only its collateral: its outputs are never created
 * and its metadata never takes effect. The built-in observers must report only the block's valid transactions.
 */
class InvalidL1TransactionObserverTest {
    private static final String WATCHED = "addr_test1qwatched";
    private static final Map<String, String> DEPOSITS = Map.of(
            "observers.deposits.type", "address-deposit",
            "observers.deposits.address", WATCHED);
    private static final Map<String, String> REGISTRY = Map.of(
            "observers.registry.type", "metadata-label",
            "observers.registry.label", "7014");
    // Observer-profile digests of DEPOSITS and REGISTRY on L1 genesis 0x01..01 before the invalid-transaction
    // filter (claim schemas *-claim-v1), recomputed outside the code under test.
    private static final String PRE_FILTER_DEPOSITS_PROFILE =
            "ec763a2e7ea9e8a528dd6c9ca8cebbd2ec9d8d8906f66fd6a7752e0c328eadbc";
    private static final String PRE_FILTER_REGISTRY_PROFILE =
            "25954a41e24fcb4fb072a3ba06c4b5c3a2321f548ab6f4377bb9b2c3593b25d2";

    @TempDir
    Path tempDir;

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

    @Test
    void filteringBuiltInsHaveANewObserverProfile() {
        assertThat(HexUtil.encodeHexString(profile(DEPOSITS))).isNotEqualTo(PRE_FILTER_DEPOSITS_PROFILE);
        assertThat(HexUtil.encodeHexString(profile(REGISTRY))).isNotEqualTo(PRE_FILTER_REGISTRY_PROFILE);
    }

    @Test
    void journalHoldingAPreFilterObservationDoesNotReopenUnderTheNewProfile() {
        // What the pre-filter observer journaled for an invalid transaction: the same claim as for a valid one.
        L1Observation falseDeposit = new AddressDepositObserver("deposits", Map.of("address", WATCHED))
                .observe(10, new byte[32], Block.builder()
                        .transactionBodies(List.of(payment("aa".repeat(32))))
                        .build())
                .getFirst();
        try (AppLedgerStore ledger = store()) {
            new L1ObservationJournal(ledger, 1_000_000, HexUtil.decodeHexString(PRE_FILTER_DEPOSITS_PROFILE))
                    .observe(List.of(falseDeposit));
        }
        try (AppLedgerStore ledger = store()) {
            assertThatThrownBy(() -> new L1ObservationJournal(ledger, 1_000_000, profile(DEPOSITS)))
                    .hasMessageContaining("profile differs");
        }
    }

    private static byte[] profile(Map<String, String> settings) {
        byte[] l1Genesis = new byte[32];
        Arrays.fill(l1Genesis, (byte) 1);
        return L1ObservationService.consensusProfileDigest(settings, PluginProviderRegistry.empty(), l1Genesis);
    }

    private AppLedgerStore store() {
        return new AppLedgerStore(tempDir.resolve("ledger").toString(),
                LoggerFactory.getLogger(InvalidL1TransactionObserverTest.class), TestStateCommitments.MPF);
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
