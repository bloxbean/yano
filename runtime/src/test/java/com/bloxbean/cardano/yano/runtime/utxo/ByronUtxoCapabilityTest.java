package com.bloxbean.cardano.yano.runtime.utxo;

import com.bloxbean.cardano.yaci.core.model.Block;
import com.bloxbean.cardano.yaci.core.model.Era;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import com.bloxbean.cardano.yaci.core.util.CborSerializationUtil;
import com.bloxbean.cardano.yaci.core.model.serializers.ByronBlockSerializer;
import com.bloxbean.cardano.yano.api.events.BlockAppliedEvent;
import com.bloxbean.cardano.yano.api.genesis.GenesisUtxos;
import com.bloxbean.cardano.yano.api.utxo.model.Outpoint;
import com.bloxbean.cardano.yano.runtime.chain.DirectRocksDBChainState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import org.rocksdb.ColumnFamilyHandle;
import org.rocksdb.RocksDB;

import java.math.BigInteger;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ByronUtxoCapabilityTest {
    private static final String AVVM =
            "Ae2tdPwUPEZ3DdaWu8jn553npu6jwEPAJiahruj3xQjPXxgoxfYDWusJz7x";
    private static final String NON_AVVM =
            "Ae2tdPwUPEZFRbyhz3cpfC2CumGzNkFBN2L42rcUc2yjQpEkxDbkPodpMAi";

    @TempDir Path directory;
    private DirectRocksDBChainState chain;
    private DefaultUtxoStore store;

    @BeforeEach
    void setUp() {
        chain = new DirectRocksDBChainState(directory.resolve("db").toString());
        store = new DefaultUtxoStore(chain, LoggerFactory.getLogger(getClass()), Map.of(
                "yano.utxo.enabled", true,
                "yano.metrics.enabled", false));
        store.wireAllegraBootstrapRemoval(chain);
    }

    @AfterEach
    void tearDown() {
        store.close();
        chain.close();
    }

    @Test
    void freshAtomicInitializationWritesMarkerEvenForEmptyDistributions() {
        store.initializeFreshFullStateGenesis(
                Map.of(), 42L, Map.of(), Map.of(), 0L, 0L, hex(0));

        assertThat(store.hasByronMainApplyCapability()).isTrue();
        assertThat(chain.getByronGenesisUtxoKeys()).isEmpty();
    }

    @Test
    void persistsOnlyAvvmKeysAndAllegraRemovalLeavesNonAvvmOutput() {
        store.initializeFreshFullStateGenesis(
                Map.of(), 764824073L,
                Map.of(NON_AVVM, BigInteger.valueOf(2_000_000L)),
                Map.of(AVVM, BigInteger.valueOf(1_000_000L)),
                0L, 0L, hex(0));

        String avvmTx = GenesisUtxos.byron(AVVM, BigInteger.ONE, 0, 0, hex(0)).txHash();
        String nonAvvmTx = GenesisUtxos.byron(NON_AVVM, BigInteger.ONE, 0, 0, hex(0)).txHash();
        assertThat(chain.getByronGenesisUtxoKeys())
                .singleElement()
                .satisfies(key -> assertThat(UtxoKeyUtil.txHashFromOutpointKey(key)).isEqualTo(avvmTx));

        Block allegra = Block.builder().era(Era.Allegra)
                .transactionBodies(List.of()).invalidTransactions(List.of()).build();
        store.applyBlock(new BlockAppliedEvent(Era.Allegra, 100L, 1L, hex(1), allegra));

        assertThat(store.getUtxo(new Outpoint(avvmTx, 0))).isEmpty();
        assertThat(store.getUtxo(new Outpoint(nonAvvmTx, 0))).isPresent();
    }

    @Test
    void existingChainWithoutMarkerIsRefusedEvenWithoutUtxoCursor() {
        byte[] hash = HexUtil.decodeHexString(hex(2));
        chain.storeBlockHeader(hash, 1L, 10L, new byte[]{1});
        chain.storeBlock(hash, 1L, 10L, new byte[]{1});

        assertThatThrownBy(() -> store.requireByronMainApplyCapability(chain, false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no Byron-main UTXO capability marker")
                .hasMessageContaining("rebuild UTXO state");
    }

    @Test
    void unmarkedSnapshotIsRefusedEvenWhenRestoredChainIsEmpty() {
        assertThatThrownBy(() -> store.requireByronMainApplyCapability(chain, true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("restored snapshot")
                .hasMessageContaining("no Byron-main UTXO capability marker");
    }

    @Test
    void reconcileTreatsValidatedGenesisEbbAsHavingNoUtxoTransition() {
        store.initializeFreshFullStateGenesis(
                Map.of(), 764824073L, Map.of(), Map.of(), 0L, 0L, hex(0));
        byte[] hash = HexUtil.decodeHexString(hex(3));
        byte[] ebb = genesisEbbCbor();
        chain.storeByronEbHeader(hash, 0L, 0L, ebb);
        chain.storeBlock(hash, 0L, 0L, ebb);

        store.reconcile(chain);

        assertThat(store.getLatestAppliedSlot()).isEqualTo(-1L);
        assertThat(store.getLastAppliedBlock()).isZero();
    }

    @Test
    void explicitRebuildSeedsBothByronClassesAndRecapturesShelleyBoundaryTotal() {
        byte[] byronCbor = byronMainSpendCbor();
        var byron = ByronBlockSerializer.INSTANCE.deserialize(byronCbor);
        byte[] byronHash = HexUtil.decodeHexString(byron.getHeader().getBlockHash());
        chain.storeBlockHeader(byronHash, 1L, 1L, byronCbor);
        chain.storeBlock(byronHash, 1L, 1L, byronCbor);
        storeShelleyBlock(2L, 21_600L, hex(4));
        store.setShelleyStartBoundaryCapture(() -> {
            if (chain.getShelleyStartUtxoTotal().isEmpty()) {
                chain.setShelleyStartUtxoTotal(store.computeTotalUtxoLovelace());
            }
        });

        store.rebuildFullStateFromGenesis(chain, Map.of(), 764824073L,
                Map.of(NON_AVVM, BigInteger.valueOf(2_000_000L)),
                Map.of(AVVM, BigInteger.valueOf(1_000_000L)));

        assertThat(store.hasByronMainApplyCapability()).isTrue();
        assertThat(store.getLastAppliedBlock()).isEqualTo(2L);
        assertThat(store.getUtxosByAddress(NON_AVVM, 1, 10)).hasSize(2);
        String avvmTx = GenesisUtxos.byron(AVVM, BigInteger.ONE, 0, 0, hex(0)).txHash();
        assertThat(store.getUtxosByAddress(AVVM, 1, 10)).isEmpty();
        assertThat(store.getUtxoSpentOrUnspent(new Outpoint(avvmTx, 0))).isPresent();
        assertThat(chain.getShelleyStartUtxoTotal()).contains(BigInteger.valueOf(2_900_000L));
        assertThat(store.computeTotalUtxoLovelace()).isEqualTo(BigInteger.valueOf(2_900_000L));
        assertThat(chain.getByronGenesisUtxoKeys()).hasSize(1);
    }

    @Test
    void explicitRebuildRefusesWhenCanonicalBodyWasPruned() throws Exception {
        byte[] hash = HexUtil.decodeHexString(hex(5));
        storeShelleyBlock(1L, 10L, hex(5));
        ((RocksDB) chain.getDb()).delete(
                (ColumnFamilyHandle) chain.getColumnFamilyHandle("blocks"), hash);

        assertThatThrownBy(() -> store.rebuildFullStateFromGenesis(
                chain, Map.of(), 764824073L, Map.of(), Map.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("full resync")
                .hasRootCauseMessage("UTXO reconcile missing local block body for block 1");
    }

    @Test
    void reconcileNeverReclassifiesAByronMainApplyFailureAsAnEbb() {
        store.initializeFreshFullStateGenesis(
                Map.of(), 764824073L, Map.of(), Map.of(), 0L, 0L, hex(0));
        byte[] byronCbor = byronMainSpendCbor();
        var byron = ByronBlockSerializer.INSTANCE.deserialize(byronCbor);
        byte[] blockHash = HexUtil.decodeHexString(byron.getHeader().getBlockHash());
        chain.storeBlockHeader(blockHash, 1L, 1L, byronCbor);
        chain.storeBlock(blockHash, 1L, 1L, byronCbor);

        assertThatThrownBy(() -> store.reconcile(chain))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Byron UTXO apply failed for block 1")
                .hasStackTraceContaining("Unresolved Byron input");
        assertThat(store.getLastAppliedBlock()).isZero();
    }

    private void storeShelleyBlock(long blockNumber, long slot, String hashHex) {
        byte[] hash = HexUtil.decodeHexString(hashHex);
        byte[] cbor = emptyBabbageBlockCbor(blockNumber, slot);
        chain.storeBlockHeader(hash, blockNumber, slot, cbor);
        chain.storeBlock(hash, blockNumber, slot, cbor);
    }

    private static byte[] emptyBabbageBlockCbor(long blockNumber, long slot) {
        var headerBody = new co.nstant.in.cbor.model.Array();
        headerBody.add(new co.nstant.in.cbor.model.UnsignedInteger(blockNumber));
        headerBody.add(new co.nstant.in.cbor.model.UnsignedInteger(slot));
        headerBody.add(co.nstant.in.cbor.model.SimpleValue.NULL);
        headerBody.add(new co.nstant.in.cbor.model.ByteString(new byte[32]));
        headerBody.add(new co.nstant.in.cbor.model.ByteString(new byte[32]));
        var vrf = new co.nstant.in.cbor.model.Array();
        vrf.add(new co.nstant.in.cbor.model.ByteString(new byte[32]));
        vrf.add(new co.nstant.in.cbor.model.ByteString(new byte[64]));
        headerBody.add(vrf);
        headerBody.add(new co.nstant.in.cbor.model.UnsignedInteger(0));
        headerBody.add(new co.nstant.in.cbor.model.ByteString(new byte[32]));
        var opCert = new co.nstant.in.cbor.model.Array();
        opCert.add(new co.nstant.in.cbor.model.ByteString(new byte[32]));
        opCert.add(new co.nstant.in.cbor.model.UnsignedInteger(0));
        opCert.add(new co.nstant.in.cbor.model.UnsignedInteger(0));
        opCert.add(new co.nstant.in.cbor.model.ByteString(new byte[64]));
        headerBody.add(opCert);
        var protocolVersion = new co.nstant.in.cbor.model.Array();
        protocolVersion.add(new co.nstant.in.cbor.model.UnsignedInteger(7));
        protocolVersion.add(new co.nstant.in.cbor.model.UnsignedInteger(0));
        headerBody.add(protocolVersion);

        var header = new co.nstant.in.cbor.model.Array();
        header.add(headerBody);
        header.add(new co.nstant.in.cbor.model.ByteString(new byte[64]));
        var block = new co.nstant.in.cbor.model.Array();
        block.add(header);
        block.add(new co.nstant.in.cbor.model.Array());
        block.add(new co.nstant.in.cbor.model.Array());
        block.add(new co.nstant.in.cbor.model.Map());
        block.add(new co.nstant.in.cbor.model.Array());
        var outer = new co.nstant.in.cbor.model.Array();
        outer.add(new co.nstant.in.cbor.model.UnsignedInteger(Era.Babbage.getValue()));
        outer.add(block);
        return CborSerializationUtil.serialize(outer, true);
    }

    private static byte[] genesisEbbCbor() {
        var consensus = new co.nstant.in.cbor.model.Array();
        consensus.add(new co.nstant.in.cbor.model.UnsignedInteger(0));
        var difficulty = new co.nstant.in.cbor.model.Array();
        difficulty.add(new co.nstant.in.cbor.model.UnsignedInteger(0));
        consensus.add(difficulty);

        var header = new co.nstant.in.cbor.model.Array();
        header.add(new co.nstant.in.cbor.model.UnsignedInteger(764824073L));
        header.add(new co.nstant.in.cbor.model.ByteString(new byte[32]));
        header.add(new co.nstant.in.cbor.model.Array());
        header.add(consensus);
        header.add(new co.nstant.in.cbor.model.Array());

        var block = new co.nstant.in.cbor.model.Array();
        block.add(header);
        block.add(new co.nstant.in.cbor.model.Array());
        var outer = new co.nstant.in.cbor.model.Array();
        outer.add(new co.nstant.in.cbor.model.UnsignedInteger(0));
        outer.add(block);
        return CborSerializationUtil.serialize(outer, true);
    }

    /** A ledger-balanced miniature Byron block: spend 1 ADA, create 0.9 ADA, fee 0.1 ADA. */
    private static byte[] byronMainSpendCbor() {
        String genesisTx = GenesisUtxos.byron(AVVM, BigInteger.ONE, 0, 0, hex(0)).txHash();
        var actualInput = new co.nstant.in.cbor.model.Array();
        actualInput.add(new co.nstant.in.cbor.model.ByteString(HexUtil.decodeHexString(genesisTx)));
        actualInput.add(new co.nstant.in.cbor.model.UnsignedInteger(0));
        var input = new co.nstant.in.cbor.model.Array();
        input.add(new co.nstant.in.cbor.model.UnsignedInteger(0));
        input.add(new co.nstant.in.cbor.model.ByteString(
                CborSerializationUtil.serialize(actualInput, true)));
        var inputs = new co.nstant.in.cbor.model.Array();
        inputs.add(input);

        var output = new co.nstant.in.cbor.model.Array();
        output.add(CborSerializationUtil.deserializeOne(
                com.bloxbean.cardano.client.crypto.Base58.decode(NON_AVVM)));
        output.add(new co.nstant.in.cbor.model.UnsignedInteger(900_000L));
        var outputs = new co.nstant.in.cbor.model.Array();
        outputs.add(output);
        var transaction = new co.nstant.in.cbor.model.Array();
        transaction.add(inputs);
        transaction.add(outputs);
        transaction.add(new co.nstant.in.cbor.model.Map());
        var payload = new co.nstant.in.cbor.model.Array();
        payload.add(transaction);
        payload.add(new co.nstant.in.cbor.model.Array());
        var transactions = new co.nstant.in.cbor.model.Array();
        transactions.add(payload);

        var txProof = new co.nstant.in.cbor.model.Array();
        txProof.add(new co.nstant.in.cbor.model.UnsignedInteger(1));
        txProof.add(new co.nstant.in.cbor.model.ByteString(new byte[32]));
        txProof.add(new co.nstant.in.cbor.model.ByteString(new byte[32]));
        var sscProof = new co.nstant.in.cbor.model.Array();
        sscProof.add(new co.nstant.in.cbor.model.UnsignedInteger(3));
        sscProof.add(new co.nstant.in.cbor.model.ByteString(new byte[32]));
        var bodyProof = new co.nstant.in.cbor.model.Array();
        bodyProof.add(txProof);
        bodyProof.add(sscProof);
        bodyProof.add(new co.nstant.in.cbor.model.ByteString(new byte[32]));
        bodyProof.add(new co.nstant.in.cbor.model.ByteString(new byte[32]));

        var slotId = new co.nstant.in.cbor.model.Array();
        slotId.add(new co.nstant.in.cbor.model.UnsignedInteger(0));
        slotId.add(new co.nstant.in.cbor.model.UnsignedInteger(1));
        var difficulty = new co.nstant.in.cbor.model.Array();
        difficulty.add(new co.nstant.in.cbor.model.UnsignedInteger(1));
        var signaturePayload = new co.nstant.in.cbor.model.Array();
        signaturePayload.add(new co.nstant.in.cbor.model.ByteString(new byte[64]));
        var signature = new co.nstant.in.cbor.model.Array();
        signature.add(new co.nstant.in.cbor.model.UnsignedInteger(0));
        signature.add(signaturePayload);
        var consensus = new co.nstant.in.cbor.model.Array();
        consensus.add(slotId);
        consensus.add(new co.nstant.in.cbor.model.ByteString(new byte[32]));
        consensus.add(difficulty);
        consensus.add(signature);

        var blockVersion = new co.nstant.in.cbor.model.Array();
        blockVersion.add(new co.nstant.in.cbor.model.UnsignedInteger(0));
        blockVersion.add(new co.nstant.in.cbor.model.UnsignedInteger(0));
        blockVersion.add(new co.nstant.in.cbor.model.UnsignedInteger(0));
        var softwareVersion = new co.nstant.in.cbor.model.Array();
        softwareVersion.add(new co.nstant.in.cbor.model.UnicodeString("yano-test"));
        softwareVersion.add(new co.nstant.in.cbor.model.UnsignedInteger(1));
        var extraData = new co.nstant.in.cbor.model.Array();
        extraData.add(blockVersion);
        extraData.add(softwareVersion);
        extraData.add(new co.nstant.in.cbor.model.Map());
        extraData.add(new co.nstant.in.cbor.model.ByteString(new byte[32]));
        var header = new co.nstant.in.cbor.model.Array();
        header.add(new co.nstant.in.cbor.model.UnsignedInteger(764824073L));
        header.add(new co.nstant.in.cbor.model.ByteString(new byte[32]));
        header.add(bodyProof);
        header.add(consensus);
        header.add(extraData);

        var sscPayload = new co.nstant.in.cbor.model.Array();
        sscPayload.add(new co.nstant.in.cbor.model.UnsignedInteger(3));
        sscPayload.add(new co.nstant.in.cbor.model.Array());
        var updatePayload = new co.nstant.in.cbor.model.Array();
        updatePayload.add(new co.nstant.in.cbor.model.Array());
        updatePayload.add(new co.nstant.in.cbor.model.Array());
        var body = new co.nstant.in.cbor.model.Array();
        body.add(transactions);
        body.add(sscPayload);
        body.add(new co.nstant.in.cbor.model.Array());
        body.add(updatePayload);
        var block = new co.nstant.in.cbor.model.Array();
        block.add(header);
        block.add(body);
        block.add(new co.nstant.in.cbor.model.Array());
        var outer = new co.nstant.in.cbor.model.Array();
        outer.add(new co.nstant.in.cbor.model.UnsignedInteger(1));
        outer.add(block);
        return CborSerializationUtil.serialize(outer, true);
    }

    private static String hex(int value) {
        return String.format("%02x", value).repeat(32);
    }
}
