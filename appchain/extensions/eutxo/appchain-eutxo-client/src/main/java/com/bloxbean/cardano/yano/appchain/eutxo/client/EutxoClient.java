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
        byte[] payload = client.query(
                EutxoQueryCodec.OUTPOINT_PATH,
                EutxoQueryCodec.outpointRequest(outpoint)).payload();
        return Optional.ofNullable(EutxoQueryCodec.decodeOptionalRecord(payload));
    }

    public List<EutxoRecord> utxos(String address) {
        byte[] payload = client.query(
                EutxoQueryCodec.ADDRESS_PATH,
                EutxoQueryCodec.addressRequest(address)).payload();
        return EutxoQueryCodec.decodeRecords(payload);
    }

    public Optional<EutxoReceipt> transaction(String transactionId) {
        byte[] payload = client.query(
                EutxoQueryCodec.TRANSACTION_PATH,
                EutxoQueryCodec.transactionRequest(transactionId)).payload();
        return Optional.ofNullable(EutxoQueryCodec.decodeOptionalReceipt(payload));
    }

    public Optional<EutxoReceipt> attempt(byte[] appMessageId) {
        byte[] payload = client.query(
                EutxoQueryCodec.ATTEMPT_PATH,
                EutxoQueryCodec.attemptRequest(appMessageId)).payload();
        return Optional.ofNullable(EutxoQueryCodec.decodeOptionalReceipt(payload));
    }

    public Optional<AppChainClient.Proof> proof(EutxoOutpoint outpoint) {
        return client.proof(EutxoStateKeys.utxo(outpoint));
    }

    public String profileDigest() {
        return new String(client.query(EutxoQueryCodec.PROFILE_PATH, new byte[0]).payload(),
                java.nio.charset.StandardCharsets.UTF_8);
    }
}
