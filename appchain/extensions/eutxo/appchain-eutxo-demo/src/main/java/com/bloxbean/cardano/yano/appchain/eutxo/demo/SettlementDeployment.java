package com.bloxbean.cardano.yano.appchain.eutxo.demo;

import com.bloxbean.cardano.client.api.model.Utxo;
import com.bloxbean.cardano.client.backend.api.BackendService;
import com.bloxbean.cardano.client.backend.api.DefaultUtxoSupplier;
import com.bloxbean.cardano.client.backend.blockfrost.service.BFBackendService;
import com.bloxbean.cardano.client.common.model.Networks;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoOutpoint;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoProfile;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * ADR-UTXO-009 §13.2: deploying a settlement identity on a PUBLIC network
 * with the operator's own key, the counterpart to the packaged devnet demo.
 *
 * <p>Two steps, mirroring preprod anchoring:
 * <ol>
 *   <li>{@link #prepare} — derive the operator address from the key file and
 *       report exactly how much to send and in how many UTxOs;</li>
 *   <li>{@link #bootstrap} — pick two one-shot seeds from the funded address,
 *       deploy on the PRODUCTION profile, and emit the chain-config block.</li>
 * </ol>
 *
 * <p>The production profile ({@link EutxoProfile#V3}) is not negotiable here:
 * its ~6 h fallback floor is the real safety window, and the relaxed devnet
 * profile is refused off devnet by the state-machine provider anyway.
 */
public final class SettlementDeployment {
    /** Both one-shot mints need their own input, plus fees. */
    public static final int MIN_FUNDING_UTXOS = 2;

    private SettlementDeployment() {
    }

    /**
     * Where to send the funds and how much. Derivation only — no node call, so
     * this cannot fail because the node is down, still syncing, or has never
     * seen the address. Whether the funding has landed is answered by
     * {@link #bootstrap}, which submits nothing until it has.
     */
    public static Map<String, Object> prepare(
            SettlementOperatorIdentity identity, String network) {
        long required = SettlementBootstrapWorkflow.requiredFundingLovelace();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("network", network);
        payload.put("chainId", ShowcaseSettlementPlan.CHAIN_ID);
        payload.put("profile", EutxoProfile.V3.id());
        payload.put("fundThisAddress", identity.operatorAddress());
        payload.put("requiredLovelace", required);
        payload.put("requiredAda", ada(required));
        payload.put("minimumUtxos", MIN_FUNDING_UTXOS);
        payload.put("note", "send at least " + ada(required) + " ADA to the"
                + " address above, split across " + MIN_FUNDING_UTXOS + " or"
                + " more pure-ADA UTxOs. Once the node is running and synced"
                + " past that transaction, run: settlement bootstrap (it"
                + " reports what is still missing and submits nothing until"
                + " the funding is in place).");
        return payload;
    }

    private static String rootCause(Throwable failure) {
        Throwable cause = failure;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        String message = cause.getMessage();
        return message == null || message.isBlank()
                ? cause.getClass().getSimpleName() : message.trim();
    }

    /**
     * Deploy the settlement identity. Resumable: the chosen seed outpoints are
     * recorded in {@code stateDir} on first use and reused afterwards, so a
     * re-run after a partial failure completes the SAME identity instead of
     * starting a second one.
     *
     * @param members the chain's live federation keys — the root datum commits
     *                to them, so they must match the running app chain
     */
    public static Map<String, Object> bootstrap(
            String apiBase, SettlementOperatorIdentity identity, String network,
            List<String> members, int threshold, Path stateDir, Path keyFile,
            Path scriptDir) throws Exception {
        if (members == null || members.isEmpty()) {
            throw new IllegalArgumentException(
                    "the settlement identity commits to the chain's federation"
                            + " members; none were supplied");
        }
        if (threshold < 1 || threshold > members.size()) {
            throw new IllegalArgumentException(
                    "threshold must be between 1 and the member count");
        }
        // SettlementBootstrapWorkflow takes a base that ALREADY includes the
        // API path — pass the same one used here, never the raw host.
        String backendBase = apiBase + "/api/v1/";
        BackendService backend = new BFBackendService(backendBase, "demo");
        String chainId = ShowcaseSettlementPlan.CHAIN_ID;

        Optional<SettlementDeploymentRecord> existing =
                SettlementDeploymentRecord.load(stateDir, chainId);
        existing.ifPresent(record -> record.requireMatches(chainId, network));
        EutxoOutpoint rootSeed;
        EutxoOutpoint shardSeed;
        if (existing.isPresent()) {
            rootSeed = existing.get().rootSeed();
            shardSeed = existing.get().shardSeed();
        } else {
            List<Utxo> funds;
            try {
                funds = pureAdaUtxos(backend, identity.operatorAddress());
            } catch (Exception unreadable) {
                // An address the node has never seen reads as an API error,
                // not an empty set — report it as the unfunded state it is.
                throw new IllegalStateException("cannot read UTxOs at operator"
                        + " address " + identity.operatorAddress() + " ("
                        + rootCause(unreadable) + "). Is the node running and"
                        + " synced past the funding transaction?"
                        + " Run: settlement prepare", unreadable);
            }
            long available = funds.stream()
                    .mapToLong(SettlementDeployment::lovelace).sum();
            long required = SettlementBootstrapWorkflow.requiredFundingLovelace();
            if (funds.size() < MIN_FUNDING_UTXOS || available < required) {
                throw new IllegalStateException("operator address "
                        + identity.operatorAddress() + " holds " + ada(available)
                        + " ADA across " + funds.size() + " pure-ADA UTxO(s);"
                        + " the deploy needs " + ada(required) + " ADA across at"
                        + " least " + MIN_FUNDING_UTXOS
                        + ". Run: settlement prepare");
            }
            rootSeed = outpoint(funds.get(0));
            shardSeed = outpoint(funds.get(1));
        }

        SettlementBootstrapPlan plan = SettlementBootstrapPlan.plan(
                rootSeed, shardSeed,
                new SettlementBootstrapPlan.Config(
                        chainId, 0, Networks.testnet(),
                        ShowcaseSettlementPlan.ROOT_TOKEN
                                .getBytes(StandardCharsets.UTF_8),
                        List.copyOf(members), threshold, 0,
                        EutxoProfile.V3.fallbackDelayMinSlots()));

        SettlementDeploymentRecord record = new SettlementDeploymentRecord(
                chainId, network, plan.profile().id(), rootSeed, shardSeed,
                identity.operatorAddress(), plan.vaultAddress(),
                plan.shardAddress(), plan.rootAddress());
        // Persist BEFORE submitting: a crash mid-deploy must not orphan the
        // identity the funds are already committed to.
        record.save(stateDir);

        String transaction = SettlementBootstrapWorkflow.bootstrap(
                backendBase, plan, rootSeed, shardSeed,
                identity.operatorSeed(), identity.operatorAddress());
        Path scripts = ShowcaseSettlementPlan.writeScripts(plan, scriptDir);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("chainId", chainId);
        payload.put("network", network);
        payload.put("profile", plan.profile().id());
        payload.put("profileDigest", plan.profile().digestHex());
        payload.put("bootstrapTransaction", transaction);
        payload.put("operatorAddress", identity.operatorAddress());
        payload.put("vaultAddress", plan.vaultAddress());
        payload.put("shardAddress", plan.shardAddress());
        payload.put("rootAddress", plan.rootAddress());
        payload.put("payoutAddress", identity.payoutAddress());
        payload.put("scriptDirectory", scripts.toString());
        payload.put("deploymentRecord",
                SettlementDeploymentRecord.path(stateDir, chainId).toString());
        payload.put("configBlock", ShowcaseSettlementPlan.yamlBlock(
                ShowcaseSettlementPlan.chainIndexPlaceholder(), chainId,
                ShowcaseSettlementPlan.configProperties(
                        plan, chainId,
                        withdrawalAddress(identity),
                        identity.operatorAddress(),
                        // The operator's key stays in its owner-only file; the
                        // chain YAML only ever names the path.
                        null, keyFile.toAbsolutePath().toString(),
                        network, scriptDir.toString())));
        return payload;
    }

    /** The single claim-forming L2 address for this operator identity. */
    public static String withdrawalAddress(SettlementOperatorIdentity identity) {
        return com.bloxbean.cardano.yano.appchain.eutxo.client.EutxoKeyWallet
                .fromSeed(identity.withdrawalL2Seed()).address();
    }

    // ------------------------------------------------------------------

    /**
     * Pure-ADA UTxOs, largest first — a one-shot seed carrying native assets
     * would drag them into the mint transaction.
     */
    private static List<Utxo> pureAdaUtxos(BackendService backend, String address)
            throws Exception {
        List<Utxo> pure = new ArrayList<>();
        for (Utxo utxo : new DefaultUtxoSupplier(backend.getUtxoService())
                .getAll(address)) {
            boolean adaOnly = utxo.getAmount() != null
                    && utxo.getAmount().size() == 1
                    && "lovelace".equals(utxo.getAmount().getFirst().getUnit());
            if (adaOnly && utxo.getDataHash() == null
                    && utxo.getInlineDatum() == null) {
                pure.add(utxo);
            }
        }
        pure.sort(Comparator.comparingLong(SettlementDeployment::lovelace).reversed());
        return pure;
    }

    private static long lovelace(Utxo utxo) {
        return utxo.getAmount().stream()
                .filter(amount -> "lovelace".equals(amount.getUnit()))
                .map(amount -> amount.getQuantity() == null
                        ? BigInteger.ZERO : amount.getQuantity())
                .findFirst().orElse(BigInteger.ZERO)
                .longValue();
    }

    private static EutxoOutpoint outpoint(Utxo utxo) {
        return new EutxoOutpoint(utxo.getTxHash(), utxo.getOutputIndex());
    }

    private static String ada(long lovelace) {
        return new java.math.BigDecimal(lovelace)
                .movePointLeft(6).stripTrailingZeros().toPlainString();
    }
}
