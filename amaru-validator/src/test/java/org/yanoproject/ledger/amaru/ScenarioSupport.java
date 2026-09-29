package org.yanoproject.ledger.amaru;

import org.yanoproject.ledger.amaru.AmaruNetworkParameters.EraBound;
import org.yanoproject.ledger.amaru.AmaruNetworkParameters.EraSummary;
import org.yanoproject.ledger.amaru.AmaruNetworkParameters.GlobalParameters;
import org.yanoproject.ledger.amaru.runtime.AmaruInstance;
import org.yanoproject.ledger.amaru.runtime.WasmAmaruInstance;
import org.yanoproject.ledger.amaru.wire.AmaruRequest;
import org.yanoproject.ledger.amaru.wire.AmaruRequest.ProposalKind;
import org.yanoproject.ledger.amaru.wire.ProtocolParamsEncoder;
import org.yanoproject.ledger.rules.TxValidationOutcome;
import org.yanoproject.ledger.rules.TxValidationRequest;
import org.yanoproject.ledger.rules.fixtures.amaru.AmaruScenario;
import org.yanoproject.ledger.rules.fixtures.amaru.AmaruScenario.CertPointer;
import org.yanoproject.ledger.rules.fixtures.amaru.AmaruScenario.LedgerConstants;
import org.yanoproject.ledger.rules.view.LedgerView;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/** Shared helpers: scenario → engine inputs, and one engine per distinct configuration. */
final class ScenarioSupport {

    static final int MAX_MEMORY_PAGES = AmaruEngineConfig.DEFAULT_MAX_MEMORY_PAGES;

    private static final Map<String, AmaruTransactionValidator> ENGINES = new ConcurrentHashMap<>();

    private ScenarioSupport() {
    }

    static Supplier<AmaruInstance> wasm() {
        return () -> new WasmAmaruInstance(MAX_MEMORY_PAGES);
    }

    static AmaruNetworkParameters network(AmaruScenario scenario) {
        return network(scenario.network());
    }

    static AmaruNetworkParameters network(AmaruScenario.Network n) {
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

    static AmaruLedgerConstants constants(AmaruScenario scenario) {
        LedgerConstants c = scenario.constants();
        return new AmaruLedgerConstants(c.maxRefScriptSizePerTx(), c.maxRefScriptSizePerBlock(),
                c.refScriptCostStride(), c.refScriptCostMultiplierNumerator(), c.refScriptCostMultiplierDenominator());
    }

    /** A shared FULL-mode engine for the scenario's network and constants. */
    static AmaruTransactionValidator engine(AmaruScenario scenario) {
        AmaruNetworkParameters network = network(scenario);
        AmaruLedgerConstants constants = constants(scenario);
        return ENGINES.computeIfAbsent(network + "|" + constants, key -> new AmaruTransactionValidator(
                AmaruEngineConfig.defaults(Phase2Mode.FULL, 2), network, null, constants, wasm()));
    }

    static TxValidationOutcome validate(AmaruTransactionValidator engine, AmaruScenario scenario,
                                        TxValidationRequest.Rule rule, TxValidationRequest.Origin origin) {
        return validate(engine, scenario, scenario.view(), rule, origin);
    }

    static TxValidationOutcome validate(AmaruTransactionValidator engine, AmaruScenario scenario, LedgerView view,
                                        TxValidationRequest.Rule rule, TxValidationRequest.Origin origin) {
        return engine.validate(new TxValidationRequest(scenario.txCbor(), view, scenario.env(), rule, origin, null));
    }

    /**
     * The request the Rust reference encoder builds for this scenario ({@code amaru_scenarios.rs},
     * {@code load_scenario}): state in fixture order, the fixture's certificate pointers and output bytes.
     */
    static AmaruRequest referenceRequest(AmaruScenario scenario, AmaruRequest.Mode mode) {
        AmaruScenario.State s = scenario.state();
        return new AmaruRequest(mode, scenario.txCbor(), network(scenario),
                ProtocolParamsEncoder.encode(scenario.protocolParams()), constants(scenario), s.dormantEpochs(),
                s.guardrailScriptHash(), s.roots(), s.treasury(), s.slot(), s.transactionIndex(),
                s.utxo().stream().map(u -> new AmaruRequest.Utxo(u.outpoint(), u.outputCbor())).toList(),
                s.accounts().stream().map(a -> new AmaruRequest.Account(a.credential(), a.deposit(), a.rewards(),
                        a.pool(), pointer(a.poolPointer()), a.drep(), pointer(a.drepPointer()))).toList(),
                s.pools(),
                s.dreps().stream().map(d -> new AmaruRequest.DRep(d.credential(), d.deposit(),
                        pointer(d.registeredAt()), d.validUntil())).toList(),
                s.committee().stream().map(m -> new AmaruRequest.CommitteeMember(m.cold(), m.hot(), m.resigned(),
                        m.validUntil())).toList(),
                s.proposals().stream().map(p -> new AmaruRequest.Proposal(p.id(), kind(p.kind()),
                        p.validUntil())).toList());
    }

    private static AmaruRequest.CertPointer pointer(CertPointer pointer) {
        return pointer == null ? null
                : new AmaruRequest.CertPointer(pointer.slot(), pointer.transactionIndex(), pointer.certificateIndex());
    }

    /** Amaru's {@code ProposalSlim::from_str} for the corpus lineage strings. */
    static ProposalKind kind(String kind) {
        return switch (kind) {
            case "ProtocolParameters" -> new ProposalKind.ParameterChange(false);
            case "ProtocolParameters(security-group)" -> new ProposalKind.ParameterChange(true);
            case "ConstitutionalCommittee" -> new ProposalKind.Committee();
            case "Constitution" -> new ProposalKind.Constitution();
            case "Orphan", "Information" -> new ProposalKind.Info();
            case "TreasuryWithdrawals" -> new ProposalKind.TreasuryWithdrawals();
            default -> {
                String[] version = kind.substring("HardFork(".length(), kind.length() - 1).split("\\.");
                yield new ProposalKind.HardFork(Long.parseLong(version[0]), Long.parseLong(version[1]));
            }
        };
    }
}
