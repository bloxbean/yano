package com.bloxbean.cardano.yano.appchain.eutxo.indexer.testing;

import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoOutpoint;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoProfile;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoTransactionSummary;
import com.bloxbean.cardano.yano.appchain.eutxo.indexer.EutxoIndexEvent;
import com.bloxbean.cardano.yano.appchain.eutxo.indexer.IndexIdentity;
import com.bloxbean.cardano.yano.appchain.eutxo.indexer.SourcePoint;

import java.math.BigInteger;
import java.util.List;

public final class EutxoIndexFixtures {
    public static final String ALICE =
            "addr_test1wzn5ee2qaqvly3hx7e0nk3vhm240n5muq3plhjcnvx9ppjgf62u6a";
    public static final String BOB =
            "addr_test1wzn5ee2qaqvly3hx7e0nk3vhm240n5muq3plhjcnvx9ppjgf62u6b";

    private EutxoIndexFixtures() {
    }

    public static IndexIdentity identity() {
        return new IndexIdentity(
                "devnet", "payments", "eutxo-ledger",
                EutxoProfile.V2.digestHex(), 1, "");
    }

    public static SourcePoint point(long height) {
        return new SourcePoint(
                height,
                hex(height),
                100 + height,
                hex(100 + height));
    }

    public static List<List<EutxoIndexEvent>> splitMergeEvents() {
        EutxoTransactionSummary split = summary(
                1, 1, List.of(),
                List.of(entry(1, 0, ALICE, 60), entry(1, 1, ALICE, 40)));
        EutxoTransactionSummary spend = summary(
                2, 2,
                List.of(entry(1, 0, ALICE, 60)),
                List.of(entry(2, 0, BOB, 60)));
        EutxoTransactionSummary merge = summary(
                3, 3,
                List.of(
                        entry(1, 1, ALICE, 40),
                        entry(2, 0, BOB, 60)),
                List.of(entry(3, 0, ALICE, 100)));
        return List.of(
                List.of(
                        new EutxoIndexEvent.Transaction(1, split),
                        new EutxoIndexEvent.Transaction(2, spend)),
                List.of(new EutxoIndexEvent.Transaction(3, merge)));
    }

    public static EutxoTransactionSummary summary(
            long sequence,
            long height,
            List<EutxoTransactionSummary.Entry> inputs,
            List<EutxoTransactionSummary.Entry> outputs
    ) {
        return new EutxoTransactionSummary(
                hex(sequence),
                hex(sequence + 1_000),
                sequence,
                height,
                (int) (sequence - 1),
                100 + height,
                EutxoTransactionSummary.Status.ACCEPTED,
                "cardano-vkey",
                inputs,
                outputs,
                "");
    }

    public static EutxoTransactionSummary.Entry entry(
            long transaction,
            int index,
            String address,
            long lovelace
    ) {
        return new EutxoTransactionSummary.Entry(
                new EutxoOutpoint(hex(transaction), index),
                address,
                BigInteger.valueOf(lovelace));
    }

    public static String hex(long value) {
        return String.format(java.util.Locale.ROOT, "%064x", value);
    }
}
