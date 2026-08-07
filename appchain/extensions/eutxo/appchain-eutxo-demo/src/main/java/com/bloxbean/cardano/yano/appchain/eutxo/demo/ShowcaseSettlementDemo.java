package com.bloxbean.cardano.yano.appchain.eutxo.demo;

import com.bloxbean.cardano.client.api.model.Amount;
import com.bloxbean.cardano.client.api.model.Result;
import com.bloxbean.cardano.client.backend.api.BackendService;
import com.bloxbean.cardano.client.backend.blockfrost.service.BFBackendService;
import com.bloxbean.cardano.client.crypto.SecretKey;
import com.bloxbean.cardano.client.function.helper.SignerProviders;
import com.bloxbean.cardano.client.plutus.spec.PlutusData;
import com.bloxbean.cardano.client.quicktx.QuickTxBuilder;
import com.bloxbean.cardano.client.quicktx.Tx;
import com.bloxbean.cardano.client.spec.NetworkId;
import com.bloxbean.cardano.client.transaction.TransactionSigner;
import com.bloxbean.cardano.client.transaction.spec.Transaction;
import com.bloxbean.cardano.client.transaction.spec.TransactionBody;
import com.bloxbean.cardano.client.transaction.spec.TransactionInput;
import com.bloxbean.cardano.client.transaction.spec.TransactionOutput;
import com.bloxbean.cardano.client.transaction.spec.TransactionWitnessSet;
import com.bloxbean.cardano.client.transaction.spec.Value;
import com.bloxbean.cardano.client.transaction.util.TransactionUtil;
import com.bloxbean.cardano.yano.appchain.client.AppChainClient;
import com.bloxbean.cardano.yano.appchain.eutxo.client.EutxoClient;
import com.bloxbean.cardano.yano.appchain.eutxo.client.EutxoKeyWallet;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoOutpoint;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoReceipt;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoRecord;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoVaultDatum;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoWithdrawalDatum;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoWithdrawalRecord;

import java.math.BigInteger;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * ADR-UTXO-009: the showcase settlement demo — L1 deposit, L2 withdrawal
 * (which forms a claim), and claim status. There is deliberately NO settle
 * operation: once a claim exists the chain settles it autonomously through
 * the effect path, and watching that happen is the point of the demo.
 *
 * <p>All identities are the deterministic PUBLIC demo actors from
 * {@link ShowcaseSettlementPlan}. Demo amounts only.
 */
public final class ShowcaseSettlementDemo {
    private static final long DEPOSIT_LOVELACE = 12_000_000L;
    private static final long POLL_MILLIS = 1_000;

    private ShowcaseSettlementDemo() {
    }

    /** L1 deposit into the settlement vault; mirrors to the depositor's L2 address. */
    static Map<String, Object> deposit(String targetBase, long amountLovelace)
            throws Exception {
        long lovelace = amountLovelace > 0 ? amountLovelace : DEPOSIT_LOVELACE;
        BackendService backend = new BFBackendService(
                targetBase + "/api/v1/", "demo");
        EutxoKeyWallet depositor = EutxoKeyWallet.fromSeed(
                ShowcaseSettlementPlan.DEPOSITOR_L2_SEED);
        EutxoVaultDatum datum = new EutxoVaultDatum(
                EutxoVaultDatum.ABI_VERSION,
                ShowcaseSettlementPlan.CHAIN_ID,
                depositor.address(),
                nonce("deposit", lovelace),
                new EutxoOutpoint("44".repeat(32), 0),
                10_000_000L);
        Result<String> result = new QuickTxBuilder(backend)
                .compose(new Tx()
                        .payToContract(
                                ShowcaseSettlementPlan.PLAN.vaultAddress(),
                                List.of(Amount.lovelace(
                                        BigInteger.valueOf(lovelace))),
                                PlutusData.deserialize(datum.encode()))
                        .from(ShowcaseSettlementPlan.OPERATOR_ADDRESS))
                .withSigner(SignerProviders.signerFrom(
                        SecretKey.create(ShowcaseSettlementPlan.OPERATOR_SEED)))
                .completeAndWait();
        if (!result.isSuccessful()) {
            throw new IllegalStateException(
                    "deposit rejected: " + result.getResponse());
        }
        EutxoClient eutxo = eutxoClient(targetBase);
        EutxoOutpoint mirrored = awaitMirroredFunds(eutxo, depositor.address());
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("chainId", ShowcaseSettlementPlan.CHAIN_ID);
        payload.put("l1Transaction", result.getValue());
        payload.put("lovelace", lovelace);
        payload.put("l2Address", depositor.address());
        payload.put("mirroredOutpoint", mirrored.toString());
        return payload;
    }

    /**
     * L2 withdrawal: pay the withdrawal address, forming a claim. The node
     * settles it on its own — poll {@code status} to watch it confirm.
     */
    static Map<String, Object> withdraw(String targetBase) throws Exception {
        EutxoClient eutxo = eutxoClient(targetBase);
        EutxoKeyWallet depositor = EutxoKeyWallet.fromSeed(
                ShowcaseSettlementPlan.DEPOSITOR_L2_SEED);
        List<EutxoRecord> funds = eutxo.utxos(depositor.address());
        if (funds.isEmpty()) {
            throw new IllegalStateException(
                    "no mirrored L2 funds — run: settlement deposit first");
        }
        EutxoRecord source = funds.getFirst();
        // The L2 ledger conserves value EXACTLY (no fees), so the claim must
        // carry the whole mirrored UTxO — never a second hardcoded amount that
        // has to be kept in step with the deposit's.
        BigInteger claimLovelace = lovelaceOf(source);
        String withdrawalAddress = EutxoKeyWallet.fromSeed(
                ShowcaseSettlementPlan.WITHDRAWAL_L2_SEED).address();
        EutxoWithdrawalDatum datum = new EutxoWithdrawalDatum(
                EutxoWithdrawalDatum.ABI_VERSION,
                ShowcaseSettlementPlan.CHAIN_ID,
                0,
                ShowcaseSettlementPlan.PAYOUT_ADDRESS,
                // A source UTxO is spendable once, so its outpoint is a
                // unique — and, unlike an identity hash, reproducible — nonce.
                nonce("withdrawal", source.outpoint().toString()));
        TransactionBody body = TransactionBody.builder()
                .inputs(List.of(new TransactionInput(
                        source.outpoint().transactionId(),
                        source.outpoint().index())))
                .outputs(List.of(TransactionOutput.builder()
                        .address(withdrawalAddress)
                        .value(Value.fromCoin(claimLovelace))
                        .inlineDatum(PlutusData.deserialize(datum.encode()))
                        .build()))
                .fee(BigInteger.ZERO)
                .ttl(10_000_000L)
                .networkId(NetworkId.TESTNET)
                .build();
        Transaction signed = TransactionSigner.INSTANCE.sign(
                Transaction.builder().body(body)
                        .witnessSet(new TransactionWitnessSet())
                        .isValid(true).build(),
                depositor.signingKey());
        byte[] cbor = signed.serialize();
        eutxo.submit(cbor);
        // Submission only means the message was sequenced. The claim exists
        // only once the machine ACCEPTS it — surface a rejection here instead
        // of reporting success and leaving 'status' mysteriously empty.
        awaitAccepted(eutxo, TransactionUtil.getTxHash(cbor));
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("chainId", ShowcaseSettlementPlan.CHAIN_ID);
        payload.put("l2Transaction", TransactionUtil.getTxHash(cbor));
        payload.put("lovelace", claimLovelace);
        payload.put("payoutAddress", ShowcaseSettlementPlan.PAYOUT_ADDRESS);
        payload.put("note", "the claim is now pending; the federation settles"
                + " it autonomously — poll: settlement status");
        return payload;
    }

    /** Every claim and its settlement state. */
    static Map<String, Object> status(String targetBase) throws Exception {
        AppChainClient client = AppChainClient.builder(targetBase + "/api/v1/")
                .chainId(ShowcaseSettlementPlan.CHAIN_ID)
                .build();
        var response = client.query(
                com.bloxbean.cardano.yano.appchain.eutxo.contracts
                        .EutxoQueryCodec.WITHDRAWALS_PATH,
                com.bloxbean.cardano.yano.appchain.eutxo.contracts
                        .EutxoQueryCodec.lifecyclePageRequest(0, 50));
        List<EutxoWithdrawalRecord> records =
                com.bloxbean.cardano.yano.appchain.eutxo.contracts
                        .EutxoQueryCodec.decodeWithdrawalRecords(
                                response.payload());
        List<Map<String, Object>> claims = new ArrayList<>();
        for (EutxoWithdrawalRecord record : records) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("claimId", record.claim().claimId());
            item.put("status", record.status().name());
            item.put("payout", record.claim().lovelace());
            item.put("bounty", record.claim().bounty());
            item.put("destination", record.claim().destinationAddress());
            claims.add(item);
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("chainId", ShowcaseSettlementPlan.CHAIN_ID);
        payload.put("claims", claims);
        payload.put("settled", claims.stream().filter(
                c -> "CONFIRMED".equals(c.get("status"))).count());
        return payload;
    }

    private static EutxoOutpoint awaitMirroredFunds(
            EutxoClient eutxo, String address) throws Exception {
        long deadline = System.currentTimeMillis() + 90_000;
        while (System.currentTimeMillis() < deadline) {
            List<EutxoRecord> funds = eutxo.utxos(address);
            if (!funds.isEmpty()) {
                return funds.getFirst().outpoint();
            }
            Thread.sleep(POLL_MILLIS);
        }
        throw new IllegalStateException(
                "the deposit did not mirror into L2 within the stability window");
    }

    private static EutxoClient eutxoClient(String targetBase) {
        return new EutxoClient(AppChainClient.builder(targetBase + "/api/v1/")
                .chainId(ShowcaseSettlementPlan.CHAIN_ID)
                .build());
    }

    /** Block until the machine has ruled on {@code transactionId}. */
    private static void awaitAccepted(EutxoClient eutxo, String transactionId)
            throws Exception {
        long deadline = System.currentTimeMillis() + 60_000;
        while (System.currentTimeMillis() < deadline) {
            var receipt = eutxo.transaction(transactionId);
            if (receipt.isPresent()) {
                var value = receipt.get();
                if (value.status() == EutxoReceipt.Status.ACCEPTED) {
                    return;
                }
                throw new IllegalStateException("withdrawal rejected by the "
                        + "settlement machine: " + value.code()
                        + (value.detail() == null || value.detail().isBlank()
                        ? "" : " (" + value.detail() + ")"));
            }
            Thread.sleep(POLL_MILLIS);
        }
        throw new IllegalStateException(
                "withdrawal " + transactionId + " was never ruled on");
    }

    private static byte[] nonce(String label, long salt) throws Exception {
        return nonce(label, Long.toString(salt));
    }

    private static byte[] nonce(String label, String salt) throws Exception {
        return MessageDigest.getInstance("SHA-256").digest(
                (label + ':' + salt).getBytes(StandardCharsets.UTF_8));
    }

    /** The committed lovelace of a mirrored L2 record. */
    private static BigInteger lovelaceOf(EutxoRecord record) throws Exception {
        return TransactionOutput.deserialize(
                        com.bloxbean.cardano.client.common.cbor.CborSerializationUtil
                                .deserialize(record.outputCbor()))
                .getValue().getCoin();
    }
}
