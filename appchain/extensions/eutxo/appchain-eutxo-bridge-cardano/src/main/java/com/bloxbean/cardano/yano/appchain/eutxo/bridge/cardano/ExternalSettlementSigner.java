package com.bloxbean.cardano.yano.appchain.eutxo.bridge.cardano;

import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoWithdrawalClaim;

import java.util.Objects;

/**
 * Privileged boundary implemented by an operator-controlled threshold/HSM
 * service. Yano never accepts signing secrets in consensus configuration.
 */
public interface ExternalSettlementSigner {
    SignedSettlement sign(SigningRequest request) throws Exception;

    record SigningRequest(
            EutxoWithdrawalClaim claim,
            byte[] unsignedBodyCbor
    ) {
        public SigningRequest {
            Objects.requireNonNull(claim, "claim");
            unsignedBodyCbor = Objects.requireNonNull(
                    unsignedBodyCbor, "unsignedBodyCbor").clone();
        }

        @Override
        public byte[] unsignedBodyCbor() {
            return unsignedBodyCbor.clone();
        }
    }

    record SignedSettlement(
            String transactionId,
            byte[] transactionCbor
    ) {
        public SignedSettlement {
            transactionId = Objects.requireNonNull(transactionId, "transactionId").trim();
            transactionCbor = Objects.requireNonNull(
                    transactionCbor, "transactionCbor").clone();
            String actual = com.bloxbean.cardano.client.transaction.util.TransactionUtil
                    .getTxHash(transactionCbor);
            if (!transactionId.equals(actual)) {
                throw new IllegalArgumentException(
                        "external signer returned a transaction-id mismatch");
            }
        }

        @Override
        public byte[] transactionCbor() {
            return transactionCbor.clone();
        }
    }
}
