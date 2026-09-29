package org.yanoproject.ledger.conformance.engines;

import org.yanoproject.ledger.amaru.AmaruEngineConfig;
import org.yanoproject.ledger.amaru.AmaruLedgerConstants;
import org.yanoproject.ledger.amaru.AmaruNetworkParameters;
import org.yanoproject.ledger.amaru.AmaruNetworkParameters.EraBound;
import org.yanoproject.ledger.amaru.AmaruNetworkParameters.EraSummary;
import org.yanoproject.ledger.amaru.AmaruNetworkParameters.GlobalParameters;
import org.yanoproject.ledger.amaru.AmaruTransactionValidator;
import org.yanoproject.ledger.amaru.Phase2Mode;
import org.yanoproject.ledger.amaru.runtime.WasmAmaruInstance;
import org.yanoproject.ledger.conformance.ConformanceSettings;
import org.yanoproject.ledger.conformance.runner.ConformanceCase;
import org.yanoproject.ledger.conformance.runner.ConformanceEngine;
import org.yanoproject.ledger.conformance.runner.Observation;
import org.yanoproject.ledger.rules.TxValidationRequest;
import org.yanoproject.ledger.rules.fixtures.amaru.AmaruScenario;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The reference engine: {@link AmaruTransactionValidator} in {@code full} mode (Amaru runs the scripts too) on
 * the Endive AOT module, one engine per network and constants, as the ADR-057 Phase B gate builds it. Only in
 * {@code -PwithAmaru=true} builds.
 */
public final class AmaruReferenceEngine implements ConformanceEngine {

    private static final Map<String, AmaruTransactionValidator> ENGINES = new ConcurrentHashMap<>();
    /** The module's {@code amaru_version()} text, read (and checked) once. */
    private static volatile String moduleVersion;

    /** @throws IllegalStateException when the module is stale ({@link #verifyModule(String)}) */
    public AmaruReferenceEngine() {
        if (moduleVersion == null) {
            try (WasmAmaruInstance instance = new WasmAmaruInstance(AmaruEngineConfig.DEFAULT_MAX_MEMORY_PAGES)) {
                String version = instance.amaruVersion();
                verifyModule(version);
                moduleVersion = version;
            }
        }
    }

    @Override
    public String name() {
        return BaselineEngines.AMARU;
    }

    @Override
    public String description() {
        return "AmaruTransactionValidator, phase2 = full, Endive AOT (reference; ADR-057); module crate "
                + ConformanceSettings.amaruCrateVersion().orElse("?") + ", sha256 "
                + ConformanceSettings.amaruWasmSha256().orElse("?");
    }

    @Override
    public Observation validate(ConformanceCase testCase) {
        AmaruNetworkParameters network = network(testCase.network());
        AmaruLedgerConstants constants = constants(testCase.constants());
        AmaruTransactionValidator engine = ENGINES.computeIfAbsent(network + "|" + constants,
                key -> new AmaruTransactionValidator(AmaruEngineConfig.defaults(Phase2Mode.FULL, 2), network, null,
                        constants, () -> new WasmAmaruInstance(AmaruEngineConfig.DEFAULT_MAX_MEMORY_PAGES)));
        return Observation.of(engine.validate(new TxValidationRequest(testCase.txCbor(), testCase.view(),
                testCase.env(), TxValidationRequest.Rule.LEDGER, TxValidationRequest.Origin.SYNC, null)));
    }

    /**
     * Refuses a stale module: the {@code crate=} line of {@code amaru_version()} must be the version in
     * {@code amaru-validator-wasm/Cargo.toml}, which is bumped whenever the failure mapping changes. A module built
     * before such a change would otherwise skew every baseline and gate silently (it did: a module without the
     * Phase 3a {@code OutsideForecast} alias scored 274 instead of 275).
     */
    static void verifyModule(String amaruVersion) {
        String expected = ConformanceSettings.amaruCrateVersion().orElseThrow(() -> new IllegalStateException(
                "amaru.wasm.crateVersion is not set: run through ledger-conformance/build.gradle with -PwithAmaru=true"));
        String reported = crateVersion(amaruVersion);
        if (!expected.equals(reported)) {
            throw new IllegalStateException("The Amaru module is stale: it reports crate=" + reported
                    + " but amaru-validator-wasm/Cargo.toml is " + expected + " (module "
                    + ConformanceSettings.amaruWasmFile().map(Object::toString).orElse("?") + "). Rebuild it with "
                    + "amaru-validator-wasm/scripts/build-wasm.sh and pass its absolute path as -PamaruWasm.");
        }
    }

    /** @return the value of the {@code crate=} line of an {@code amaru_version()} text, or {@code none} */
    static String crateVersion(String amaruVersion) {
        return amaruVersion.lines().map(String::strip).filter(l -> l.startsWith("crate=")).map(l -> l.substring(6))
                .findFirst().orElse("none");
    }

    private static AmaruNetworkParameters network(AmaruScenario.Network n) {
        List<EraSummary> eras = n.eras().stream()
                .map(e -> new EraSummary(bound(e.start()), e.end() == null ? null : bound(e.end()),
                        e.epochSizeSlots(), e.slotLengthMs(), e.eraTag()))
                .toList();
        AmaruScenario.GlobalParameters g = n.globalParameters();
        return new AmaruNetworkParameters(n.networkMagic(), n.stabilityWindow(), eras,
                new GlobalParameters(g.securityParam(), g.epochLengthScaleFactor(), g.activeSlotCoeffInverse(),
                        g.maxLovelaceSupply(), g.slotsPerKesPeriod(), g.maxKesEvolution(), g.systemStartMs()));
    }

    private static EraBound bound(AmaruScenario.EraBound b) {
        return new EraBound(b.timeMs(), b.slot(), b.epoch());
    }

    private static AmaruLedgerConstants constants(AmaruScenario.LedgerConstants c) {
        return new AmaruLedgerConstants(c.maxRefScriptSizePerTx(), c.maxRefScriptSizePerBlock(),
                c.refScriptCostStride(), c.refScriptCostMultiplierNumerator(), c.refScriptCostMultiplierDenominator());
    }
}
