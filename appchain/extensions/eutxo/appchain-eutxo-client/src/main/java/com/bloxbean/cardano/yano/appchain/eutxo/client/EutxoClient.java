package com.bloxbean.cardano.yano.appchain.eutxo.client;

import com.bloxbean.cardano.client.transaction.spec.Transaction;
import com.bloxbean.cardano.yano.appchain.client.AppChainClient;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoContract;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoOutpoint;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoQueryCodec;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoReceipt;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoRecord;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoStateKeys;

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

    public String profileDigest() {
        return profileSnapshot().value();
    }

    public EutxoSnapshot<String> profileSnapshot() {
        AppChainClient.QueryResult result =
                client.query(EutxoQueryCodec.PROFILE_PATH, new byte[0]);
        return snapshot(result, new String(result.payload(),
                java.nio.charset.StandardCharsets.UTF_8));
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
