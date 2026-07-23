package com.bloxbean.cardano.yano.runtime.appchain;

import com.bloxbean.cardano.client.crypto.Blake2bUtil;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import com.bloxbean.cardano.yano.api.appchain.AppBlock;
import com.bloxbean.cardano.yano.api.appchain.sink.FinalizedStreamSink;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ADR-006 review fix: a sink that names a legacy cursor key resumes from it on
 * first init (in-place upgrade keeps at-least-once) instead of jumping to tip.
 */
@Timeout(30)
class SinkCursorMigrationTest {

    @TempDir
    Path tempDir;

    private AppLedgerStore ledger;

    @AfterEach
    void tearDown() {
        if (ledger != null) ledger.close();
    }

    @Test
    void sinkRunner_migratesLegacyCursor() {
        ledger = new AppLedgerStore(tempDir.resolve("ledger").toString(),
                LoggerFactory.getLogger(SinkCursorMigrationTest.class));

        // Simulate a pre-upgrade webhook cursor persisted at height 5
        String url = "http://consumer.example/hook";
        String legacyKey = "webhook_cursor_" + HexUtil.encodeHexString(
                Blake2bUtil.blake2bHash224(url.getBytes(StandardCharsets.UTF_8)));
        ledger.metaPutLong(legacyKey, 5L);

        // A sink exposing that legacy key migrates it on first init
        FinalizedStreamSink sink = new FinalizedStreamSink() {
            @Override public String id() { return "webhook:" + url; }
            @Override public String legacyCursorKey() { return legacyKey; }
            @Override public boolean deliver(AppBlock block) { return true; }
        };
        SinkRunner runner = new SinkRunner(sink, sink.id(), ledger,
                LoggerFactory.getLogger(SinkCursorMigrationTest.class));
        SinkRunner.initializeCursors(ledger, List.of(runner));

        // Resumed from the legacy cursor (5), NOT reset to tip (0)
        assertThat(runner.cursor()).isEqualTo(5L);
    }

    @Test
    void sinkRunner_noLegacy_startsAtTip() {
        ledger = new AppLedgerStore(tempDir.resolve("ledger2").toString(),
                LoggerFactory.getLogger(SinkCursorMigrationTest.class));

        FinalizedStreamSink sink = new FinalizedStreamSink() {
            @Override public String id() { return "kafka:topic:chain"; }
            @Override public boolean deliver(AppBlock block) { return true; }
        };
        SinkRunner runner = new SinkRunner(sink, sink.id(), ledger,
                LoggerFactory.getLogger(SinkCursorMigrationTest.class));
        SinkRunner.initializeCursors(ledger, List.of(runner));

        assertThat(runner.cursor()).isEqualTo(ledger.tipHeight()); // 0 on empty ledger
    }
}
