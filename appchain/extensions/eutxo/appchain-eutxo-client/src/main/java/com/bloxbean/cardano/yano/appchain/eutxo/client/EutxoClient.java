package com.bloxbean.cardano.yano.appchain.eutxo.client;

import com.bloxbean.cardano.yano.appchain.client.MpfProofConverter;

import com.bloxbean.cardano.client.transaction.spec.Transaction;
import com.bloxbean.cardano.yano.appchain.client.AppChainClient;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoContract;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoDepositRecord;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoOutpoint;
import com.bloxbean.cardano.yano.appchain.proofs.MpfNormalizedProof;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoL2ParameterSnapshot;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoQueryCodec;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoReceipt;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoRecord;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoReserve;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoStateKeys;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoWithdrawalRecord;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoValidityTransition;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Typed facade over Yano's generic submit, query, and MPF-proof APIs. */
public final class EutxoClient {
    private final AppChainClient client;

    public EutxoClient(AppChainClient client) {
        this.client = Objects.requireNonNull(client, "client");
    }

    public AppChainClient.SubmitResult submit(Transaction transaction) {
        Objects.requireNonNull(transaction, "transaction");
        try {
            return submit(transaction.serialize());
        } catch (Exception failure) {
            throw new IllegalArgumentException("transaction cannot be encoded", failure);
        }
    }

    public AppChainClient.SubmitResult submit(byte[] signedTransactionCbor) {
        return client.submit(EutxoContract.TRANSACTION_TOPIC, signedTransactionCbor);
    }

    public Optional<EutxoRecord> utxo(EutxoOutpoint outpoint) {
        return utxoSnapshot(outpoint).value();
    }

    public EutxoSnapshot<Optional<EutxoRecord>> utxoSnapshot(EutxoOutpoint outpoint) {
        AppChainClient.QueryResult result = client.query(
                EutxoQueryCodec.OUTPOINT_PATH,
                EutxoQueryCodec.outpointRequest(outpoint));
        return snapshot(result, Optional.ofNullable(
                EutxoQueryCodec.decodeOptionalRecord(result.payload())));
    }

    public List<EutxoRecord> utxos(String address) {
        return utxosSnapshot(address).value();
    }

    public EutxoSnapshot<List<EutxoRecord>> utxosSnapshot(String address) {
        AppChainClient.QueryResult result = client.query(
                EutxoQueryCodec.ADDRESS_PATH,
                EutxoQueryCodec.addressRequest(address));
        return snapshot(result, EutxoQueryCodec.decodeRecords(result.payload()));
    }

    public Optional<EutxoReceipt> transaction(String transactionId) {
        return transactionSnapshot(transactionId).value();
    }

    public EutxoSnapshot<Optional<EutxoReceipt>> transactionSnapshot(String transactionId) {
        AppChainClient.QueryResult result = client.query(
                EutxoQueryCodec.TRANSACTION_PATH,
                EutxoQueryCodec.transactionRequest(transactionId));
        return snapshot(result, Optional.ofNullable(
                EutxoQueryCodec.decodeOptionalReceipt(result.payload())));
    }

    public Optional<EutxoReceipt> attempt(byte[] appMessageId) {
        return attemptSnapshot(appMessageId).value();
    }

    public EutxoSnapshot<Optional<EutxoReceipt>> attemptSnapshot(byte[] appMessageId) {
        AppChainClient.QueryResult result = client.query(
                EutxoQueryCodec.ATTEMPT_PATH,
                EutxoQueryCodec.attemptRequest(appMessageId));
        return snapshot(result, Optional.ofNullable(
                EutxoQueryCodec.decodeOptionalReceipt(result.payload())));
    }

    public Optional<AppChainClient.Proof> proof(EutxoOutpoint outpoint) {
        return client.proof(EutxoStateKeys.utxo(outpoint));
    }

    public EutxoSnapshot<Optional<EutxoDepositRecord>> depositSnapshot(
            EutxoOutpoint acceptedL1Outpoint
    ) {
        AppChainClient.QueryResult result = client.query(
                EutxoQueryCodec.DEPOSIT_PATH,
                EutxoQueryCodec.depositRequest(acceptedL1Outpoint));
        return snapshot(result, Optional.ofNullable(
                EutxoQueryCodec.decodeOptionalDepositRecord(result.payload())));
    }

    public EutxoSnapshot<Optional<EutxoReserve>> reserveSnapshot(String assetId) {
        AppChainClient.QueryResult result = client.query(
                EutxoQueryCodec.RESERVE_PATH,
                EutxoQueryCodec.reserveRequest(assetId));
        return snapshot(result, Optional.ofNullable(
                EutxoQueryCodec.decodeOptionalReserve(result.payload())));
    }

    public EutxoSnapshot<Optional<EutxoWithdrawalRecord>> withdrawalSnapshot(
            String claimId
    ) {
        AppChainClient.QueryResult result = client.query(
                EutxoQueryCodec.WITHDRAWAL_PATH,
                EutxoQueryCodec.withdrawalRequest(claimId));
        return snapshot(result, Optional.ofNullable(
                EutxoQueryCodec.decodeOptionalWithdrawalRecord(result.payload())));
    }

    /**
     * Fetches the exact finalized Cardano transition witness committed by the
     * validity root. The returned snapshot height lets a prover reject reads
     * that are not yet final under its configured policy.
     */
    public EutxoSnapshot<Optional<EutxoValidityTransition>>
            finalizedValidityTransition(long appHeight, int ordinal) {
        AppChainClient.QueryResult result = client.query(
                EutxoQueryCodec.VALIDITY_TRANSITION_PATH,
                EutxoQueryCodec.validityTransitionRequest(
                        appHeight, ordinal));
        return snapshot(result, Optional.ofNullable(
                EutxoQueryCodec.decodeOptionalValidityTransition(
                        result.payload())));
    }

    /**
     * Fetch and locally verify the compact Plutus withdrawal commitment proof
     * used by the permissionless Cardano relay path.
     */
    public Optional<MpfNormalizedProof> withdrawalProof(String claimId) {
        return client.proof(EutxoStateKeys.withdrawalCommitment(claimId))
                .map(MpfProofConverter::convert);
    }

    public String profileDigest() {
        return profileSnapshot().value();
    }

    public EutxoSnapshot<String> profileSnapshot() {
        AppChainClient.QueryResult result =
                client.query(EutxoQueryCodec.PROFILE_PATH, new byte[0]);
        return snapshot(result, new String(result.payload(),
                java.nio.charset.StandardCharsets.UTF_8));
    }

    public EutxoSnapshot<EutxoL2ParameterSnapshot> l2ParametersSnapshot() {
        AppChainClient.QueryResult result = client.query(
                EutxoQueryCodec.L2_PARAMETERS_PATH, new byte[0]);
        return snapshot(
                result,
                EutxoQueryCodec.decodeL2Parameters(result.payload()));
    }

    private static <T> EutxoSnapshot<T> snapshot(
            AppChainClient.QueryResult result,
            T value
    ) {
        if (!EutxoContract.STATE_MACHINE_ID.equals(result.stateMachineId())) {
            throw new IllegalStateException(
                    "query response is not from the EUTxO state machine");
        }
        return new EutxoSnapshot<>(
                result.chainId(),
                result.committedHeight(),
                result.stateRoot(),
                value);
    }
}
