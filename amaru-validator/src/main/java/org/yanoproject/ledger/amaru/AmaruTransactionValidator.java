package org.yanoproject.ledger.amaru;

import com.bloxbean.cardano.client.api.model.ProtocolParams;
import com.bloxbean.cardano.client.transaction.spec.Transaction;
import com.bloxbean.cardano.client.transaction.spec.TransactionWitnessSet;
import com.bloxbean.cardano.client.transaction.spec.governance.actions.HardForkInitiationAction;
import com.bloxbean.cardano.client.transaction.spec.governance.actions.GovActionType;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yanoproject.api.utxo.model.Outpoint;
import org.yanoproject.ledger.amaru.runtime.AmaruEngineException;
import org.yanoproject.ledger.amaru.runtime.AmaruInstance;
import org.yanoproject.ledger.amaru.runtime.AmaruInstancePool;
import org.yanoproject.ledger.amaru.runtime.WasmAmaruInstance;
import org.yanoproject.ledger.amaru.wire.AmaruRequest;
import org.yanoproject.ledger.amaru.wire.AmaruRequest.CommitteeMember;
import org.yanoproject.ledger.amaru.wire.AmaruRequest.ProposalKind;
import org.yanoproject.ledger.amaru.wire.AmaruRequestEncoder;
import org.yanoproject.ledger.amaru.wire.AmaruResponse;
import org.yanoproject.ledger.amaru.wire.ProtocolParamsEncoder;
import org.yanoproject.ledger.amaru.wire.RequiredKeys;
import org.yanoproject.ledger.amaru.wire.UtxoOutputEncoder;
import org.yanoproject.ledger.rules.LedgerFailure;
import org.yanoproject.ledger.rules.LedgerRuleName;
import org.yanoproject.ledger.rules.LedgerValidationEngine;
import org.yanoproject.ledger.rules.TxIdentity;
import org.yanoproject.ledger.rules.TxValidationOutcome;
import org.yanoproject.ledger.rules.TxValidationRequest;
import org.yanoproject.ledger.rules.ValidatedTx;
import org.yanoproject.ledger.rules.ValidationEnv;
import org.yanoproject.ledger.rules.conway.mempool.MempoolRule;
import org.yanoproject.ledger.rules.effects.TxEffects;
import org.yanoproject.ledger.rules.effects.TxEffectsDeriver;
import org.yanoproject.ledger.rules.phase2.ScriptPhaseEvaluator;
import org.yanoproject.ledger.rules.phase2.ScriptPhaseResult;
import org.yanoproject.ledger.rules.view.LedgerStateUnavailableException;
import org.yanoproject.ledger.rules.view.LedgerView;
import org.yanoproject.ledger.rules.view.Lookup;
import org.yanoproject.ledger.rules.view.model.AccountState;
import org.yanoproject.ledger.rules.view.model.CommitteeMemberState;
import org.yanoproject.ledger.rules.view.model.CredentialKey;
import org.yanoproject.ledger.rules.view.model.DRepState;
import org.yanoproject.ledger.rules.view.model.EnactedRoots;
import org.yanoproject.ledger.rules.view.model.PoolId;
import org.yanoproject.ledger.rules.view.model.ProposalState;
import org.yanoproject.ledger.rules.view.model.UtxoEntry;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

/**
 * The {@code amaru} admission engine (ADR-057): Pragma's Amaru Conway rules, compiled to WebAssembly
 * ({@code amaru-validator-wasm}, interface v1) and run in the JVM by Endive's build-time AOT code.
 *
 * <p><b>Request flow</b> (ADR-057 §2), for one {@link TxValidationRequest}:</p>
 * <ol start="0">
 *   <li>Protocol version below 10 → {@code ENGINE.EraNotSupported} (invariant 6). For rule
 *       {@code MEMPOOL}, the Java {@link MempoolRule} against the incoming state: all spending inputs
 *       spent → only {@code LEDGER.ConwayMempoolFailure}; unelected committee voters (PV ≤ 10) →
 *       {@code ConwayMempoolFailure} and {@code LEDGER} still runs, as in Haskell.</li>
 *   <li>{@code required_keys}: the keys Amaru's {@code prepare_transaction} needs.</li>
 *   <li>Each key is resolved through the request's {@link LedgerView}: Present → sent; Absent → left
 *       out (how Amaru itself represents absence); Unavailable → {@code ENGINE.LedgerStateUnavailable}
 *       without calling {@code validate} (invariant 3). The full committee (members and candidates) and
 *       all active proposals are always sent, with the enacted roots, treasury, dormant epochs,
 *       guardrail script, protocol parameters, era history and global parameters.</li>
 *   <li>{@code validate} in {@code full} mode, or in {@code phase_one} mode followed by the
 *       {@link ScriptPhaseEvaluator} ({@link Phase2Mode#SCALUS}).</li>
 *   <li>The response's rule, constructor and phase are already Haskell-named by the module.</li>
 *   <li>On a valid verdict, effects come from {@link TxEffectsDeriver} for the phase-2 verdict, and the
 *       ADR-056 origin policy rejects {@code is_valid = false} from every origin except {@code SYNC}
 *       ({@code ENGINE.Phase2InvalidTxNotSupported}).</li>
 * </ol>
 *
 * <p><b>Failure handling.</b> A trap, a timeout, an undecodable response or a module {@code error} rejects
 * the transaction with {@code ENGINE.AmaruEngineFailure} and the instance is discarded (invariant 4).
 * After {@code max-abandoned} stuck calls the engine is unhealthy: {@link #isHealthy()} turns false and
 * every call fails closed with {@code ENGINE.AmaruEngineUnhealthy}. A transaction the module cannot
 * decode is {@code ENGINE.DecodingFailure} (Haskell never sees such a transaction: it is rejected when
 * the node deserialises it).</p>
 *
 * <p>Slices are sent in a canonical order (inputs by id and index, credentials by type then hash, pools
 * and proposals by id), so a request depends only on the ledger state, not on how a view orders it.</p>
 */
public final class AmaruTransactionValidator implements LedgerValidationEngine, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(AmaruTransactionValidator.class);

    /** Engine name in configuration and metrics ({@code yano.validation.engine=amaru}). */
    public static final String NAME = "amaru";
    /** The interface version this host speaks (INTERFACE.md, invariant 5). */
    public static final int SUPPORTED_ABI_VERSION = AmaruRequestEncoder.ABI_VERSION;

    /** Engine constructor: a trap, timeout, undecodable response or module error (invariant 4). */
    public static final String AMARU_ENGINE_FAILURE = "AmaruEngineFailure";
    /** Engine constructor: the abandoned-call cap was reached; every call fails closed until restart. */
    public static final String AMARU_ENGINE_UNHEALTHY = "AmaruEngineUnhealthy";
    /** Engine constructor: the transaction does not decode as a Conway transaction. */
    public static final String DECODING_FAILURE = "DecodingFailure";

    private static final int MIN_PROTOCOL_MAJOR = 10;

    private static final Comparator<CredentialKey> CREDENTIAL_ORDER =
            Comparator.comparing((CredentialKey c) -> c.type().tag()).thenComparing(CredentialKey::hashHex);
    private static final Comparator<Outpoint> OUTPOINT_ORDER =
            Comparator.comparing(Outpoint::txHash).thenComparingInt(Outpoint::index);

    private final AmaruEngineConfig config;
    private final Supplier<AmaruNetworkParameters> networkSupplier;
    private volatile AmaruNetworkParameters network;
    private final ScriptPhaseEvaluator phase2Evaluator;
    private final AmaruLedgerConstants ledgerConstants;
    private final AmaruInstancePool pool;
    private final String amaruVersion;
    private final TxEffectsDeriver effectsDeriver = new TxEffectsDeriver();

    /**
     * @param config          engine settings
     * @param network         magic, era history and global parameters of the running network
     * @param phase2Evaluator the phase-2 engine for {@link Phase2Mode#SCALUS}; may be null, in which case
     *                        a transaction with redeemers fails closed in that mode
     * @throws IllegalStateException when the module's {@code abi_version} is not supported
     */
    public AmaruTransactionValidator(AmaruEngineConfig config, AmaruNetworkParameters network,
                                     ScriptPhaseEvaluator phase2Evaluator) {
        this(config, network, phase2Evaluator, AmaruLedgerConstants.HASKELL,
                () -> new WasmAmaruInstance(config.maxMemoryPages()));
    }

    /**
     * For a node whose network facts may be known only after startup (a devnet resolves its system start
     * late): {@code network} is called on the first validation and its result kept. Until it succeeds every
     * request fails closed with {@code ENGINE.AmaruEngineFailure}.
     */
    public AmaruTransactionValidator(AmaruEngineConfig config, Supplier<AmaruNetworkParameters> network,
                                     ScriptPhaseEvaluator phase2Evaluator) {
        this(config, network, phase2Evaluator, AmaruLedgerConstants.HASKELL,
                () -> new WasmAmaruInstance(config.maxMemoryPages()));
    }

    /**
     * Full constructor, for tests and oracle runs.
     *
     * @param ledgerConstants test-only overrides of Haskell's hardcoded constants
     *                        ({@link AmaruLedgerConstants#HASKELL} in production)
     * @param instances       creates module instances (on the worker thread that will own each)
     */
    public AmaruTransactionValidator(AmaruEngineConfig config, AmaruNetworkParameters network,
                                     ScriptPhaseEvaluator phase2Evaluator, AmaruLedgerConstants ledgerConstants,
                                     Supplier<? extends AmaruInstance> instances) {
        this(config, fixed(Objects.requireNonNull(network, "network")), phase2Evaluator, ledgerConstants, instances);
    }

    private static Supplier<AmaruNetworkParameters> fixed(AmaruNetworkParameters network) {
        return () -> network;
    }

    /** Full constructor with a late-resolved network (see the three-argument supplier constructor). */
    public AmaruTransactionValidator(AmaruEngineConfig config, Supplier<AmaruNetworkParameters> network,
                                     ScriptPhaseEvaluator phase2Evaluator, AmaruLedgerConstants ledgerConstants,
                                     Supplier<? extends AmaruInstance> instances) {
        this.config = Objects.requireNonNull(config, "config");
        this.networkSupplier = Objects.requireNonNull(network, "network");
        this.phase2Evaluator = phase2Evaluator;
        this.ledgerConstants = Objects.requireNonNull(ledgerConstants, "ledgerConstants");
        Objects.requireNonNull(instances, "instances");
        try (AmaruInstance probe = instances.get()) {
            int abi = probe.abiVersion();
            if (abi != SUPPORTED_ABI_VERSION) {
                throw new IllegalStateException("amaru_validator.wasm implements interface version " + abi
                        + "; this Yano build supports version " + SUPPORTED_ABI_VERSION);
            }
            this.amaruVersion = probe.amaruVersion().strip();
        }
        this.pool = new AmaruInstancePool(instances, config.poolSize(), config.timeout(), config.maxAbandoned(),
                config.workerStackSize());
        log.info("Amaru validator ready: {} instance(s), phase2={}, timeout={} ms, max-abandoned={}, "
                        + "max-memory-pages={}; module {}", config.poolSize(), config.phase2(),
                config.timeout().toMillis(), config.maxAbandoned(), config.maxMemoryPages(),
                amaruVersion.replace('\n', ' '));
        if (config.phase2() == Phase2Mode.SCALUS && phase2Evaluator == null) {
            log.warn("Amaru validator runs phase2=scalus without a ScriptPhaseEvaluator: transactions with "
                    + "redeemers are rejected (fail closed)");
        }
    }

    @Override
    public String name() {
        return NAME;
    }

    /** @return the network facts, resolved once from the supplier */
    AmaruNetworkParameters network() {
        AmaruNetworkParameters resolved = network;
        if (resolved == null) {
            try {
                resolved = Objects.requireNonNull(networkSupplier.get(), "network parameters");
            } catch (RuntimeException e) {
                throw new AmaruEngineException(AmaruEngineException.Kind.BAD_RESPONSE,
                        "the network parameters are not known yet: " + e.getMessage(), e);
            }
            network = resolved;
        }
        return resolved;
    }

    /** @return false once {@code max-abandoned} stuck calls were reached; stays false until restart */
    @Override
    public boolean isHealthy() {
        return pool.isHealthy();
    }

    /** @return the module's {@code AMARU_VERSION} text (tag, commit, toolchain) */
    public String amaruVersion() {
        return amaruVersion;
    }

    /** @return calls abandoned so far */
    public int abandonedCount() {
        return pool.abandonedCount();
    }

    @Override
    public TxValidationOutcome validate(TxValidationRequest request) {
        try {
            return run(request);
        } catch (LedgerStateUnavailableException e) {
            return TxValidationOutcome.Invalid.of(LedgerFailure.ledgerStateUnavailable(e.getMessage()));
        } catch (AmaruEngineException e) {
            if (e.kind() == AmaruEngineException.Kind.UNHEALTHY) {
                return engine(AMARU_ENGINE_UNHEALTHY, e.getMessage());
            }
            return engine(AMARU_ENGINE_FAILURE, e.getMessage());
        } catch (RuntimeException e) {
            log.warn("Amaru validation failed closed", e);
            return engine(AMARU_ENGINE_FAILURE, e.toString());
        }
    }

    private TxValidationOutcome run(TxValidationRequest request) {
        if (!pool.isHealthy()) {
            return engine(AMARU_ENGINE_UNHEALTHY, "the Amaru engine is unhealthy (max-abandoned "
                    + config.maxAbandoned() + " reached); restart the node");
        }
        byte[] txCbor = request.txCbor();
        ValidationEnv env = request.env();
        LedgerView view = request.view();
        ProtocolParams params = view.protocolParams().require("protocol parameters");
        Integer paramsMajor = params.getProtocolMajorVer();
        if (paramsMajor == null || params.getProtocolMinorVer() == null) {
            return engine(AMARU_ENGINE_FAILURE, "protocol parameters carry no protocol version");
        }
        if (env.protocolMajor() < MIN_PROTOCOL_MAJOR || paramsMajor < MIN_PROTOCOL_MAJOR) {
            return TxValidationOutcome.Invalid.of(LedgerFailure.eraNotSupported(
                    "protocol version " + paramsMajor + "." + params.getProtocolMinorVer()
                            + " (Amaru validates Conway from protocol version 10)"));
        }

        Transaction tx = decodeWithCcl(txCbor);
        List<LedgerFailure> failures = new ArrayList<>();
        if (request.rule() == TxValidationRequest.Rule.MEMPOOL && tx != null) {
            MempoolRule.Result mempool = MempoolRule.apply(tx.getBody(), view, paramsMajor);
            if (!mempool.continueToLedger()) {
                return new TxValidationOutcome.Invalid(mempool.failures());
            }
            failures.addAll(mempool.failures());
        }

        // Encoded per call: CCL ProtocolParams is a mutable bean, so an identity cache could go stale.
        byte[] protocolParameters = ProtocolParamsEncoder.encode(params);
        byte[] keysEnv = AmaruRequestEncoder.keysEnv(paramsMajor, params.getProtocolMinorVer());
        RequiredKeys.Result keysResult = decodeKeys(pool.call(instance -> instance.requiredKeys(txCbor, keysEnv)));
        AmaruRequest.Mode mode = config.phase2() == Phase2Mode.FULL ? AmaruRequest.Mode.FULL
                : AmaruRequest.Mode.PHASE_ONE;

        if (keysResult instanceof RequiredKeys.Error error) {
            // The transaction does not decode (or the env is malformed). validate names the decoding
            // failure precisely; it needs no state for that.
            AmaruResponse response = callValidate(emptyRequest(mode, txCbor, protocolParameters, env));
            if (response instanceof AmaruResponse.Invalid invalid && invalid.phase() == 0) {
                failures.add(toLedgerFailure(invalid));
                return new TxValidationOutcome.Invalid(failures);
            }
            return engine(AMARU_ENGINE_FAILURE, "required_keys: " + error.message());
        }
        RequiredKeys keys = ((RequiredKeys.Keys) keysResult).keys();

        Map<Outpoint, UtxoEntry> resolvedInputs = new LinkedHashMap<>();
        Resolution resolution = resolve(keys, view, resolvedInputs);
        if (resolution.unavailable() != null) {
            return TxValidationOutcome.Invalid.of(LedgerFailure.ledgerStateUnavailable(resolution.unavailable()));
        }
        AmaruRequest amaruRequest = new AmaruRequest(mode, txCbor, network(), protocolParameters, ledgerConstants,
                resolution.dormantEpochs(), resolution.guardrail(), resolution.roots(), resolution.treasury(),
                env.currentSlot(), 0, resolution.utxo(), resolution.accounts(), resolution.pools(),
                resolution.dreps(), resolution.committee(), resolution.proposals());

        AmaruResponse response = callValidate(amaruRequest);
        switch (response) {
            case AmaruResponse.Error error -> {
                return engine(AMARU_ENGINE_FAILURE, "the module rejected the request: " + error.message());
            }
            case AmaruResponse.Invalid invalid -> {
                failures.add(toLedgerFailure(invalid));
                return new TxValidationOutcome.Invalid(failures);
            }
            case AmaruResponse.Ok ok -> {
                // Verdict below.
            }
        }
        if (!failures.isEmpty()) {
            return new TxValidationOutcome.Invalid(failures);
        }
        if (tx == null) {
            return engine(AMARU_ENGINE_FAILURE, "Amaru accepted a transaction that CCL cannot decode, so its "
                    + "effects cannot be derived");
        }

        boolean phase2Valid;
        if (mode == AmaruRequest.Mode.FULL) {
            phase2Valid = tx.isValid();
        } else {
            Phase2 phase2 = runPhase2(txCbor, tx, resolvedInputs, params, env);
            if (phase2.outcome() != null) {
                return phase2.outcome();
            }
            phase2Valid = phase2.valid();
        }
        if (!phase2Valid && request.origin() != TxValidationRequest.Origin.SYNC) {
            return TxValidationOutcome.Invalid.of(LedgerFailure.phase2InvalidTxNotSupported(
                    "is_valid = false transactions are not admitted (ADR-056 §6)"));
        }

        byte[] txId = TxIdentity.txId(txCbor);
        TxEffects effects;
        try {
            effects = effectsDeriver.derive(txCbor, tx, TxIdentity.txIdHex(txCbor), view, env, phase2Valid);
        } catch (IllegalArgumentException | IllegalStateException e) {
            return engine(AMARU_ENGINE_FAILURE, "Amaru accepted the transaction but its effects cannot be derived: "
                    + e.getMessage());
        }
        ValidatedTx validated = new ValidatedTx(txCbor, txId, env.protocolMajor(), env.currentEpoch(),
                env.phase2EnvDigest(), phase2Valid, request.origin());
        return new TxValidationOutcome.Valid(effects, validated, false);
    }

    // ----------------------------------------------------------------------------------- phase 2

    private record Phase2(boolean valid, TxValidationOutcome outcome) {
    }

    /**
     * {@link Phase2Mode#SCALUS}: Amaru judged phase one; the {@link ScriptPhaseEvaluator} runs the scripts,
     * and the result is compared with the transaction's {@code is_valid} flag
     * ({@code UTXOS.ValidationTagMismatch}, Alonzo/Rules/Utxos.hs).
     */
    private Phase2 runPhase2(byte[] txCbor, Transaction tx, Map<Outpoint, UtxoEntry> resolvedInputs,
                             ProtocolParams params, ValidationEnv env) {
        boolean scriptsPassed;
        if (!needsScriptPhase(tx, resolvedInputs)) {
            scriptsPassed = true;
        } else if (phase2Evaluator == null) {
            return new Phase2(false, engine(AMARU_ENGINE_FAILURE, "phase2 = scalus needs a ScriptPhaseEvaluator "
                    + "and none is configured"));
        } else {
            ScriptPhaseResult result;
            try {
                result = phase2Evaluator.evaluate(txCbor, tx, Map.copyOf(resolvedInputs), params, env.slotConfig(),
                        env.currentSlot());
            } catch (LedgerStateUnavailableException e) {
                throw e;
            } catch (RuntimeException e) {
                return new Phase2(false, engine(AMARU_ENGINE_FAILURE, "phase-2 evaluator failed: " + e));
            }
            switch (result) {
                case ScriptPhaseResult.Rejected rejected -> {
                    return new Phase2(false, new TxValidationOutcome.Invalid(rejected.failures()));
                }
                case ScriptPhaseResult.Passed passed -> scriptsPassed = true;
                case ScriptPhaseResult.Failed failed -> scriptsPassed = false;
            }
        }
        if (scriptsPassed != tx.isValid()) {
            // Detail as the module words it: "<tag mismatch>: <reason>".
            String mismatch = tx.isValid() ? "FailedUnexpectedly" : "PassedUnexpectedly";
            return new Phase2(false, TxValidationOutcome.Invalid.of(new LedgerFailure(LedgerRuleName.UTXOS,
                    "ValidationTagMismatch", LedgerFailure.Phase.PHASE_2, mismatch + ": "
                    + (tx.isValid() ? "a Plutus script failed (phase2 = scalus)"
                    : "every Plutus script succeeded (phase2 = scalus)"))));
        }
        return new Phase2(scriptsPassed, null);
    }

    /**
     * Whether the {@link ScriptPhaseEvaluator} must see the transaction: it has redeemers, Plutus script
     * witnesses, or a resolved input carrying a reference script. Phase-one mode does not check Plutus
     * script witnesses and reference scripts for well-formedness ({@code MalformedScriptWitnesses},
     * {@code MalformedReferenceScripts}), so a transaction carrying any of them goes to the evaluator even
     * without a redeemer.
     */
    static boolean needsScriptPhase(Transaction tx, Map<Outpoint, UtxoEntry> resolvedInputs) {
        TransactionWitnessSet witnesses = tx.getWitnessSet();
        if (witnesses != null && (notEmpty(witnesses.getRedeemers()) || notEmpty(witnesses.getPlutusV1Scripts())
                || notEmpty(witnesses.getPlutusV2Scripts()) || notEmpty(witnesses.getPlutusV3Scripts()))) {
            return true;
        }
        return resolvedInputs.values().stream().anyMatch(entry -> entry.output().getScriptRef() != null);
    }

    private static boolean notEmpty(List<?> list) {
        return list != null && !list.isEmpty();
    }

    // ---------------------------------------------------------------------------- key resolution

    private record Resolution(String unavailable, List<AmaruRequest.Utxo> utxo, List<AmaruRequest.Account> accounts,
                              List<PoolId> pools, List<AmaruRequest.DRep> dreps, List<CommitteeMember> committee,
                              List<AmaruRequest.Proposal> proposals, EnactedRoots roots, BigInteger treasury,
                              long dormantEpochs, String guardrail) {
        static Resolution unavailable(String reason) {
            return new Resolution(reason, null, null, null, null, null, null, null, null, 0, null);
        }
    }

    /**
     * Resolves the required keys through the view (ADR-057 invariant 3) and adds the slices that are
     * always shipped whole.
     */
    private static Resolution resolve(RequiredKeys keys, LedgerView view, Map<Outpoint, UtxoEntry> resolvedInputs) {
        List<AmaruRequest.Utxo> utxo = new ArrayList<>();
        for (Outpoint input : keys.inputs().stream().sorted(OUTPOINT_ORDER).distinct().toList()) {
            switch (view.utxo(input)) {
                case Lookup.Present<UtxoEntry> p -> {
                    resolvedInputs.put(input, p.value());
                    utxo.add(new AmaruRequest.Utxo(input, UtxoOutputEncoder.encode(p.value())));
                }
                case Lookup.Absent<UtxoEntry> a -> {
                }
                case Lookup.Unavailable<UtxoEntry> u -> {
                    return Resolution.unavailable("utxo " + input.txHash() + "#" + input.index() + ": " + u.reason());
                }
            }
        }

        List<AmaruRequest.Account> accounts = new ArrayList<>();
        for (CredentialKey credential : sorted(keys.accounts())) {
            switch (view.account(credential)) {
                case Lookup.Present<AccountState> p -> {
                    AccountState a = p.value();
                    accounts.add(new AmaruRequest.Account(credential, a.deposit(), a.rewardBalance(),
                            a.delegatedPool(), null, a.drepDelegation(), null));
                }
                case Lookup.Absent<AccountState> a -> {
                }
                case Lookup.Unavailable<AccountState> u -> {
                    return Resolution.unavailable("account " + credential + ": " + u.reason());
                }
            }
        }

        List<PoolId> pools = new ArrayList<>();
        for (PoolId pool : keys.pools().stream().sorted(Comparator.comparing(PoolId::hashHex)).distinct().toList()) {
            switch (view.pool(pool)) {
                case Lookup.Present<?> p -> pools.add(pool);
                case Lookup.Absent<?> a -> {
                }
                case Lookup.Unavailable<?> u -> {
                    return Resolution.unavailable(pool + ": " + u.reason());
                }
            }
        }

        List<AmaruRequest.DRep> dreps = new ArrayList<>();
        for (CredentialKey credential : sorted(keys.dreps())) {
            switch (view.drep(credential)) {
                case Lookup.Present<DRepState> p -> dreps.add(new AmaruRequest.DRep(credential, p.value().deposit(),
                        null, p.value().expiryEpoch()));
                case Lookup.Absent<DRepState> a -> {
                }
                case Lookup.Unavailable<DRepState> u -> {
                    return Resolution.unavailable("drep " + credential + ": " + u.reason());
                }
            }
        }

        // Always whole: the committee (members and UpdateCommittee candidates) and all proposals.
        List<CommitteeMember> committee = new ArrayList<>();
        Set<CredentialKey> members = new HashSet<>();
        for (CommitteeMemberState member : view.committeeMembers().require("committee members")) {
            members.add(member.cold());
            committee.add(new CommitteeMember(member.cold(), member.hot(), member.resigned(), member.expiryEpoch()));
        }
        for (CredentialKey candidate : view.committeeCandidates().require("committee candidates")) {
            if (members.add(candidate)) {
                committee.add(new CommitteeMember(candidate, null, false, null));
            }
        }
        committee.sort(Comparator.comparing(CommitteeMember::cold, CREDENTIAL_ORDER));

        List<AmaruRequest.Proposal> proposals = new ArrayList<>();
        for (ProposalState proposal : view.activeProposals().require("active proposals")) {
            ProposalKind kind = proposalKind(proposal);
            if (kind == null) {
                return Resolution.unavailable("proposal " + proposal.id() + " (" + proposal.type() + "): "
                        + (proposal.type() == GovActionType.PARAMETER_CHANGE_ACTION
                        ? "its protocol_param_update keys are unknown, so its security group cannot be decided"
                        : "its action payload is unknown"));
            }
            proposals.add(new AmaruRequest.Proposal(proposal.id(), kind, proposal.expiresAfterEpoch()));
        }
        proposals.sort(Comparator.comparing((AmaruRequest.Proposal p) -> p.id().txHashHex())
                .thenComparingInt(p -> p.id().index()));

        String guardrail = switch (view.guardrailScriptHash()) {
            case Lookup.Present<String> p -> p.value();
            case Lookup.Absent<String> a -> null;
            case Lookup.Unavailable<String> u -> throw new LedgerStateUnavailableException(u.reason());
        };
        return new Resolution(null, utxo, accounts, pools, dreps, committee, proposals,
                view.enactedRoots().require("enacted roots"), view.treasury().require("treasury"),
                view.dormantEpochs().require("dormant epochs"), guardrail);
    }

    private static List<CredentialKey> sorted(List<CredentialKey> credentials) {
        return credentials.stream().sorted(CREDENTIAL_ORDER).distinct().toList();
    }

    /**
     * {@code proposal_kind}; null when the data needed for it is unknown.
     *
     * <p>A parameter change's {@code any_in_security_group} comes from
     * {@link ProposalState#paramUpdateKeys()} (the keys of its {@code protocol_param_update} map), never
     * from CCL's decoded {@code ProtocolParamUpdate}, which has no fields for the Conway keys (30
     * {@code govActionDeposit} and 33 {@code minFeeRefScriptCostPerByte} are security-group keys). When
     * the keys are unknown the request is not sent: guessing would let Amaru reject a legitimate SPO vote
     * ({@code GOV.DisallowedVoters}) or accept a disallowed one.</p>
     *
     * <p>The canonical view reads the keys from the stored action payload
     * ({@code CanonicalLedgerView.paramUpdateKeys}, ADR-056 step 1d) and overlays from the submitting
     * transaction ({@code TxEffectsDeriver}); only a stored proposal without a payload still fails closed.</p>
     */
    static ProposalKind proposalKind(ProposalState proposal) {
        return switch (proposal.type()) {
            case PARAMETER_CHANGE_ACTION -> {
                Boolean security = proposal.anyInSecurityGroup();
                yield security == null ? null : new ProposalKind.ParameterChange(security);
            }
            case HARD_FORK_INITIATION_ACTION -> proposal.action() instanceof HardForkInitiationAction hardFork
                    && hardFork.getProtocolVersion() != null
                    ? new ProposalKind.HardFork(hardFork.getProtocolVersion().getMajor(),
                    hardFork.getProtocolVersion().getMinor())
                    : null;
            case NO_CONFIDENCE, UPDATE_COMMITTEE -> new ProposalKind.Committee();
            case NEW_CONSTITUTION -> new ProposalKind.Constitution();
            case TREASURY_WITHDRAWALS_ACTION -> new ProposalKind.TreasuryWithdrawals();
            case INFO_ACTION -> new ProposalKind.Info();
        };
    }

    // ------------------------------------------------------------------------------ module calls

    private AmaruRequest emptyRequest(AmaruRequest.Mode mode, byte[] txCbor, byte[] protocolParameters,
                                      ValidationEnv env) {
        return new AmaruRequest(mode, txCbor, network(), protocolParameters, ledgerConstants, 0, null,
                EnactedRoots.NONE, BigInteger.ZERO, env.currentSlot(), 0, List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of());
    }

    private AmaruResponse callValidate(AmaruRequest request) {
        byte[] document = AmaruRequestEncoder.encode(request);
        byte[] response = pool.call(instance -> instance.validate(document));
        try {
            return AmaruResponse.decode(response);
        } catch (RuntimeException e) {
            throw new AmaruEngineException(AmaruEngineException.Kind.BAD_RESPONSE,
                    "undecodable validate response: " + e.getMessage(), e);
        }
    }

    private static RequiredKeys.Result decodeKeys(byte[] response) {
        try {
            return RequiredKeys.decode(response);
        } catch (RuntimeException e) {
            throw new AmaruEngineException(AmaruEngineException.Kind.BAD_RESPONSE,
                    "undecodable required_keys response: " + e.getMessage(), e);
        }
    }

    private static Transaction decodeWithCcl(byte[] txCbor) {
        try {
            return Transaction.deserialize(txCbor);
        } catch (Exception e) {
            log.debug("CCL cannot decode the transaction: {}", e.toString());
            return null;
        }
    }

    // ------------------------------------------------------------------------------ failures

    static LedgerFailure toLedgerFailure(AmaruResponse.Invalid invalid) {
        String detail = (invalid.tagMismatch() != null ? invalid.tagMismatch() + ": " : "")
                + invalid.amaruError() + ": " + invalid.detail();
        if (invalid.phase() == 0 || "DECODE".equals(invalid.rule())) {
            return new LedgerFailure(LedgerRuleName.ENGINE, DECODING_FAILURE, LedgerFailure.Phase.PHASE_1, detail);
        }
        LedgerRuleName rule;
        try {
            rule = LedgerRuleName.valueOf(invalid.rule());
        } catch (IllegalArgumentException e) {
            return new LedgerFailure(LedgerRuleName.ENGINE, AMARU_ENGINE_FAILURE, LedgerFailure.Phase.PHASE_1,
                    "unknown rule " + invalid.rule() + ": " + detail);
        }
        // Without a Haskell leaf constructor (era-history arithmetic, impossible preparation states) the
        // Amaru variant path stands in for it.
        String constructor = invalid.constructor() != null ? invalid.constructor() : invalid.amaruError();
        LedgerFailure.Phase phase = invalid.phase() == 2 ? LedgerFailure.Phase.PHASE_2 : LedgerFailure.Phase.PHASE_1;
        return new LedgerFailure(rule, constructor, phase, detail);
    }

    private static TxValidationOutcome engine(String constructor, String detail) {
        return TxValidationOutcome.Invalid.of(new LedgerFailure(LedgerRuleName.ENGINE, constructor,
                LedgerFailure.Phase.PHASE_1, detail));
    }

    @Override
    public void close() {
        pool.close();
    }
}
