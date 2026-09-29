package org.yanoproject.tx;

import com.bloxbean.cardano.client.common.model.SlotConfig;
import com.bloxbean.cardano.client.spec.NetworkId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yanoproject.api.utxo.model.Outpoint;
import org.yanoproject.ledger.rules.LedgerFailure;
import org.yanoproject.ledger.rules.LedgerRuleName;
import org.yanoproject.ledger.rules.LedgerValidationEngine;
import org.yanoproject.ledger.rules.TxValidationOutcome;
import org.yanoproject.ledger.rules.TxValidationRequest;
import org.yanoproject.ledger.rules.ValidationEnv;
import org.yanoproject.ledger.rules.shadow.ShadowDumpBundle;
import org.yanoproject.ledger.rules.shadow.ShadowDumpBundle.RecordedOutcome;
import org.yanoproject.ledger.rules.view.InMemoryLedgerView;
import org.yanoproject.ledger.rules.view.Lookup;
import org.yanoproject.ledger.rules.view.RecordingLedgerView;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** ADR-056 Phase 7a: replaying a shadow bundle through an engine. */
class ShadowBundleReplayTest {

    private static final Logger log = LoggerFactory.getLogger(ShadowBundleReplayTest.class);

    @TempDir
    Path dir;

    @Test
    void aRecordedFindingReproducesAgainstTheRecordedReads() {
        Outpoint missing = new Outpoint("bb".repeat(32), 0);
        LedgerValidationEngine engine = new LedgerValidationEngine() {
            @Override
            public String name() {
                return "java";
            }

            @Override
            public TxValidationOutcome validate(TxValidationRequest request) {
                return request.view().utxo(missing) instanceof Lookup.Absent
                        ? TxValidationOutcome.Invalid.of(new LedgerFailure(LedgerRuleName.UTXO, "BadInputsUTxO",
                        LedgerFailure.Phase.PHASE_1, "missing"))
                        : TxValidationOutcome.Invalid.of(LedgerFailure.ledgerStateUnavailable("not recorded"));
            }
        };
        RecordingLedgerView recording = new RecordingLedgerView(InMemoryLedgerView.builder().build());
        byte[] tx = {(byte) 0x84, (byte) 0xa0, (byte) 0xa0, (byte) 0xf5, (byte) 0xf6};
        ValidationEnv env = new ValidationEnv(10, 0, 10, 0, NetworkId.TESTNET, new SlotConfig(1000, 0, 0),
                new byte[32], 7);
        TxValidationOutcome outcome = engine.validate(new TxValidationRequest(tx, recording, env,
                TxValidationRequest.Rule.LEDGER, TxValidationRequest.Origin.SYNC, null));
        Path file = new ShadowDumpBundle("cd".repeat(32), tx, TxValidationRequest.Rule.LEDGER,
                TxValidationRequest.Origin.SYNC, env, new RecordedOutcome("chain", true, List.of()),
                RecordedOutcome.of("java", outcome), recording.reads()).write(dir);

        ShadowBundleReplay.Result result = ShadowBundleReplay.replay(file, engine);

        assertThat(result.reproduced()).isTrue();
        assertThat(result.agreesWithReference()).isFalse();
        assertThat(result.replayed().label()).isEqualTo("UTXO.BadInputsUTxO");
        assertThat(ShadowDumpBundle.read(file).env().forecastBasisSlot()).isEqualTo(7);
        assertThat(ShadowBundleReplay.bundles(dir)).containsExactly(file);
    }

    /**
     * Replays real bundles with the java engine: {@code ./gradlew :tx-services:test --tests '*ShadowBundleReplayTest'
     * -Dyano.shadow.bundles=/path/to/dump-dir}. Logs each verdict; fails when a finding no longer reproduces.
     */
    @Test
    @EnabledIfSystemProperty(named = "yano.shadow.bundles", matches = ".+")
    void replaysTheGivenBundlesWithTheJavaEngine() {
        LedgerValidationEngine engine = ShadowBundleReplay.javaEngine();
        List<Path> bundles = ShadowBundleReplay.bundles(Path.of(System.getProperty("yano.shadow.bundles")));
        assertThat(bundles).isNotEmpty();
        for (Path bundle : bundles) {
            ShadowBundleReplay.Result result = ShadowBundleReplay.replay(bundle, engine);
            log.info("REPLAY {}", result);
            if (result.outcome() instanceof TxValidationOutcome.Invalid invalid) {
                invalid.failures().forEach(f -> log.info("REPLAY     {}: {}", f.qualifiedName(), f.detail()));
            }
        }
    }
}
