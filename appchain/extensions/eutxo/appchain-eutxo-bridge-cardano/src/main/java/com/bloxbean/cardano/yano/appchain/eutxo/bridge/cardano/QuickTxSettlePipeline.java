package com.bloxbean.cardano.yano.appchain.eutxo.bridge.cardano;

import com.bloxbean.cardano.client.address.Address;
import com.bloxbean.cardano.client.api.model.Amount;
import com.bloxbean.cardano.client.api.model.Utxo;
import com.bloxbean.cardano.client.crypto.SecretKey;
import com.bloxbean.cardano.client.plutus.spec.BigIntPlutusData;
import com.bloxbean.cardano.client.plutus.spec.BytesPlutusData;
import com.bloxbean.cardano.client.plutus.spec.ConstrPlutusData;
import com.bloxbean.cardano.client.plutus.spec.ListPlutusData;
import com.bloxbean.cardano.client.plutus.spec.PlutusData;
import com.bloxbean.cardano.client.plutus.spec.PlutusV3Script;
import com.bloxbean.cardano.client.quicktx.QuickTxBuilder;
import com.bloxbean.cardano.client.quicktx.ScriptTx;
import com.bloxbean.cardano.client.transaction.TransactionSigner;
import com.bloxbean.cardano.client.transaction.spec.Transaction;
import com.bloxbean.cardano.yano.appchain.eutxo.client.NullifierShardMirror;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoBatchSettlementMarker;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoSettlementBatch;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoShardDatum;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoWithdrawalClaim;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * ADR-UTXO-009 SP-M6: the owner node's live A2 settle engine — the exact
 * transaction shape the devnet gate proved, assembled programmatically
 * against the node's own surfaces:
 *
 * <ol>
 *   <li>resolve the batch's still-PENDING claims from committed state and
 *       group them by nullifier shard (a shard validates only its own
 *       nibble),</li>
 *   <li>per shard group: reconstruct the shard's mirror from the settled-id
 *       set (SP-M4 — the root is a pure function of that set), verify it
 *       against the ON-CHAIN shard datum, plan the inserts, and assemble
 *       the Settle transaction with QuickTx (vault + shard spends with
 *       redeemers, root reference input, positional payouts, remainder
 *       under the batch marker, shard continuation; ex-units from the
 *       node's evaluator),</li>
 *   <li>run the federation co-sign round over the built body, add the
 *       operator wallet witness (fees/collateral), submit through the node,
 *       and wait for the outputs to appear before the next group.</li>
 * </ol>
 *
 * A batch that spans multiple shards settles as multiple sequential
 * transactions inside one effect execution; a group that cannot complete
 * throws — the effect retries and re-resolves only the claims that are
 * still pending (settled claims drop out of the range, and the nullifier
 * makes double-settlement impossible on-chain).
 */
final class QuickTxSettlePipeline {
    private static final long CONFIRM_POLL_MILLIS = 1_000;

    private final SettlementWiring wiring;
    private final SettlementClaimsView claimsView;
    private final SettlementCosignService cosign;
    private final NodeSettlementBackend backend;
    private final QuickTxBuilder quickTxBuilder;
    private final java.util.function.Supplier<
            com.bloxbean.cardano.yano.api.utxo.UtxoState> utxoView;
    private final java.util.function.Supplier<java.util.Set<String>> members;
    private final PlutusV3Script vaultScript;
    private final PlutusV3Script shardScript;

    QuickTxSettlePipeline(
            SettlementWiring wiring,
            SettlementClaimsView claimsView,
            SettlementCosignService cosign,
            NodeSettlementBackend backend,
            QuickTxBuilder quickTxBuilder,
            java.util.function.Supplier<
                    com.bloxbean.cardano.yano.api.utxo.UtxoState> utxoView,
            java.util.function.Supplier<java.util.Set<String>> members,
            PlutusV3Script vaultScript,
            PlutusV3Script shardScript) {
        this.wiring = Objects.requireNonNull(wiring, "wiring");
        this.claimsView = Objects.requireNonNull(claimsView, "claimsView");
        this.cosign = Objects.requireNonNull(cosign, "cosign");
        this.backend = Objects.requireNonNull(backend, "backend");
        this.quickTxBuilder = Objects.requireNonNull(quickTxBuilder, "quickTxBuilder");
        this.utxoView = Objects.requireNonNull(utxoView, "utxoView");
        this.members = Objects.requireNonNull(members, "members");
        this.vaultScript = Objects.requireNonNull(vaultScript, "vaultScript");
        this.shardScript = Objects.requireNonNull(shardScript, "shardScript");
    }

    /**
     * Settle every still-pending claim of the batch; returns the LAST
     * transaction id, or null when nothing remained to settle.
     */
    String settle(EutxoSettlementBatch batch) throws Exception {
        List<EutxoWithdrawalClaim> pending = claimsView.pendingClaimsInRange(batch);
        if (pending.isEmpty()) {
            return null;
        }
        Map<Integer, List<EutxoWithdrawalClaim>> byShard = new LinkedHashMap<>();
        for (EutxoWithdrawalClaim claim : pending) {
            int shard = HexFormat.of().parseHex(claim.claimId())[31] & 0x0F;
            byShard.computeIfAbsent(shard, key -> new ArrayList<>()).add(claim);
        }
        String lastTransactionId = null;
        for (Map.Entry<Integer, List<EutxoWithdrawalClaim>> group
                : byShard.entrySet()) {
            lastTransactionId = settleGroup(group.getKey(), group.getValue());
        }
        return lastTransactionId;
    }

    private String settleGroup(int shard, List<EutxoWithdrawalClaim> claims)
            throws Exception {
        var view = Objects.requireNonNull(utxoView.get(), "L1 view unavailable");

        // --- the shard thread + its on-chain root ------------------------
        String shardUnit = wiring.shardThreadPolicyIdHex()
                + HexFormat.of().formatHex(new byte[] {(byte) shard});
        var shardThread = tokenUtxo(view, wiring.shardAddress(), shardUnit);
        EutxoShardDatum onChainDatum = EutxoShardDatum.decode(
                HexFormat.of().parseHex(Objects.requireNonNull(
                        shardThread.getInlineDatum(), "shard thread datum")));

        // --- reconstruct the mirror; it must agree with the chain --------
        NullifierShardMirror mirror = new NullifierShardMirror();
        for (byte[] settled : claimsView.settledClaimIdsForShard(shard)) {
            mirror.insert(settled);
        }
        if (!java.util.Arrays.equals(
                mirror.root(shard), onChainDatum.nullifierRoot())) {
            throw new IllegalStateException("shard " + shard
                    + " mirror root does not match the on-chain root yet"
                    + " — settled state still syncing");
        }
        List<byte[]> ids = new ArrayList<>();
        for (EutxoWithdrawalClaim claim : claims) {
            ids.add(HexFormat.of().parseHex(claim.claimId()));
        }
        NullifierShardMirror.InsertPlan plan = mirror.planInserts(shard, ids);

        // --- redeemers ---------------------------------------------------
        List<PlutusData> claimData = new ArrayList<>();
        for (EutxoWithdrawalClaim claim : claims) {
            claimData.add(claimData(claim));
        }
        PlutusData vaultRedeemer = ConstrPlutusData.of(0,
                ListPlutusData.of(claimData.toArray(new PlutusData[0])));
        List<PlutusData> inserts = new ArrayList<>();
        for (NullifierShardMirror.PlannedInsert insert : plan.inserts()) {
            inserts.add(ConstrPlutusData.of(0,
                    BytesPlutusData.of(insert.claimId()),
                    PlutusData.deserialize(insert.proofWire())));
        }
        PlutusData shardRedeemer = ConstrPlutusData.of(0,
                ListPlutusData.of(inserts.toArray(new PlutusData[0])));

        // --- vault inventory + root reference ----------------------------
        // Spend the ENTIRE vault inventory: consolidation keeps custody in
        // one continuing UTxO, and — critically — guarantees the spent set
        // includes TRACKED deposit outpoints (a settle can only follow a
        // deposit), which the ledger's custody gate requires; the bootstrap
        // genesis fund alone is not tracked custody.
        List<Utxo> vaultInventory = lovelaceOnly(
                view.getUtxosByAddress(wiring.vaultAddress(), 1, 100));
        var rootThread = tokenUtxo(view, wiring.rootAddress(), wiring.rootUnit());

        BigInteger payoutTotal = BigInteger.ZERO;
        BigInteger bountyTotal = BigInteger.ZERO;
        for (EutxoWithdrawalClaim claim : claims) {
            payoutTotal = payoutTotal.add(claim.lovelace());
            bountyTotal = bountyTotal.add(claim.bounty());
        }
        BigInteger outflow = payoutTotal.add(bountyTotal);
        List<Utxo> selected = new ArrayList<>(vaultInventory);
        BigInteger gathered = BigInteger.ZERO;
        for (Utxo vault : selected) {
            gathered = gathered.add(lovelace(vault));
        }
        BigInteger remainder = gathered.subtract(outflow);
        if (remainder.signum() <= 0) {
            throw new IllegalStateException(
                    "vault inventory cannot fund the settle outflow");
        }

        EutxoBatchSettlementMarker marker = new EutxoBatchSettlementMarker(1,
                claims.stream().map(EutxoWithdrawalClaim::claimId).toList());
        EutxoShardDatum nextDatum = onChainDatum.withRoot(plan.nextRoot());

        // --- assemble (the devnet-gate shape) ----------------------------
        ScriptTx settleTx = new ScriptTx()
                .collectFrom(selected, vaultRedeemer)
                .collectFrom(shardThread, shardRedeemer)
                .readFrom(rootThread);
        for (EutxoWithdrawalClaim claim : claims) {
            settleTx = settleTx.payToAddress(claim.destinationAddress(),
                    Amount.lovelace(claim.lovelace()));
        }
        settleTx = settleTx
                .payToContract(wiring.vaultAddress(),
                        List.of(Amount.lovelace(remainder)),
                        PlutusData.deserialize(marker.encode()))
                .payToContract(wiring.shardAddress(),
                        List.of(Amount.lovelace(lovelace(shardThread)),
                                new Amount(shardUnit, BigInteger.ONE)),
                        PlutusData.deserialize(nextDatum.encode()))
                .attachSpendingValidator(vaultScript)
                .attachSpendingValidator(shardScript);

        List<byte[]> requiredSigners = new ArrayList<>();
        for (String member : members.get()) {
            requiredSigners.add(com.bloxbean.cardano.client.crypto.Blake2bUtil
                    .blake2bHash224(HexFormat.of().parseHex(
                            member.trim().toLowerCase(java.util.Locale.ROOT))));
        }
        Transaction unsigned = quickTxBuilder.compose(settleTx)
                .feePayer(wiring.operatorAddress())
                .collateralPayer(wiring.operatorAddress())
                .withRequiredSigners(requiredSigners.toArray(new byte[0][]))
                // Fee must budget the witnesses added AFTER build: one vkey
                // witness per federation member (the co-sign round) plus the
                // operator wallet witness.
                .additionalSignersCount(requiredSigners.size() + 1)
                .build();

        // --- co-sign, operator witness, submit, confirm ------------------
        byte[] cosigned = cosign.cosignTransaction(unsigned.serialize());
        Transaction complete = TransactionSigner.INSTANCE.sign(
                Transaction.deserialize(cosigned),
                SecretKey.create(wiring.operatorSecretSeed()));
        CardanoSettlementBackend.Submission submission =
                backend.submit(complete.serialize());
        awaitConfirmation(submission.transactionId());
        return submission.transactionId();
    }

    private void awaitConfirmation(String transactionId) throws Exception {
        long deadline = System.currentTimeMillis()
                + wiring.confirmTimeout().toMillis();
        while (System.currentTimeMillis() < deadline) {
            if (backend.status(transactionId)
                    == CardanoSettlementBackend.Status.CONFIRMED) {
                return;
            }
            Thread.sleep(CONFIRM_POLL_MILLIS);
        }
        throw new IllegalStateException("settle transaction " + transactionId
                + " did not confirm within the group window");
    }

    /**
     * Claim redeemer field: the destination as a Plutus {@code Address}.
     *
     * <p>Both enterprise and base destinations are supported — wallets hand
     * out base addresses, and the vault validator already fingerprints either
     * form ({@code Nothing} vs {@code Just (StakingHash …)}). The staking part
     * must be encoded FAITHFULLY: the validator compares the paying output's
     * address to this value with {@code equalsData}, so an enterprise
     * encoding for a base destination would simply never match.
     */
    /**
     * {@code Maybe StakingCredential}: {@code Nothing} for an enterprise
     * address, {@code Just (StakingHash cred)} for a base one. Pointer
     * addresses are refused — the validator only fingerprints StakingHash, so
     * a pointer destination could never be paid.
     */
    private static PlutusData stakingPart(Address destination) {
        var delegation = destination.getDelegationCredential();
        if (delegation.isEmpty()) {
            return ConstrPlutusData.of(1);
        }
        var credential = delegation.get();
        int tag = credential.getType()
                == com.bloxbean.cardano.client.address.CredentialType.Key ? 0 : 1;
        // Just ( StakingHash ( Credential ) )
        return ConstrPlutusData.of(0,
                ConstrPlutusData.of(0,
                        ConstrPlutusData.of(tag,
                                BytesPlutusData.of(credential.getBytes()))));
    }

    static PlutusData claimData(EutxoWithdrawalClaim claim) {
        Address destination = new Address(claim.destinationAddress());
        var credential = destination.getPaymentCredential().orElseThrow(
                () -> new IllegalStateException(
                        "destination has no payment credential"));
        int credentialTag = credential.getType()
                == com.bloxbean.cardano.client.address.CredentialType.Key ? 0 : 1;
        PlutusData destinationData = ConstrPlutusData.of(0,
                ConstrPlutusData.of(credentialTag,
                        BytesPlutusData.of(credential.getBytes())),
                stakingPart(destination));
        return ConstrPlutusData.of(0,
                BigIntPlutusData.of(claim.bridgeEpoch()),
                BigIntPlutusData.of(claim.settlementSequence()),
                BytesPlutusData.of(HexFormat.of().parseHex(claim.claimId())),
                destinationData,
                BigIntPlutusData.of(claim.lovelace()),
                BigIntPlutusData.of(claim.bounty()));
    }

    private static Utxo tokenUtxo(
            com.bloxbean.cardano.yano.api.utxo.UtxoState view,
            String address, String unit) {
        for (var utxo : view.getUtxosByAddress(address, 1, 100)) {
            Utxo converted = CclNodeAdapters.convert(utxo);
            for (Amount amount : converted.getAmount()) {
                if (unit.equals(amount.getUnit())) {
                    return converted;
                }
            }
        }
        throw new IllegalStateException(
                "thread token " + unit + " not found at " + address);
    }

    private static List<Utxo> lovelaceOnly(
            List<com.bloxbean.cardano.yano.api.utxo.model.Utxo> utxos) {
        List<Utxo> result = new ArrayList<>();
        for (var utxo : utxos) {
            if (utxo.assets() == null || utxo.assets().isEmpty()) {
                result.add(CclNodeAdapters.convert(utxo));
            }
        }
        return result;
    }

    private static BigInteger lovelace(Utxo utxo) {
        for (Amount amount : utxo.getAmount()) {
            if ("lovelace".equals(amount.getUnit())) {
                return amount.getQuantity();
            }
        }
        return BigInteger.ZERO;
    }
}
