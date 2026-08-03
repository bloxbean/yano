package com.bloxbean.cardano.yano.appchain.eutxo.zk.client;

import com.bloxbean.cardano.client.common.cbor.CborSerializationUtil;
import com.bloxbean.cardano.client.transaction.spec.TransactionBody;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoL2Authorization;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoL2Domain;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoL2Transaction;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** Wraps a Cardano-compatible body and signs the Yano L2 envelope. */
public final class EutxoL2TransactionBuilder {
    private EutxoL2TransactionBuilder() {
    }

    public static EutxoL2Transaction sign(
            EutxoL2Domain domain,
            TransactionBody body,
            List<Signer> signers
    ) {
        Objects.requireNonNull(body, "body");
        try {
            return sign(
                    domain,
                    CborSerializationUtil.serialize(body.serialize()),
                    signers);
        } catch (Exception failure) {
            throw new IllegalArgumentException(
                    "Cardano transaction body cannot be encoded", failure);
        }
    }

    public static EutxoL2Transaction sign(
            EutxoL2Domain domain,
            byte[] canonicalBody,
            List<Signer> signers
    ) {
        List<Signer> ordered = signers.stream()
                .sorted(Comparator.comparing(Signer::paymentCredential))
                .toList();
        if (ordered.isEmpty()) {
            throw new IllegalArgumentException("at least one L2 signer is required");
        }
        List<EutxoL2Authorization> unsigned = ordered.stream()
                .map(signer -> new EutxoL2Authorization(
                        signer.paymentCredential(),
                        signer.keyEpoch(),
                        signer.sessionKey().publicKey(),
                        new byte[32],
                        new byte[32],
                        signer.inputIndexes()))
                .toList();
        EutxoL2Transaction template = new EutxoL2Transaction(
                domain, canonicalBody, unsigned);
        List<EutxoL2Authorization> signatures =
                new ArrayList<>(ordered.size());
        for (Signer signer : ordered) {
            signatures.add(signer.sessionKey().sign(
                    template,
                    signer.paymentCredential(),
                    signer.keyEpoch(),
                    signer.inputIndexes()));
        }
        return new EutxoL2Transaction(domain, canonicalBody, signatures);
    }

    public record Signer(
            String paymentCredential,
            long keyEpoch,
            List<Integer> inputIndexes,
            EutxoL2SessionKey sessionKey
    ) {
        public Signer {
            Objects.requireNonNull(paymentCredential, "paymentCredential");
            inputIndexes = List.copyOf(Objects.requireNonNull(
                    inputIndexes, "inputIndexes"));
            Objects.requireNonNull(sessionKey, "sessionKey");
        }
    }
}
