package org.yanoproject.ledger.conformance.engines;

import com.bloxbean.cardano.client.api.model.ProtocolParams;
import com.bloxbean.cardano.client.spec.NetworkId;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.yanoproject.api.utxo.model.Outpoint;
import org.yanoproject.api.util.EpochSlotCalc;
import org.yanoproject.ledger.conformance.ConformanceSettings;
import org.yanoproject.ledger.conformance.runner.ConformanceCase;
import org.yanoproject.ledger.conformance.runner.ConformanceEngine;
import org.yanoproject.ledger.conformance.runner.Observation;
import org.yanoproject.ledger.conformance.runner.ScenarioCases;
import org.yanoproject.ledger.rules.LedgerValidationEngine;
import org.yanoproject.ledger.rules.NetworkParameters;
import org.yanoproject.ledger.rules.TxValidationRequest;
import org.yanoproject.ledger.rules.conway.JavaLedgerValidationEngine;
import org.yanoproject.ledger.rules.fixtures.PublicNetworkTransactions;
import org.yanoproject.ledger.rules.phase2.ForecastHorizon;
import org.yanoproject.ledger.rules.view.LedgerView;
import org.yanoproject.ledger.rules.view.Lookup;
import org.yanoproject.ledger.rules.view.model.AccountState;
import org.yanoproject.ledger.rules.view.model.CommitteeMemberState;
import org.yanoproject.ledger.rules.view.model.CredentialKey;
import org.yanoproject.ledger.rules.view.model.DRepState;
import org.yanoproject.ledger.rules.view.model.EnactedRoots;
import org.yanoproject.ledger.rules.view.model.GovActionId;
import org.yanoproject.ledger.rules.view.model.PoolId;
import org.yanoproject.ledger.rules.view.model.PoolState;
import org.yanoproject.ledger.rules.view.model.ProposalState;
import org.yanoproject.ledger.rules.view.model.UtxoEntry;
import org.yanoproject.ledger.scripteval.phase2.JulcScriptPhaseEvaluator;
import org.yanoproject.scalusbridge.ScalusScriptPhaseEvaluator;

import java.math.BigInteger;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ADR-057 Phase E latency benchmark: warm per-transaction validation latency of {@code java-julc}, {@code java-scalus}
 * and (in {@code -PwithAmaru=true} builds) {@code amaru} on the same fixed corpus, the Amaru scenarios at protocol
 * version 10 and later (the one PV 9 scenario is left out: Amaru refuses it without validating), and a second corpus
 * of real preprod and preview transactions: the replay bundles of {@link PublicNetworkTransactions} (each transaction
 * with the ledger state it was validated against, {@code ShadowDumpBundle.replayRequest()}). That corpus is small and
 * biased toward unusual Plutus transactions (each was once an engine finding): a real-network Plutus-heavy sample, not
 * typical traffic. On that corpus {@code amaru} runs twice, with {@code phase2 = full} (the oracle mode) and with
 * {@code phase2 = scalus} (the node default), and only on the PV 10 and later bundles, over
 * {@link WholeSlicesView}: a bundle holds the Java engine's reads, and Amaru's request also ships whole slices the Java
 * engine did not read for these transactions.
 *
 * <p>Each engine is created once per network and ledger constants and reused, as the node reuses its engines, so a
 * sample is one {@code validate} call: rule {@code LEDGER}, origin {@code SYNC}, the case's in-memory view. The Java
 * engines run on the calling thread; {@code amaru} hands each call to its instance pool's worker thread, as in the
 * node, with {@code phase2 = full} (Amaru runs the scripts) unless stated. Warm-up passes are not sampled; the first
 * of them is reported as the cold pass.</p>
 *
 * <pre>
 * ./gradlew :ledger-conformance:test --tests '*EngineLatencyBenchmarkTest' -PengineBenchmark=true \
 *     -PamaruScenariosDir=&lt;amaru clone at the pinned tag&gt; -PwithAmaru=true -PamaruWasm=&lt;module.wasm&gt;
 * </pre>
 *
 * <p>{@code -PengineBenchmarkPasses} (default 5) and {@code -PengineBenchmarkWarmup} (default 2) set the passes. The
 * public-network corpus runs {@code -PengineBenchmarkNetworkRepeat} (default 20) times as many passes of each, as it
 * has only a few transactions. The table goes to stdout and to {@code build/conformance/engine-latency.md}.</p>
 */
@Tag("benchmark")
@EnabledIfSystemProperty(named = "yano.engine.benchmark", matches = "true")
class EngineLatencyBenchmarkTest {

    private static final int FIRST_AMARU_MAJOR = 10;
    /** {@code amaru} with {@code phase2 = scalus}, the node default: Amaru judges phase 1, Scalus the scripts. */
    private static final String AMARU_SCALUS = "amaru (phase2 scalus)";

    /** Preprod genesis (Shelley and Byron): 4 Byron epochs of 21,600 slots at 20 s, then 432,000-slot epochs. */
    static final NetworkParameters PREPROD = new NetworkParameters(1, NetworkId.TESTNET, 2160, 0.05,
            new BigInteger("45000000000000000"), 129_600, 62, 1_654_041_600_000L, 21_600, 20_000, 86_400, 432_000,
            1000);
    /** Preview genesis: no Byron period, 86,400-slot epochs. */
    static final NetworkParameters PREVIEW = new NetworkParameters(2, NetworkId.TESTNET, 432, 0.05,
            new BigInteger("45000000000000000"), 129_600, 62, 1_666_656_000_000L, 4_320, 20_000, 0, 86_400, 1000);

    @Test
    void warmLatencyPerEngine() {
        List<ConformanceCase> cases = ScenarioCases.cases().orElse(List.of()).stream()
                .filter(c -> c.env().protocolMajor() >= FIRST_AMARU_MAJOR)
                .toList();
        int warmup = Math.max(1, Integer.getInteger("yano.engine.benchmark.warmup", 2));
        int passes = Math.max(1, Integer.getInteger("yano.engine.benchmark.passes", 5));
        int repeat = Math.max(1, Integer.getInteger("yano.engine.benchmark.networkRepeat", 20));

        List<ConformanceEngine> engines = new ArrayList<>(List.of(
                new ReusedJavaEngine("java-julc", h -> JavaViewEngine.create(new JulcScriptPhaseEvaluator(h))),
                new ReusedJavaEngine("java-scalus",
                        h -> JavaViewEngine.createScalus(new ScalusScriptPhaseEvaluator(h)))));
        BaselineEngines.amaru().ifPresent(engines::add);

        Map<String, List<TxValidationRequest>> network = new LinkedHashMap<>();
        Stream.concat(PublicNetworkTransactions.PHASE2_CASES.stream().map(PublicNetworkTransactions.Phase2Case::name),
                        Stream.of(PublicNetworkTransactions.PREPROD_INDEFINITE_ASSET_MAP_OUTPUT))
                .sorted()
                .forEach(name -> network.computeIfAbsent(name.substring(0, name.indexOf('-')), k -> new ArrayList<>())
                        .add(PublicNetworkTransactions.bundle(name).replayRequest()));

        StringBuilder table = new StringBuilder()
                .append("# Engine latency (ADR-057 Phase E)\n\n")
                .append(String.format(Locale.ROOT, "%s %s, %s %s, %d processors, max heap %d MiB. Scenarios: %d "
                                + "cases (Amaru scenarios, PV >= 10), %d warm-up + %d sampled passes. Preprod and "
                                + "preview: the public-network replay bundles (real-network Plutus-heavy sample), "
                                + "%d warm-up + %d sampled passes; `amaru` runs the PV >= 10 bundles only, and "
                                + "`amaru (phase2 scalus)` is the node default.%n%n",
                        System.getProperty("java.vm.name"), System.getProperty("java.version"),
                        System.getProperty("os.name"), System.getProperty("os.arch"),
                        Runtime.getRuntime().availableProcessors(), Runtime.getRuntime().maxMemory() >> 20,
                        cases.size(), warmup, passes, warmup * repeat, passes * repeat))
                .append("| Corpus | Engine | cases | samples | cold pass mean ms | mean ms | p50 ms | p90 ms | p99 ms "
                        + "| max ms | valid | crashes |\n")
                .append("|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|\n");
        for (ConformanceEngine engine : cases.isEmpty() ? List.<ConformanceEngine>of() : engines) {
            table.append(measure("scenarios", engine.name(),
                    cases.stream().<Callable<Observation>>map(c -> () -> engine.validate(c)).toList(),
                    warmup, passes));
        }
        network.forEach((name, requests) -> {
            NetworkParameters parameters = name.equals("preprod") ? PREPROD : PREVIEW;
            ForecastHorizon horizon = ForecastHorizon.of(parameters::stabilityWindow, new EpochSlotCalc(
                    parameters.epochLength(), parameters.byronEpochLength(), parameters.firstNonByronSlot()));
            Map<String, LedgerValidationEngine> networkEngines = new LinkedHashMap<>();
            networkEngines.put("java-julc", JavaViewEngine.create(new JulcScriptPhaseEvaluator(horizon)));
            networkEngines.put("java-scalus", JavaViewEngine.createScalus(new ScalusScriptPhaseEvaluator(horizon)));
            BaselineEngines.amaru(parameters, null)
                    .ifPresent(amaru -> networkEngines.put(BaselineEngines.AMARU, amaru));
            BaselineEngines.amaru(parameters, new ScalusScriptPhaseEvaluator(horizon))
                    .ifPresent(amaru -> networkEngines.put(AMARU_SCALUS, amaru));
            networkEngines.forEach((engineName, engine) -> table.append(measure(name, engineName, requests.stream()
                    .filter(r -> !engineName.startsWith(BaselineEngines.AMARU)
                            || r.env().protocolMajor() >= FIRST_AMARU_MAJOR)
                    .map(r -> !engineName.startsWith(BaselineEngines.AMARU) ? r : new TxValidationRequest(r.txCbor(),
                            new WholeSlicesView(r.view()), r.env(), r.rule(), r.origin(), null))
                    .<Callable<Observation>>map(r -> () -> Observation.of(engine.validate(r)))
                    .toList(), warmup * repeat, passes * repeat)));
        });
        String report = table.toString();
        System.out.println(report);
        Path written = ConformanceSettings.write("engine-latency.md", "engine.benchmark.file", report);
        System.out.println("Written to " + written.toAbsolutePath());
    }

    private static String measure(String corpus, String engine, List<Callable<Observation>> calls, int warmup,
                                  int passes) {
        double coldMs = 0;
        for (int pass = 0; pass < warmup; pass++) {
            long started = System.nanoTime();
            for (Callable<Observation> call : calls) {
                validate(call);
            }
            if (pass == 0) {
                coldMs = (System.nanoTime() - started) / 1e6 / calls.size();
            }
        }
        long[] nanos = new long[calls.size() * passes];
        int n = 0;
        int valid = 0;
        int crashes = 0;
        for (int pass = 0; pass < passes; pass++) {
            for (Callable<Observation> call : calls) {
                long start = System.nanoTime();
                Observation observation = validate(call);
                nanos[n++] = System.nanoTime() - start;
                if (pass == 0) {
                    valid += observation.valid() ? 1 : 0;
                    crashes += observation.failures().stream()
                            .anyMatch(f -> Observation.CRASH.equals(f.rule())) ? 1 : 0;
                }
            }
        }
        assertThat(n).isPositive();
        Arrays.sort(nanos);
        double mean = Arrays.stream(nanos).average().orElse(0) / 1e6;
        return String.format(Locale.ROOT,
                "| %s | `%s` | %d | %d | %.3f | %.3f | %.3f | %.3f | %.3f | %.3f | %d/%d | %d |%n",
                corpus, engine, calls.size(), nanos.length, coldMs, mean, percentile(nanos, 0.50),
                percentile(nanos, 0.90), percentile(nanos, 0.99), nanos[nanos.length - 1] / 1e6, valid, calls.size(),
                crashes);
    }

    private static Observation validate(Callable<Observation> call) {
        try {
            return call.call();
        } catch (Exception | LinkageError | StackOverflowError e) {
            return Observation.crash(e);
        }
    }

    /** Nearest-rank percentile of sorted samples, in milliseconds. */
    private static double percentile(long[] sorted, double p) {
        int rank = (int) Math.ceil(p * sorted.length);
        return sorted[Math.max(0, Math.min(sorted.length - 1, rank - 1))] / 1e6;
    }

    /**
     * A Java engine created once per network and constants ({@link JavaViewEngine} creates one per case, which the
     * baseline report's timing includes).
     */
    private static final class ReusedJavaEngine implements ConformanceEngine {

        private final String name;
        private final Function<ForecastHorizon, JavaLedgerValidationEngine> factory;
        private final Map<String, JavaLedgerValidationEngine> engines = new ConcurrentHashMap<>();

        ReusedJavaEngine(String name, Function<ForecastHorizon, JavaLedgerValidationEngine> factory) {
            this.name = name;
            this.factory = factory;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public String description() {
            return name + ", reused per network and constants";
        }

        @Override
        public Observation validate(ConformanceCase testCase) {
            JavaLedgerValidationEngine engine = engines.computeIfAbsent(
                    testCase.network() + "|" + testCase.constants(), key -> {
                        ForecastHorizon horizon = ForecastHorizon.of(testCase.network()::stabilityWindow,
                                CaseTiming.geometry(testCase.network().eras()));
                        JavaLedgerValidationEngine created = factory.apply(horizon);
                        return testCase.constants().isNone() ? created
                                : created.withConstants(JavaViewEngine.constants(testCase.constants()));
                    });
            return Observation.of(engine.validate(new TxValidationRequest(testCase.txCbor(), testCase.view(),
                    testCase.env(), TxValidationRequest.Rule.LEDGER, TxValidationRequest.Origin.SYNC, null)));
        }
    }

    /**
     * A replay view for {@code amaru}: Amaru always ships the committee, its candidates, the proposals, the guardrail,
     * the enacted roots, the treasury and the dormant-epoch count, and a bundle records them only when the Java engine
     * read them. When not recorded they are answered as none (no committee, candidates or proposals, no guardrail, no
     * enacted roots, treasury 0, 0 dormant epochs). The transactions of this corpus do not depend on them, and the
     * benchmark's valid count shows the verdicts are the chain's.
     */
    private record WholeSlicesView(LedgerView base) implements LedgerView {

        private static <T> Lookup<T> orElse(Lookup<T> recorded, T none) {
            return recorded instanceof Lookup.Unavailable<T> ? Lookup.present(none) : recorded;
        }

        @Override
        public Lookup<List<CommitteeMemberState>> committeeMembers() {
            return orElse(base.committeeMembers(), List.of());
        }

        @Override
        public Lookup<Set<CredentialKey>> committeeCandidates() {
            return orElse(base.committeeCandidates(), Set.of());
        }

        @Override
        public Lookup<List<ProposalState>> activeProposals() {
            return orElse(base.activeProposals(), List.of());
        }

        @Override
        public Lookup<EnactedRoots> enactedRoots() {
            return orElse(base.enactedRoots(), EnactedRoots.NONE);
        }

        @Override
        public Lookup<String> guardrailScriptHash() {
            Lookup<String> recorded = base.guardrailScriptHash();
            return recorded instanceof Lookup.Unavailable<String> ? Lookup.absent() : recorded;
        }

        @Override
        public Lookup<Long> dormantEpochs() {
            return orElse(base.dormantEpochs(), 0L);
        }

        @Override
        public Lookup<BigInteger> treasury() {
            return orElse(base.treasury(), BigInteger.ZERO);
        }

        @Override
        public Lookup<UtxoEntry> utxo(Outpoint outpoint) {
            return base.utxo(outpoint);
        }

        @Override
        public Lookup<AccountState> account(CredentialKey credential) {
            return base.account(credential);
        }

        @Override
        public Lookup<PoolState> pool(PoolId poolId) {
            return base.pool(poolId);
        }

        @Override
        public Lookup<PoolId> poolByVrfKeyHash(String vrfKeyHashHex) {
            return base.poolByVrfKeyHash(vrfKeyHashHex);
        }

        @Override
        public Lookup<DRepState> drep(CredentialKey credential) {
            return base.drep(credential);
        }

        @Override
        public Lookup<CommitteeMemberState> committeeMemberByCold(CredentialKey cold) {
            return base.committeeMemberByCold(cold);
        }

        @Override
        public Lookup<List<CommitteeMemberState>> committeeMembersByHot(CredentialKey hot) {
            return base.committeeMembersByHot(hot);
        }

        @Override
        public Lookup<ProposalState> proposal(GovActionId id) {
            return base.proposal(id);
        }

        @Override
        public Lookup<ProtocolParams> protocolParams() {
            return base.protocolParams();
        }
    }
}
