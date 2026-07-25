package com.bloxbean.cardano.yano.appchain.eutxo.indexer;

import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoDepositRecord;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoTransactionSummary;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoWithdrawalRecord;

import java.util.Optional;

public interface EutxoIndexReader {
    IndexCheckpoint checkpoint();

    Optional<EutxoTransactionSummary> transaction(String transactionId);

    Optional<EutxoTransactionSummary> message(String messageId);

    IndexPage<EutxoTransactionSummary> transactions(long before, int limit);

    Optional<EutxoDepositRecord> deposit(String acceptedOutpoint);

    IndexPage<EutxoDepositRecord> deposits(long before, int limit);

    Optional<EutxoWithdrawalRecord> withdrawal(String claimId);

    IndexPage<EutxoWithdrawalRecord> withdrawals(long before, int limit);

    IndexedAccount account(String address, int activityLimit);

    EutxoLineage lineage(String transactionId, int maximumDepth, int maximumNodes);

    /** Stable digest used by conformance and cross-node comparison. */
    String normalizedDigest();
}
