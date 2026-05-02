package com.bloxbean.cardano.yano.runtime.migration;

import com.bloxbean.cardano.client.address.Address;
import com.bloxbean.cardano.yaci.core.model.Amount;
import com.bloxbean.cardano.yaci.core.model.Block;
import com.bloxbean.cardano.yaci.core.model.Era;
import com.bloxbean.cardano.yaci.core.model.TransactionBody;
import com.bloxbean.cardano.yaci.core.model.TransactionOutput;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import com.bloxbean.cardano.yano.api.events.BlockAppliedEvent;
import com.bloxbean.cardano.yano.runtime.chain.DirectRocksDBChainState;
import com.bloxbean.cardano.yano.runtime.utxo.DefaultUtxoStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import java.math.BigInteger;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.bloxbean.cardano.yaci.core.util.Constants.LOVELACE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StakeBalanceIndexStartupMigrationTest {
    private static final String BASE_ADDR_WITH_STAKE =
            "addr_test1qz2fxv2umyhttkxyxp8x0dlpdt3k6cwng5pxj3jhsydzer3jcu5d8ps7zex2k2xt3uqxgjqnnj83ws8lhrn648jjxtwq2ytjqp";

    @TempDir
    Path tempDir;

    private DirectRocksDBChainState chain;
    private final List<DefaultUtxoStore> stores = new ArrayList<>();

    @BeforeEach
    void setUp() {
        chain = new DirectRocksDBChainState(tempDir.toString());
    }

    @AfterEach
    void tearDown() throws Exception {
        for (DefaultUtxoStore store : stores) {
            store.close();
        }
        if (chain != null) {
            chain.close();
        }
    }

    @Test
    void startupMigrationPopulatesIndexWhenSupportIsEnabledAfterSync() {
        StakeCred stakeCred = stakeCred(BASE_ADDR_WITH_STAKE);

        DefaultUtxoStore disabledStore = newStore(false, false);
        assertFalse(disabledStore.isStakeBalanceIndexEnabled());
        applyOutputBlock(disabledStore, 100, 1, "41".repeat(32), "a1".repeat(32),
                BASE_ADDR_WITH_STAKE, 1_234);

        DefaultUtxoStore enabledBeforeMigration = newStore(true, false);
        assertTrue(enabledBeforeMigration.isStakeBalanceIndexEnabled());
        assertFalse(enabledBeforeMigration.isStakeBalanceIndexReady());
        assertEquals(Optional.empty(), enabledBeforeMigration.getUtxoBalanceByStakeCredential(
                stakeCred.credType(), stakeCred.credHash()));

        StartupMigrationContext context = context(true, false);
        StakeBalanceIndexStartupMigration migration = new StakeBalanceIndexStartupMigration();
        assertTrue(migration.shouldRun(context));

        new StartupMigrationRunner().run(context, List.of(migration));

        DefaultUtxoStore enabledAfterMigration = newStore(true, false);
        assertTrue(enabledAfterMigration.isStakeBalanceIndexReady());
        assertEquals(Optional.of(BigInteger.valueOf(1_234)),
                enabledAfterMigration.getUtxoBalanceByStakeCredential(stakeCred.credType(), stakeCred.credHash()));
        assertFalse(migration.shouldRun(context));
    }

    @Test
    void startupMigrationClearsReadyMarkerWhenUtxoFiltersAreEnabled() {
        StakeCred stakeCred = stakeCred(BASE_ADDR_WITH_STAKE);

        DefaultUtxoStore enabledStore = newStore(true, false);
        assertTrue(enabledStore.isStakeBalanceIndexReady());
        applyOutputBlock(enabledStore, 100, 1, "51".repeat(32), "b1".repeat(32),
                BASE_ADDR_WITH_STAKE, 1_000);
        assertEquals(Optional.of(BigInteger.valueOf(1_000)),
                enabledStore.getUtxoBalanceByStakeCredential(stakeCred.credType(), stakeCred.credHash()));

        StartupMigrationContext filteredContext = context(true, true);
        StakeBalanceIndexStartupMigration migration = new StakeBalanceIndexStartupMigration();
        assertTrue(migration.shouldRun(filteredContext));

        new StartupMigrationRunner().run(filteredContext, List.of(migration));

        DefaultUtxoStore filteredStore = newStore(true, true);
        assertFalse(filteredStore.isStakeBalanceIndexReady());
        assertEquals(Optional.empty(), filteredStore.getUtxoBalanceByStakeCredential(
                stakeCred.credType(), stakeCred.credHash()));

        DefaultUtxoStore unfilteredStore = newStore(true, false);
        assertFalse(unfilteredStore.isStakeBalanceIndexReady());
        assertEquals(Optional.empty(), unfilteredStore.getUtxoBalanceByStakeCredential(
                stakeCred.credType(), stakeCred.credHash()));
    }

    @Test
    void startupMigrationRunnerPropagatesRequiredMigrationFailure() {
        StartupMigration failingMigration = new StartupMigration() {
            @Override
            public String id() {
                return "failing";
            }

            @Override
            public boolean shouldRun(StartupMigrationContext context) {
                return true;
            }

            @Override
            public StartupMigrationResult run(StartupMigrationContext context) {
                throw new IllegalStateException("boom");
            }
        };

        assertThrows(IllegalStateException.class,
                () -> new StartupMigrationRunner().run(context(true, false), List.of(failingMigration)));
    }

    private StartupMigrationContext context(boolean stakeBalanceIndexEnabled, boolean utxoFiltersEnabled) {
        return new StartupMigrationContext(chain, config(stakeBalanceIndexEnabled, utxoFiltersEnabled), tempDir.toString());
    }

    private DefaultUtxoStore newStore(boolean stakeBalanceIndexEnabled, boolean utxoFiltersEnabled) {
        DefaultUtxoStore store = new DefaultUtxoStore(
                chain,
                LoggerFactory.getLogger(StakeBalanceIndexStartupMigrationTest.class),
                config(stakeBalanceIndexEnabled, utxoFiltersEnabled));
        stores.add(store);
        return store;
    }

    private Map<String, Object> config(boolean stakeBalanceIndexEnabled, boolean utxoFiltersEnabled) {
        Map<String, Object> cfg = new HashMap<>();
        cfg.put("yaci.node.utxo.enabled", true);
        cfg.put("yaci.node.utxo.pruneDepth", 3);
        cfg.put("yaci.node.utxo.rollbackWindow", 4);
        cfg.put("yaci.node.utxo.pruneBatchSize", 100);
        cfg.put("yaci.node.metrics.enabled", false);
        cfg.put("yaci.node.account.stake-balance-index-enabled", stakeBalanceIndexEnabled);
        cfg.put("yaci.node.filters.utxo.enabled", utxoFiltersEnabled);
        return cfg;
    }

    private void applyOutputBlock(DefaultUtxoStore store,
                                  long slot,
                                  long blockNo,
                                  String txHash,
                                  String blockHash,
                                  String address,
                                  long lovelace) {
        TransactionBody tx = TransactionBody.builder()
                .txHash(txHash)
                .outputs(List.of(TransactionOutput.builder()
                        .address(address)
                        .amounts(List.of(lovelaceAmount(lovelace)))
                        .build()))
                .build();
        Block block = Block.builder()
                .era(Era.Babbage)
                .transactionBodies(List.of(tx))
                .invalidTransactions(Collections.emptyList())
                .build();
        store.applyBlock(new BlockAppliedEvent(Era.Babbage, slot, blockNo, blockHash, block));
    }

    private Amount lovelaceAmount(long lovelace) {
        return Amount.builder()
                .unit(LOVELACE)
                .quantity(BigInteger.valueOf(lovelace))
                .build();
    }

    private static StakeCred stakeCred(String address) {
        Address parsed = new Address(address);
        byte[] stakeHash = parsed.getDelegationCredentialHash().orElseThrow();
        int typeNibble = ((parsed.getBytes()[0] & 0xFF) >> 4) & 0x0F;
        int credType = switch (typeNibble) {
            case 0, 1 -> 0;
            case 2, 3 -> 1;
            default -> throw new IllegalArgumentException("Address does not contain a stake credential: " + address);
        };
        return new StakeCred(credType, HexUtil.encodeHexString(stakeHash));
    }

    private record StakeCred(int credType, String credHash) {}
}
