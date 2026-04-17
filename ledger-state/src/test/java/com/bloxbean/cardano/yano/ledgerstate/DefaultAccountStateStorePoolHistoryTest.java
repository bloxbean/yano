package com.bloxbean.cardano.yano.ledgerstate;

import com.bloxbean.cardano.yaci.core.model.Block;
import com.bloxbean.cardano.yaci.core.model.Era;
import com.bloxbean.cardano.yaci.core.model.PoolParams;
import com.bloxbean.cardano.yaci.core.model.TransactionBody;
import com.bloxbean.cardano.yaci.core.model.certs.Certificate;
import com.bloxbean.cardano.yaci.core.model.certs.PoolRegistration;
import com.bloxbean.cardano.yaci.core.model.certs.PoolRetirement;
import com.bloxbean.cardano.yano.api.events.BlockAppliedEvent;
import com.bloxbean.cardano.yano.ledgerstate.test.TestRocksDBHelper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import java.math.BigInteger;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class DefaultAccountStateStorePoolHistoryTest {

    private static final String POOL_HASH = "deadbeef00000000000000000000000000000000000000000000cafe";
    private static final long EPOCH_LENGTH = 432000L;

    @TempDir
    Path tempDir;

    @Test
    void poolReregistrationAfterRetirement_usesFreshLifecycleActivationEpoch() throws Exception {
        try (var rocks = TestRocksDBHelper.create(tempDir)) {
            var store = new DefaultAccountStateStore(
                    rocks.db(), rocks.cfSupplier(),
                    LoggerFactory.getLogger(DefaultAccountStateStorePoolHistoryTest.class),
                    true);

            applyBlockWithCerts(store, 1, epochStartSlot(10),
                    poolRegistration(BigInteger.valueOf(9_000_000_000L),
                            "e06714df177fec0312ca31c8b9aba46ddcf16961d61034ac207e7492e4",
                            Set.of(
                                    "3f8c946834753e4f20b32394e7eea558ade14b1e5a30c4565b5ce896",
                                    "6714df177fec0312ca31c8b9aba46ddcf16961d61034ac207e7492e4")));

            applyBlockWithCerts(store, 2, epochStartSlot(11),
                    PoolRetirement.builder().poolKeyHash(POOL_HASH).epoch(12).build());

            applyBlockWithCerts(store, 3, epochStartSlot(12),
                    poolRegistration(BigInteger.valueOf(5_000_000_000L),
                            "e06026674ced6bc94ff10deaffc3470d906a43d2522e6f25be796b8fbe",
                            Set.of("6026674ced6bc94ff10deaffc3470d906a43d2522e6f25be796b8fbe")));

            var epoch13 = store.getPoolParams(POOL_HASH, 13).orElseThrow();
            var epoch14 = store.getPoolParams(POOL_HASH, 14).orElseThrow();

            assertThat(epoch13.pledge()).isEqualTo(BigInteger.valueOf(9_000_000_000L));
            assertThat(epoch13.rewardAccount()).isEqualTo("e06714df177fec0312ca31c8b9aba46ddcf16961d61034ac207e7492e4");
            assertThat(epoch13.owners()).containsExactlyInAnyOrder(
                    "3f8c946834753e4f20b32394e7eea558ade14b1e5a30c4565b5ce896",
                    "6714df177fec0312ca31c8b9aba46ddcf16961d61034ac207e7492e4");

            assertThat(epoch14.pledge()).isEqualTo(BigInteger.valueOf(5_000_000_000L));
            assertThat(epoch14.rewardAccount()).isEqualTo("e06026674ced6bc94ff10deaffc3470d906a43d2522e6f25be796b8fbe");
            assertThat(epoch14.owners()).containsExactly("6026674ced6bc94ff10deaffc3470d906a43d2522e6f25be796b8fbe");
        }
    }

    private static PoolRegistration poolRegistration(BigInteger pledge, String rewardAccount, Set<String> owners) {
        return PoolRegistration.builder()
                .poolParams(PoolParams.builder()
                        .operator(POOL_HASH)
                        .pledge(pledge)
                        .cost(BigInteger.valueOf(340_000_000L))
                        .rewardAccount(rewardAccount)
                        .poolOwners(owners)
                        .build())
                .build();
    }

    private static void applyBlockWithCerts(DefaultAccountStateStore store, long blockNo, long slot,
                                            Certificate... certs) {
        var txs = new ArrayList<TransactionBody>();
        var tx = TransactionBody.builder()
                .certificates(new ArrayList<>(Arrays.asList(certs)))
                .build();
        txs.add(tx);

        Block block = Block.builder()
                .transactionBodies(txs)
                .build();

        store.applyBlock(new BlockAppliedEvent(Era.Conway, slot, blockNo, "hash" + blockNo, block));
    }

    private static long epochStartSlot(int epoch) {
        return epoch * EPOCH_LENGTH;
    }
}
