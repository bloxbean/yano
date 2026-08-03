package com.bloxbean.cardano.yano.appchain.eutxo.indexer;

import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoDepositRecord;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoTransactionSummary;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoWithdrawalRecord;

import java.util.Objects;

/** Storage-neutral facts projected from canonical committed EUTxO records. */
public sealed interface EutxoIndexEvent {
    long sequence();

    record Transaction(long sequence, EutxoTransactionSummary summary)
            implements EutxoIndexEvent {
        public Transaction {
            positive(sequence);
            Objects.requireNonNull(summary, "summary");
            if (summary.sequence() != sequence) {
                throw new IllegalArgumentException(
                        "transaction event sequence differs from summary");
            }
        }
    }

    record Deposit(long sequence, EutxoDepositRecord record)
            implements EutxoIndexEvent {
        public Deposit {
            positive(sequence);
            Objects.requireNonNull(record, "record");
        }
    }

    record Withdrawal(long sequence, EutxoWithdrawalRecord record)
            implements EutxoIndexEvent {
        public Withdrawal {
            positive(sequence);
            Objects.requireNonNull(record, "record");
            if (record.claim().settlementSequence() + 1 != sequence) {
                throw new IllegalArgumentException(
                        "withdrawal event sequence differs from claim");
            }
        }
    }

    private static void positive(long sequence) {
        if (sequence < 1) {
            throw new IllegalArgumentException("event sequence must be positive");
        }
    }
}
