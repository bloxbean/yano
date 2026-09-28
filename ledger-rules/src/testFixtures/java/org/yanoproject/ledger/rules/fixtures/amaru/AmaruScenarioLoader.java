package org.yanoproject.ledger.rules.fixtures.amaru;

import com.bloxbean.cardano.client.api.model.ProtocolParams;
import com.bloxbean.cardano.client.common.cbor.CborSerializationUtil;
import com.bloxbean.cardano.client.common.model.SlotConfig;
import com.bloxbean.cardano.client.spec.NetworkId;
import com.bloxbean.cardano.client.transaction.spec.ProtocolParamUpdate;
import com.bloxbean.cardano.client.transaction.spec.ProtocolVersion;
import com.bloxbean.cardano.client.transaction.spec.TransactionOutput;
import com.bloxbean.cardano.client.transaction.spec.governance.actions.GovAction;
import com.bloxbean.cardano.client.transaction.spec.governance.actions.GovActionType;
import com.bloxbean.cardano.client.transaction.spec.governance.actions.HardForkInitiationAction;
import com.bloxbean.cardano.client.transaction.spec.governance.actions.InfoAction;
import com.bloxbean.cardano.client.transaction.spec.governance.actions.NewConstitution;
import com.bloxbean.cardano.client.transaction.spec.governance.actions.NoConfidence;
import com.bloxbean.cardano.client.transaction.spec.governance.actions.ParameterChangeAction;
import com.bloxbean.cardano.client.transaction.spec.governance.actions.TreasuryWithdrawalsAction;
import com.bloxbean.cardano.client.util.HexUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.junit.jupiter.api.Assumptions;
import org.yanoproject.api.utxo.model.Outpoint;
import org.yanoproject.ledger.rules.LedgerFailure;
import org.yanoproject.ledger.rules.ValidationEnv;
import org.yanoproject.ledger.rules.fixtures.amaru.AmaruScenario.CertPointer;
import org.yanoproject.ledger.rules.fixtures.amaru.AmaruScenario.EraBound;
import org.yanoproject.ledger.rules.fixtures.amaru.AmaruScenario.EraSummary;
import org.yanoproject.ledger.rules.fixtures.amaru.AmaruScenario.Expected;
import org.yanoproject.ledger.rules.fixtures.amaru.AmaruScenario.GlobalParameters;
import org.yanoproject.ledger.rules.fixtures.amaru.AmaruScenario.LedgerConstants;
import org.yanoproject.ledger.rules.fixtures.amaru.AmaruScenario.Network;
import org.yanoproject.ledger.rules.fixtures.amaru.AmaruScenario.RawAccount;
import org.yanoproject.ledger.rules.fixtures.amaru.AmaruScenario.RawCommitteeMember;
import org.yanoproject.ledger.rules.fixtures.amaru.AmaruScenario.RawDRep;
import org.yanoproject.ledger.rules.fixtures.amaru.AmaruScenario.RawProposal;
import org.yanoproject.ledger.rules.fixtures.amaru.AmaruScenario.RawUtxo;
import org.yanoproject.ledger.rules.fixtures.amaru.AmaruScenario.State;
import org.yanoproject.ledger.rules.util.CborItems;
import org.yanoproject.ledger.rules.view.InMemoryLedgerView;
import org.yanoproject.ledger.rules.view.model.AccountState;
import org.yanoproject.ledger.rules.view.model.CommitteeMemberState;
import org.yanoproject.ledger.rules.view.model.CredentialKey;
import org.yanoproject.ledger.rules.view.model.CredentialType;
import org.yanoproject.ledger.rules.view.model.DRepState;
import org.yanoproject.ledger.rules.view.model.DRepTarget;
import org.yanoproject.ledger.rules.view.model.EnactedRoots;
import org.yanoproject.ledger.rules.view.model.GovActionId;
import org.yanoproject.ledger.rules.view.model.Outpoints;
import org.yanoproject.ledger.rules.view.model.PoolId;
import org.yanoproject.ledger.rules.view.model.PoolState;
import org.yanoproject.ledger.rules.view.model.ProposalState;
import org.yanoproject.ledger.rules.view.model.UtxoEntry;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Reads Amaru's implementation-generic transaction scenarios
 * ({@code crates/amaru-ledger/tests/data/transaction} at the tag ADR-057 pins, {@code v10.11.20260925};
 * format in that directory's {@code README.md} and {@code schema.json}) into {@link AmaruScenario}s:
 * an {@link InMemoryLedgerView}, the transaction bytes, a {@link ValidationEnv} and the expected verdict
 * with its Haskell name.
 *
 * <p>Shared by ADR-056 Phase 2 (the Java engine) and ADR-057 Phase B (the Amaru engine).</p>
 *
 * <p><b>Locating the corpus.</b> The directory comes from the system property
 * {@value #SCENARIOS_PROPERTY} or the environment variable {@value #SCENARIOS_ENV}. Either may name the
 * {@code transaction} data directory itself or the root of an Amaru checkout. Tests call
 * {@link #requireScenariosDir()}, which skips them (a JUnit assumption) when neither is set: the corpus
 * is Amaru's (Apache-2.0) and is not copied into Yano.</p>
 *
 * <p><b>Mapping to the view model.</b></p>
 * <ul>
 *   <li>UTxO outputs are decoded with CCL. The inline datum's original bytes are carried in
 *       {@link UtxoEntry#inlineDatumCbor()}; the reference script's bytes are already exact in CCL.</li>
 *   <li>Pools are only ids in the corpus: each becomes a {@link PoolState} with the stake-pool deposit
 *       and a synthetic VRF key hash (the id followed by four zero bytes).</li>
 *   <li>Committee entries with neither a hot credential nor a term become committee <em>candidates</em>;
 *       all others become {@link CommitteeMemberState}s (a term makes the member elected).</li>
 *   <li>Proposals carry only their lineage in the corpus: each becomes a {@link ProposalState} with a
 *       representative CCL action of that kind (a parameter change of {@code maxTxSize} for the security
 *       group, of {@code collateralPercent} otherwise, with the matching
 *       {@link ProposalState#paramUpdateKeys()}; {@code NoConfidence} for the committee lineage).
 *       {@code expiresAfterEpoch} is the corpus {@code valid_until}.</li>
 *   <li>Protocol-parameter rationals become exact {@link BigDecimal}s (every value in the corpus has a
 *       power-of-ten denominator). Cost models go to {@code costModelsRaw} under
 *       {@code PlutusV1}/{@code PlutusV2}/{@code PlutusV3}.</li>
 *   <li>The environment: slot {@code point.slot}, its epoch under the era history, the parameters'
 *       protocol version, {@code NetworkId.MAINNET} only for {@code mainnet}, a slot config from the era
 *       history and system start, and an all-zero phase-2 environment digest.</li>
 * </ul>
 */
public final class AmaruScenarioLoader {

    public static final String SCENARIOS_PROPERTY = "amaru.scenarios.dir";
    public static final String SCENARIOS_ENV = "AMARU_SCENARIOS_DIR";

    /** Scenario count at the pinned tag (same as {@code EXPECTED_SCENARIOS} in the Rust gate). */
    public static final int EXPECTED_SCENARIOS = 276;

    private static final String TRANSACTION_DATA = "crates/amaru-ledger/tests/data/transaction";

    // Haskell hardcodes these (Amaru's ProtocolParameters decoder defaults, INTERFACE.md request key 7).
    private static final long DEFAULT_MAX_REF_SCRIPT_SIZE_PER_TX = 200 * 1024;
    private static final long DEFAULT_MAX_REF_SCRIPT_SIZE_PER_BLOCK = 1024 * 1024;
    private static final long DEFAULT_REF_SCRIPT_COST_STRIDE = 25_600;
    private static final BigInteger DEFAULT_MULTIPLIER_NUMERATOR = BigInteger.valueOf(12);
    private static final BigInteger DEFAULT_MULTIPLIER_DENOMINATOR = BigInteger.TEN;

    // Amaru's GlobalParameters per named network (amaru-kernel cardano/global_parameters.rs at the tag).
    private static final GlobalParameters MAINNET = new GlobalParameters(2160, 10, 20,
            BigInteger.valueOf(45_000_000_000_000_000L), 129_600, 62, 1_506_203_091_000L);
    private static final GlobalParameters PREPROD = new GlobalParameters(2160, 10, 20,
            BigInteger.valueOf(45_000_000_000_000_000L), 129_600, 62, 1_654_041_600_000L);
    private static final GlobalParameters PREVIEW = new GlobalParameters(432, 10, 20,
            BigInteger.valueOf(45_000_000_000_000_000L), 129_600, 62, 1_666_656_000_000L);

    private static final ObjectMapper JSON = new ObjectMapper();

    private final Path root;

    /** @param root the {@code transaction} data directory (holding {@code scenarios/} and {@code common/}) */
    public AmaruScenarioLoader(Path root) {
        this.root = root;
    }

    /** @return the configured corpus directory, if any and if it exists */
    public static Optional<Path> locateScenariosDir() {
        String configured = System.getProperty(SCENARIOS_PROPERTY);
        if (configured == null || configured.isBlank()) {
            configured = System.getenv(SCENARIOS_ENV);
        }
        if (configured == null || configured.isBlank()) {
            return Optional.empty();
        }
        Path dir = Path.of(configured);
        if (Files.isDirectory(dir.resolve("scenarios"))) {
            return Optional.of(dir);
        }
        Path nested = dir.resolve(TRANSACTION_DATA);
        if (Files.isDirectory(nested.resolve("scenarios"))) {
            return Optional.of(nested);
        }
        return Optional.empty();
    }

    /**
     * @return the corpus directory
     * @throws org.opentest4j.TestAbortedException (a skipped test) when it is not configured
     */
    public static Path requireScenariosDir() {
        Optional<Path> dir = locateScenariosDir();
        Assumptions.assumeTrue(dir.isPresent(), () -> "Amaru scenarios not configured: set -D"
                + SCENARIOS_PROPERTY + " or " + SCENARIOS_ENV + " to a clone of https://github.com/pragma-org/amaru"
                + " at tag v10.11.20260925 (or its " + TRANSACTION_DATA + " directory)");
        return dir.orElseThrow();
    }

    /** @return a loader for the configured corpus; skips the calling test when there is none */
    public static AmaruScenarioLoader fromEnvironment() {
        return new AmaruScenarioLoader(requireScenariosDir());
    }

    public Path root() {
        return root;
    }

    /** @return every scenario file, sorted by name */
    public List<Path> scenarioFiles() {
        try (Stream<Path> files = Files.list(root.resolve("scenarios"))) {
            return files.filter(p -> p.getFileName().toString().endsWith(".json")).sorted().toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** @return every scenario, sorted by name */
    public List<AmaruScenario> loadAll() {
        return scenarioFiles().stream().map(this::load).toList();
    }

    /** @param fileName a file under {@code scenarios/}, e.g. {@code 00005-pass-….json} */
    public AmaruScenario load(String fileName) {
        return load(root.resolve("scenarios").resolve(fileName));
    }

    public AmaruScenario load(Path file) {
        String name = file.getFileName().toString().replaceFirst("\\.json$", "");
        try {
            return parse(name, file, JSON.readTree(file.toFile()));
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read scenario " + file, e);
        } catch (RuntimeException e) {
            throw new IllegalStateException("Cannot load scenario " + name + ": " + e.getMessage(), e);
        }
    }

    // ------------------------------------------------------------------------------------ parsing

    private AmaruScenario parse(String name, Path file, JsonNode fixture) throws IOException {
        String networkName = fixture.path("network").asText();
        long magic = networkMagic(networkName);
        JsonNode eraHistory = resolve(fixture.get("era_history"));
        List<EraSummary> eras = new ArrayList<>();
        for (JsonNode era : eraHistory.get("eras")) {
            JsonNode params = era.get("params");
            eras.add(new EraSummary(eraBound(era.get("start")),
                    era.hasNonNull("end") ? eraBound(era.get("end")) : null,
                    params.get("epoch_size_slots").asLong(), params.get("slot_length").asLong(),
                    eraTag(params.get("era_name").asText())));
        }
        Network network = new Network(networkName, magic, eraHistory.get("stability_window").asLong(), eras,
                globalParameters(networkName));

        JsonNode pp = resolve(fixture.get("protocol_parameters"));
        ProtocolParams params = protocolParams(pp);
        LedgerConstants constants = ledgerConstants(pp);

        JsonNode s = fixture.path("initial_state");
        InMemoryLedgerView.Builder view = InMemoryLedgerView.builder().protocolParams(params);

        List<RawUtxo> utxo = new ArrayList<>();
        for (JsonNode entry : array(s, "utxo")) {
            Outpoint outpoint = outpoint(hex(entry.get("input")));
            byte[] output = hex(entry.get("output"));
            utxo.add(new RawUtxo(outpoint, output));
            view.utxo(new UtxoEntry(outpoint, output(output), inlineDatumBytes(output)));
        }

        List<PoolId> pools = new ArrayList<>();
        for (JsonNode pool : array(s, "pools")) {
            PoolId id = new PoolId(pool.asText());
            pools.add(id);
            view.pool(new PoolState(id, new BigInteger(params.getPoolDeposit()), id.hashHex() + "00000000", null,
                    null, null));
        }

        List<RawAccount> accounts = new ArrayList<>();
        for (JsonNode account : array(s, "accounts")) {
            CredentialKey credential = credential(hex(account.get("credential")));
            BigInteger deposit = account.get("deposit").bigIntegerValue();
            BigInteger rewards = account.hasNonNull("rewards") ? account.get("rewards").bigIntegerValue()
                    : BigInteger.ZERO;
            PoolId pool = null;
            CertPointer poolPointer = null;
            if (account.hasNonNull("pool")) {
                pool = new PoolId(account.get("pool").get("id").asText());
                poolPointer = pointer(account.get("pool").get("delegated_at"));
            }
            DRepTarget drep = null;
            CertPointer drepPointer = null;
            if (account.hasNonNull("drep")) {
                drep = drepTarget(hex(account.get("drep").get("id")));
                drepPointer = pointer(account.get("drep").get("delegated_at"));
            }
            accounts.add(new RawAccount(credential, deposit, rewards, pool, poolPointer, drep, drepPointer));
            view.account(new AccountState(credential, deposit, rewards, pool, drep));
        }

        List<RawDRep> dreps = new ArrayList<>();
        for (JsonNode drep : array(s, "dreps")) {
            CredentialKey credential = credential(hex(drep.get("credential")));
            BigInteger deposit = drep.get("deposit").bigIntegerValue();
            long validUntil = drep.get("valid_until").asLong();
            dreps.add(new RawDRep(credential, deposit, pointer(drep.get("registered_at")), validUntil));
            view.drep(new DRepState(credential, deposit, validUntil));
        }

        List<RawCommitteeMember> committee = new ArrayList<>();
        for (JsonNode member : array(s, "committee")) {
            CredentialKey cold = credential(hex(member.get("cold_credential")));
            String status = member.hasNonNull("status") ? member.get("status").asText() : null;
            Long validUntil = member.hasNonNull("valid_until") ? member.get("valid_until").asLong() : null;
            boolean resigned = "resigned".equals(status);
            CredentialKey hot = status != null && !resigned ? credential(HexUtil.decodeHexString(status)) : null;
            committee.add(new RawCommitteeMember(cold, hot, resigned, validUntil));
            if (hot == null && !resigned && validUntil == null) {
                view.committeeCandidate(cold);
            } else {
                view.committeeMember(new CommitteeMemberState(cold, hot, resigned, validUntil));
            }
        }

        long lifetime = params.getGovActionLifetime();
        List<RawProposal> proposals = new ArrayList<>();
        for (JsonNode proposal : array(s, "proposals")) {
            GovActionId id = govActionId(proposal.get(0));
            String kind = proposal.get(1).asText();
            long validUntil = proposal.get(2).asLong();
            proposals.add(new RawProposal(id, kind, validUntil));
            GovAction action = action(kind);
            view.proposal(new ProposalState(id, action.getType(), action, null, validUntil - lifetime, validUntil,
                    params.getGovActionDeposit(), null, paramUpdateKeys(kind)));
        }

        JsonNode rootsNode = s.path("proposals_roots");
        EnactedRoots roots = new EnactedRoots(
                optionalGovActionId(rootsNode, "protocol_parameters", "protocolParameters"),
                optionalGovActionId(rootsNode, "hard_fork", "hardFork"),
                optionalGovActionId(rootsNode, "constitutional_committee", "constitutionalCommittee"),
                optionalGovActionId(rootsNode, "constitution", "constitution"));
        view.enactedRoots(roots);

        long dormant = s.path("governance_activity").path("consecutive_dormant_epochs").asLong(0);
        view.dormantEpochs(dormant);
        BigInteger treasury = s.path("pots").hasNonNull("treasury")
                ? s.path("pots").get("treasury").bigIntegerValue() : BigInteger.ZERO;
        view.treasury(treasury);
        String guardrail = s.hasNonNull("guardrail_script") ? s.get("guardrail_script").asText() : null;
        view.guardrailScriptHash(guardrail);

        JsonNode point = fixture.get("point");
        long slot = point.get("slot").asLong();
        long transactionIndex = point.get("transaction_index").asLong();
        State state = new State(utxo, accounts, pools, dreps, committee, proposals, roots, dormant, treasury,
                guardrail, slot, transactionIndex);

        ValidationEnv env = new ValidationEnv(slot, epochOf(eras, slot), params.getProtocolMajorVer(),
                params.getProtocolMinorVer(), "mainnet".equals(networkName) ? NetworkId.MAINNET : NetworkId.TESTNET,
                slotConfig(eras, network.globalParameters()), new byte[32]);

        return new AmaruScenario(name, file, fixture.path("title").asText(name), hex(fixture.get("transaction")),
                view.build(), env, params, expected(fixture.get("expected")), network, constants, state);
    }

    /** {@code $ref} to a shared document, with an optional shallow {@code $override}. */
    private JsonNode resolve(JsonNode value) throws IOException {
        if (value == null || !value.has("$ref")) {
            return value;
        }
        JsonNode document = JSON.readTree(root.resolve(value.get("$ref").asText()).toFile());
        JsonNode overrides = value.get("$override");
        if (overrides != null && overrides.isObject()) {
            ObjectNode merged = ((ObjectNode) document).deepCopy();
            overrides.properties().forEach(e -> merged.set(e.getKey(), e.getValue()));
            return merged;
        }
        return document;
    }

    private static Expected expected(JsonNode expected) {
        if (expected.isTextual() && "Pass".equals(expected.asText())) {
            return new Expected.Pass();
        }
        if (expected.has("decoding_failure")) {
            return new Expected.DecodingFailure();
        }
        String predicate = expected.get("predicate").asText();
        AmaruCorpusNames.HaskellName haskell = AmaruCorpusNames.haskellName(predicate)
                .orElseThrow(() -> new IllegalStateException("corpus predicate " + predicate
                        + " has no Haskell alias (AmaruCorpusNames)"));
        String description = expected.hasNonNull("description") ? expected.get("description").asText() : null;
        LedgerFailure.Phase phase = "ValidationTagMismatch".equals(predicate)
                ? LedgerFailure.Phase.PHASE_2 : LedgerFailure.Phase.PHASE_1;
        return new Expected.Predicate(predicate, haskell.rule(), haskell.constructor(), phase, description);
    }

    // ------------------------------------------------------------------------- protocol parameters

    static ProtocolParams protocolParams(JsonNode pp) {
        JsonNode poolThresholds = pp.get("stake_pool_voting_thresholds");
        JsonNode drepThresholds = pp.get("delegate_representative_voting_thresholds");
        LinkedHashMap<String, List<Long>> costModelsRaw = new LinkedHashMap<>();
        JsonNode costModels = pp.path("plutus_cost_models");
        String[][] languages = {{"plutus_v1", "PlutusV1"}, {"plutus_v2", "PlutusV2"}, {"plutus_v3", "PlutusV3"}};
        for (String[] language : languages) {
            if (costModels.hasNonNull(language[0])) {
                List<Long> values = new ArrayList<>();
                costModels.get(language[0]).forEach(v -> values.add(v.asLong()));
                costModelsRaw.put(language[1], values);
            }
        }
        return ProtocolParams.builder()
                .minFeeA(pp.get("min_fee_coefficient").asInt())
                .minFeeB(pp.get("min_fee_constant").asInt())
                .maxBlockSize(pp.get("max_block_body_size").asInt())
                .maxTxSize(pp.get("max_transaction_size").asInt())
                .maxBlockHeaderSize(pp.get("max_block_header_size").asInt())
                .keyDeposit(pp.get("stake_credential_deposit").asText())
                .poolDeposit(pp.get("stake_pool_deposit").asText())
                .eMax(pp.get("stake_pool_retirement_epoch_bound").asInt())
                .nOpt(pp.get("desired_number_of_stake_pools").asInt())
                .a0(ratio(pp.get("stake_pool_pledge_influence")))
                .rho(ratio(pp.get("monetary_expansion")))
                .tau(ratio(pp.get("treasury_expansion")))
                .protocolMajorVer(pp.get("version").get("major").asInt())
                .protocolMinorVer(pp.get("version").get("minor").asInt())
                .minPoolCost(pp.get("min_stake_pool_cost").asText())
                .coinsPerUtxoSize(pp.get("min_utxo_deposit_coefficient").asText())
                .costModelsRaw(costModelsRaw)
                .priceMem(ratio(pp.get("script_execution_prices").get("mem_price")))
                .priceStep(ratio(pp.get("script_execution_prices").get("step_price")))
                .maxTxExMem(pp.get("max_execution_units_per_transaction").get("mem").asText())
                .maxTxExSteps(pp.get("max_execution_units_per_transaction").get("steps").asText())
                .maxBlockExMem(pp.get("max_execution_units_per_block").get("mem").asText())
                .maxBlockExSteps(pp.get("max_execution_units_per_block").get("steps").asText())
                .maxValSize(pp.get("max_value_size").asText())
                .collateralPercent(new BigDecimal(pp.get("collateral_percentage").asText()))
                .maxCollateralInputs(pp.get("max_collateral_inputs").asInt())
                .pvtMotionNoConfidence(ratio(poolThresholds.get("motion_no_confidence")))
                .pvtCommitteeNormal(ratio(poolThresholds.get("committee_normal")))
                .pvtCommitteeNoConfidence(ratio(poolThresholds.get("committee_no_confidence")))
                .pvtHardForkInitiation(ratio(poolThresholds.get("hard_fork_initiation")))
                .pvtPPSecurityGroup(ratio(poolThresholds.get("security_voting_threshold")))
                .dvtMotionNoConfidence(ratio(drepThresholds.get("motion_no_confidence")))
                .dvtCommitteeNormal(ratio(drepThresholds.get("committee_normal")))
                .dvtCommitteeNoConfidence(ratio(drepThresholds.get("committee_no_confidence")))
                .dvtUpdateToConstitution(ratio(drepThresholds.get("update_constitution")))
                .dvtHardForkInitiation(ratio(drepThresholds.get("hard_fork_initiation")))
                .dvtPPNetworkGroup(ratio(drepThresholds.get("pp_network_group")))
                .dvtPPEconomicGroup(ratio(drepThresholds.get("pp_economic_group")))
                .dvtPPTechnicalGroup(ratio(drepThresholds.get("pp_technical_group")))
                .dvtPPGovGroup(ratio(drepThresholds.get("pp_governance_group")))
                .dvtTreasuryWithdrawal(ratio(drepThresholds.get("treasury_withdrawal")))
                .committeeMinSize(pp.get("constitutional_committee_min_size").asInt())
                .committeeMaxTermLength(pp.get("constitutional_committee_max_term_length").asInt())
                .govActionLifetime(pp.get("governance_action_lifetime").asInt())
                .govActionDeposit(pp.get("governance_action_deposit").bigIntegerValue())
                .drepDeposit(pp.get("delegate_representative_deposit").bigIntegerValue())
                .drepActivity(pp.get("delegate_representative_max_idle_time").asInt())
                .minFeeRefScriptCostPerByte(ratio(pp.get("min_fee_reference_scripts").get("base")))
                .build();
    }

    static LedgerConstants ledgerConstants(JsonNode pp) {
        Long perTx = differs(pp.path("max_reference_scripts_size"), DEFAULT_MAX_REF_SCRIPT_SIZE_PER_TX);
        Long perBlock = differs(pp.path("max_reference_scripts_size_per_block"), DEFAULT_MAX_REF_SCRIPT_SIZE_PER_BLOCK);
        JsonNode refScripts = pp.path("min_fee_reference_scripts");
        Long stride = differs(refScripts.path("range"), DEFAULT_REF_SCRIPT_COST_STRIDE);
        BigInteger numerator = null;
        BigInteger denominator = null;
        JsonNode multiplier = refScripts.path("multiplier");
        if (multiplier.isObject()) {
            BigInteger n = multiplier.get("numerator").bigIntegerValue();
            BigInteger d = multiplier.get("denominator").bigIntegerValue();
            if (!n.equals(DEFAULT_MULTIPLIER_NUMERATOR) || !d.equals(DEFAULT_MULTIPLIER_DENOMINATOR)) {
                numerator = n;
                denominator = d;
            }
        }
        return new LedgerConstants(perTx, perBlock, stride, numerator, denominator);
    }

    private static Long differs(JsonNode value, long haskellDefault) {
        return value.isNumber() && value.asLong() != haskellDefault ? value.asLong() : null;
    }

    /** An exact decimal for {@code {numerator, denominator}}; the corpus only uses power-of-ten denominators. */
    private static BigDecimal ratio(JsonNode ratio) {
        return new BigDecimal(ratio.get("numerator").bigIntegerValue())
                .divide(new BigDecimal(ratio.get("denominator").bigIntegerValue()));
    }

    // ------------------------------------------------------------------------------ small values

    private static List<JsonNode> array(JsonNode state, String field) {
        List<JsonNode> result = new ArrayList<>();
        state.path(field).forEach(result::add);
        return result;
    }

    private static byte[] hex(JsonNode value) {
        return HexUtil.decodeHexString(value.asText());
    }

    private static long networkMagic(String network) {
        return switch (network) {
            case "mainnet" -> 764_824_073L;
            case "preprod" -> 1;
            case "preview" -> 2;
            default -> {
                if (!network.startsWith("testnet_")) {
                    throw new IllegalArgumentException("unknown network " + network);
                }
                yield Long.parseLong(network.substring("testnet_".length()));
            }
        };
    }

    private static GlobalParameters globalParameters(String network) {
        return switch (network) {
            case "mainnet" -> MAINNET;
            case "preprod" -> PREPROD;
            case "preview" -> PREVIEW;
            default -> throw new IllegalArgumentException("no global parameters for " + network
                    + " (Amaru defines them for mainnet, preprod and preview only)");
        };
    }

    /** Amaru's {@code EraName} numbering. */
    static int eraTag(String eraName) {
        return switch (eraName) {
            case "Byron" -> 1;
            case "Shelley" -> 2;
            case "Allegra" -> 3;
            case "Mary" -> 4;
            case "Alonzo" -> 5;
            case "Babbage" -> 6;
            case "Conway" -> 7;
            case "Dijkstra" -> 8;
            default -> throw new IllegalArgumentException("unknown era " + eraName);
        };
    }

    /** {@code time} is serialised in picoseconds (Amaru's {@code SerialisedAsPico}). */
    private static EraBound eraBound(JsonNode bound) {
        BigInteger picos = bound.get("time").bigIntegerValue();
        return new EraBound(picos.divide(BigInteger.valueOf(1_000_000_000L)).longValueExact(),
                bound.get("slot").asLong(), bound.get("epoch").asLong());
    }

    private static long epochOf(List<EraSummary> eras, long slot) {
        for (EraSummary era : eras) {
            if (slot >= era.start().slot() && (era.end() == null || slot < era.end().slot())) {
                return era.start().epoch() + (slot - era.start().slot()) / era.epochSizeSlots();
            }
        }
        throw new IllegalArgumentException("slot " + slot + " is outside the era history");
    }

    private static SlotConfig slotConfig(List<EraSummary> eras, GlobalParameters global) {
        EraSummary last = eras.getLast();
        long zeroTime = global.systemStartMs() + last.start().timeMs();
        return new SlotConfig((int) last.slotLengthMs(), last.start().slot(), zeroTime);
    }

    private static CertPointer pointer(JsonNode pointer) {
        if (pointer == null || pointer.isNull()) {
            return null;
        }
        JsonNode transaction = pointer.get("transaction");
        return new CertPointer(transaction.get("slot").asLong(), transaction.get("transaction_index").asLong(),
                pointer.get("certificate_index").asLong());
    }

    private static GovActionId govActionId(JsonNode id) {
        return new GovActionId(id.get("transaction_id").asText(), id.get("proposal_index").asInt());
    }

    private static GovActionId optionalGovActionId(JsonNode roots, String snake, String camel) {
        JsonNode id = roots.hasNonNull(snake) ? roots.get(snake) : roots.get(camel);
        return id == null || id.isNull() ? null : govActionId(id);
    }

    /**
     * The {@code protocol_param_update} keys of the representative action: maxTxSize (3, security group)
     * or collateralPercentage (23); null for other lineages.
     */
    static Set<Integer> paramUpdateKeys(String kind) {
        return switch (kind) {
            case "ProtocolParameters" -> Set.of(23);
            case "ProtocolParameters(security-group)" -> Set.of(3);
            default -> null;
        };
    }

    /** A representative CCL action for a corpus lineage string (see the class comment). */
    static GovAction action(String kind) {
        return switch (kind) {
            case "ProtocolParameters" -> ParameterChangeAction.builder()
                    .protocolParamUpdate(ProtocolParamUpdate.builder().collateralPercent(150).build())
                    .build();
            case "ProtocolParameters(security-group)" -> ParameterChangeAction.builder()
                    .protocolParamUpdate(ProtocolParamUpdate.builder().maxTxSize(16_384).build())
                    .build();
            case "ConstitutionalCommittee" -> new NoConfidence(null);
            case "Constitution" -> NewConstitution.builder().build();
            case "TreasuryWithdrawals" -> TreasuryWithdrawalsAction.builder().build();
            case "Information", "Orphan" -> new InfoAction();
            default -> {
                if (kind.startsWith("HardFork(") && kind.endsWith(")")) {
                    String[] version = kind.substring("HardFork(".length(), kind.length() - 1).split("\\.");
                    yield HardForkInitiationAction.builder()
                            .protocolVersion(ProtocolVersion.builder()
                                    .major(Integer.parseInt(version[0]))
                                    .minor(Integer.parseInt(version[1]))
                                    .build())
                            .build();
                }
                throw new IllegalArgumentException("unknown proposal kind " + kind);
            }
        };
    }

    // ------------------------------------------------------------------------- CBOR fragments

    private static TransactionOutput output(byte[] cbor) {
        try {
            return TransactionOutput.deserialize(CborSerializationUtil.deserialize(cbor));
        } catch (Exception e) {
            throw new IllegalArgumentException("CCL cannot decode UTxO output " + HexUtil.encodeHexString(cbor), e);
        }
    }

    /** {@code credential = [0, hash28] / [1, hash28]} (a StakeCredential's CBOR). */
    static CredentialKey credential(byte[] cbor) {
        if (cbor.length != 32 || (cbor[0] & 0xff) != 0x82 || (cbor[2] & 0xff) != 0x58 || cbor[3] != 28) {
            throw new IllegalArgumentException("not a credential: " + HexUtil.encodeHexString(cbor));
        }
        return new CredentialKey(CredentialType.fromTag(cbor[1]),
                HexUtil.encodeHexString(Arrays.copyOfRange(cbor, 4, 32)));
    }

    /** {@code drep = [0, hash28] / [1, hash28] / [2] / [3]}. */
    static DRepTarget drepTarget(byte[] cbor) {
        if (cbor.length == 2 && (cbor[0] & 0xff) == 0x81) {
            return switch (cbor[1]) {
                case 2 -> DRepTarget.ALWAYS_ABSTAIN;
                case 3 -> DRepTarget.ALWAYS_NO_CONFIDENCE;
                default -> throw new IllegalArgumentException("not a drep: " + HexUtil.encodeHexString(cbor));
            };
        }
        return DRepTarget.credential(credential(cbor));
    }

    /** {@code transaction_input = [hash32, uint]}. */
    static Outpoint outpoint(byte[] cbor) {
        if ((cbor[0] & 0xff) != 0x82 || (cbor[1] & 0xff) != 0x58 || cbor[2] != 32) {
            throw new IllegalArgumentException("not a transaction input: " + HexUtil.encodeHexString(cbor));
        }
        String txId = HexUtil.encodeHexString(Arrays.copyOfRange(cbor, 3, 35));
        long[] head = head(cbor, 35);
        if (head[0] != 0) {
            throw new IllegalArgumentException("output index is not an unsigned integer");
        }
        return Outpoints.of(txId, Math.toIntExact(head[1]));
    }

    /**
     * The inline datum's original CBOR from a post-Alonzo output ({@code 2: [1, #6.24(bytes .cbor
     * plutus_data)]}), or null when the output has none.
     */
    static byte[] inlineDatumBytes(byte[] output) {
        long[] head = head(output, 0);
        if (head[0] != 5) {
            return null; // legacy array output: no inline datum
        }
        int offset = (int) head[2];
        long entries = head[1];
        for (long i = 0; entries < 0 || i < entries; i++) {
            if (entries < 0 && (output[offset] & 0xff) == 0xff) {
                break;
            }
            long[] key = head(output, offset);
            int valueOffset = CborItems.skip(output, offset);
            if (key[0] == 0 && key[1] == 2) {
                long[] option = head(output, valueOffset);
                int tagOffset = CborItems.skip(output, (int) option[2]);
                long[] kind = head(output, (int) option[2]);
                if (kind[0] == 0 && kind[1] == 1) {
                    long[] tag = head(output, tagOffset);
                    long[] bytes = head(output, (int) tag[2]);
                    if (tag[0] != 6 || tag[1] != 24 || bytes[0] != 2 || bytes[1] < 0) {
                        throw new IllegalArgumentException("malformed inline datum");
                    }
                    int start = (int) bytes[2];
                    return Arrays.copyOfRange(output, start, start + (int) bytes[1]);
                }
                return null;
            }
            offset = CborItems.skip(output, valueOffset);
        }
        return null;
    }

    /** @return {major type, argument (-1 when indefinite), offset after the head} */
    private static long[] head(byte[] data, int offset) {
        int initial = data[offset] & 0xff;
        int major = initial >>> 5;
        int info = initial & 0x1f;
        if (info < 24) {
            return new long[]{major, info, offset + 1};
        }
        if (info == 31) {
            return new long[]{major, -1, offset + 1};
        }
        int length = switch (info) {
            case 24 -> 1;
            case 25 -> 2;
            case 26 -> 4;
            case 27 -> 8;
            default -> throw new IllegalArgumentException("reserved CBOR additional information " + info);
        };
        long argument = 0;
        for (int i = 1; i <= length; i++) {
            argument = (argument << 8) | (data[offset + i] & 0xff);
        }
        return new long[]{major, argument, offset + 1 + length};
    }

    /** @return the number of scenarios in the corpus (a sanity check against {@link #EXPECTED_SCENARIOS}) */
    public int count() {
        return scenarioFiles().size();
    }

    /** @return the scenarios as a name → scenario map, in file order */
    public Map<String, AmaruScenario> loadAllByName() {
        Map<String, AmaruScenario> result = new LinkedHashMap<>();
        loadAll().forEach(s -> result.put(s.name(), s));
        return result;
    }
}
