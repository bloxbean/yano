package com.bloxbean.cardano.yano.appchain.eutxo.zk.client;

import com.bloxbean.cardano.client.crypto.Blake2bUtil;
import com.bloxbean.cardano.yano.appchain.client.AppChainClient;
import com.bloxbean.cardano.yano.appchain.eutxo.client.EutxoClient;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoL2Transaction;

import java.util.HexFormat;
import java.util.Objects;

/** High-level L2 submit facade preserving all three correlation identities. */
public final class EutxoL2Client {
    private final EutxoClient client;
    private final EutxoL2ProtocolParameters parameters;

    public EutxoL2Client(
            EutxoClient client,
            EutxoL2ProtocolParameters parameters
    ) {
        this.client = Objects.requireNonNull(client, "client");
        this.parameters = Objects.requireNonNull(parameters, "parameters");
    }

    public Submission submit(EutxoL2Transaction transaction) {
        Objects.requireNonNull(transaction, "transaction");
        requireIdentities(transaction);
        AppChainClient.SubmitResult result =
                client.submit(transaction.canonicalBytes());
        return new Submission(
                transaction.transactionId(),
                HexFormat.of().formatHex(Blake2bUtil.blake2bHash256(
                        transaction.transactionBody())),
                result.messageId(),
                result.chainId());
    }

    public EutxoRootFixedUtxoSupplier utxoSupplier() {
        return new EutxoRootFixedUtxoSupplier(client);
    }

    public EutxoL2ProtocolParameters protocolParameters() {
        return parameters;
    }

    private void requireIdentities(EutxoL2Transaction transaction) {
        var domain = transaction.domain();
        if (!parameters.chainId().equals(domain.chainId())
                || !parameters.ledgerProfileDigest().equals(
                domain.ledgerProfileDigest())
                || !parameters.validityProfileDigest().equals(
                domain.validityProfileDigest())
                || !parameters.authorizationProfile().equals(
                domain.authorizationProfile())
                || !parameters.authorizationProfileDigest().equals(
                domain.authorizationProfileDigest())) {
            throw new IllegalArgumentException(
                    "L2 transaction identities differ from client parameters");
        }
    }

    public record Submission(
            String l2TransactionId,
            String cardanoBodyHash,
            String appMessageId,
            String chainId
    ) {
        public Submission {
            requireDigest(l2TransactionId, "l2TransactionId");
            requireDigest(cardanoBodyHash, "cardanoBodyHash");
            requireDigest(appMessageId, "appMessageId");
            chainId = Objects.requireNonNull(chainId, "chainId");
        }

        private static void requireDigest(String value, String label) {
            if (value == null || !value.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException(
                        label + " must be lowercase 32-byte hex");
            }
        }
    }
}
