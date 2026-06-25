package com.bloxbean.cardano.yano.app.e2e.haskellsync;

import com.bloxbean.cardano.yano.testkit.external.HaskellCardanoNodeProcess;
import com.bloxbean.cardano.yano.testkit.external.YanoAppProcess;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Scenario 1: Regular block-producer mode.
 * Starts Yano devnet producing blocks, connects a Haskell cardano-node,
 * and verifies sync stays in lock-step across an epoch boundary.
 *
 * Devnet pv10 config: epochLength=1200, slotLength=0.2s.
 */
public class RegularBPSyncTest extends HaskellSyncTestBase {

    private static final Logger log = LoggerFactory.getLogger(RegularBPSyncTest.class);

    @Test
    void haskellSyncsInRegularBPMode() throws Exception {
        // 1. Start Yano in regular devnet mode
        yaci = new YanoAppProcess(tempDir, uberJarPath);
        yaci.start();
        yaci.waitForReady(60_000);

        // 2. Wait for some blocks to be produced
        Thread.sleep(3000);

        var tip = yaci.getTip();
        log.info("Yano tip after startup: slot={}, block={}", tip.get("slot"), tip.get("blockNumber"));
        assertTrue(tip.get("slot").asLong() > 0, "Yano should have produced blocks");

        // 3. Copy genesis files and start Haskell node
        haskell = new HaskellCardanoNodeProcess(tempDir);
        yaci.copyGenesisTo(haskell.getGenesisDir());
        haskell.start(yaci.getN2nPort());

        // 4. Wait for Haskell to sync to current Yano tip
        long currentSlot = tip.get("slot").asLong();
        haskell.waitForChainExtended(currentSlot, 60_000);
        log.info("Haskell node synced to slot {}", currentSlot);

        // 5. Wait through the first epoch boundary.
        long epochBoundarySlot = 1200;
        long epochBoundaryTimeoutMs = 300_000; // 5 minutes to be safe
        log.info("Waiting for Haskell node to reach slot {} (epoch boundary)...", epochBoundarySlot);
        haskell.waitForChainExtended(epochBoundarySlot, epochBoundaryTimeoutMs);

        // 6. Assert still in sync
        assertTipsSynced(10);
        log.info("Regular BP sync test passed — Haskell node in sync across epoch boundary");
    }
}
