package com.bloxbean.cardano.yano.runtime.utxo;

import com.bloxbean.cardano.yaci.core.model.byron.ByronAddress;
import com.bloxbean.cardano.yaci.core.model.byron.ByronBlockBody;
import com.bloxbean.cardano.yaci.core.model.byron.ByronMainBlock;
import com.bloxbean.cardano.yaci.core.model.byron.ByronTx;
import com.bloxbean.cardano.yaci.core.model.byron.ByronTxIn;
import com.bloxbean.cardano.yaci.core.model.byron.ByronTxOut;
import com.bloxbean.cardano.yaci.core.model.byron.payload.ByronTxPayload;
import com.bloxbean.cardano.yano.api.events.ByronMainBlockAppliedEvent;
import com.bloxbean.cardano.yano.api.genesis.GenesisUtxos;
import com.bloxbean.cardano.yano.api.utxo.model.Outpoint;
import com.bloxbean.cardano.yano.runtime.chain.DirectRocksDBChainState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.rocksdb.RocksDB;
import org.slf4j.LoggerFactory;

import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Directional local measurement; correctness is asserted, performance is only reported. */
class ByronUtxoApplyMeasurementTest {
    private static final int BLOCKS = 2_000;
    private static final String GENESIS_ADDRESS =
            "Ae2tdPwUPEZ3DdaWu8jn553npu6jwEPAJiahruj3xQjPXxgoxfYDWusJz7x";
    private static final String OUTPUT_ADDRESS =
            "Ae2tdPwUPEZFRbyhz3cpfC2CumGzNkFBN2L42rcUc2yjQpEkxDbkPodpMAi";

    @TempDir Path directory;

    @Test
    void measuresNativeByronApplyThroughputAndRocksGrowth() throws Exception {
        Path dbPath = directory.resolve("db");
        try (DirectRocksDBChainState chain = new DirectRocksDBChainState(dbPath.toString())) {
            DefaultUtxoStore store = new DefaultUtxoStore(
                    chain, LoggerFactory.getLogger(getClass()), Map.of(
                    "yano.utxo.enabled", true,
                    "yano.metrics.enabled", false));
            try {
                store.storeByronGenesisUtxos(
                        Map.of(GENESIS_ADDRESS, BigInteger.valueOf(1_000_000L)),
                        0L, 0L, hash(0));
                ((RocksDB) chain.getDb()).flushWal(true);
                long baselineBytes = directorySize(dbPath);
                String spentTx = GenesisUtxos.byron(
                        GENESIS_ADDRESS, BigInteger.ONE, 0, 0, hash(0)).txHash();

                long started = System.nanoTime();
                for (int blockNumber = 1; blockNumber <= BLOCKS; blockNumber++) {
                    String createdTx = hash(blockNumber);
                    ByronTx transaction = ByronTx.builder()
                            .txHash(createdTx)
                            .inputs(List.of(ByronTxIn.builder().txId(spentTx).index(0).build()))
                            .outputs(List.of(ByronTxOut.builder()
                                    .address(ByronAddress.builder().base58Raw(OUTPUT_ADDRESS).build())
                                    .amount(BigInteger.valueOf(1_000_000L)).build()))
                            .build();
                    ByronMainBlock block = ByronMainBlock.builder()
                            .body(ByronBlockBody.builder()
                                    .txPayload(List.of(ByronTxPayload.builder()
                                            .transaction(transaction).witnesses(List.of()).build()))
                                    .build())
                            .build();
                    store.applyByronBlock(new ByronMainBlockAppliedEvent(
                            blockNumber, blockNumber, hash(BLOCKS + blockNumber), block));
                    spentTx = createdTx;
                }
                long elapsedNanos = System.nanoTime() - started;
                ((RocksDB) chain.getDb()).flushWal(true);
                long growthBytes = Math.max(0L, directorySize(dbPath) - baselineBytes);
                double blocksPerSecond = BLOCKS * 1_000_000_000.0 / elapsedNanos;

                System.out.printf(java.util.Locale.ROOT,
                        "ADR041_BYRON_MEASUREMENT blocks=%d elapsedMs=%.2f blocksPerSecond=%.2f "
                                + "rocksGrowthBytes=%d bytesPerBlock=%.2f%n",
                        BLOCKS, elapsedNanos / 1_000_000.0, blocksPerSecond,
                        growthBytes, growthBytes / (double) BLOCKS);
                assertThat(store.getLastAppliedBlock()).isEqualTo(BLOCKS);
                assertThat(store.getUtxo(new Outpoint(spentTx, 0))).isPresent();
                assertThat(store.computeTotalUtxoLovelace()).isEqualTo(BigInteger.valueOf(1_000_000L));
            } finally {
                store.close();
            }
        }
    }

    private static long directorySize(Path root) throws Exception {
        try (var paths = Files.walk(root)) {
            return paths.filter(Files::isRegularFile).mapToLong(path -> {
                try {
                    return Files.size(path);
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            }).sum();
        }
    }

    private static String hash(int value) {
        return String.format("%064x", value);
    }
}
