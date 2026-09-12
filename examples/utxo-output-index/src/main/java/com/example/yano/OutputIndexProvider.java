package com.example.yano;

import org.yanoproject.api.chain.ChainPoint;
import org.yanoproject.api.utxo.index.IndexWriter;
import org.yanoproject.api.utxo.index.UtxoChanges;
import org.yanoproject.api.utxo.index.UtxoIndexContext;
import org.yanoproject.api.utxo.index.UtxoIndexContributor;
import org.yanoproject.api.utxo.index.UtxoIndexContributorProvider;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/** Example: immutable output facts keyed by canonical block + transaction/output index. */
public final class OutputIndexProvider implements UtxoIndexContributorProvider {
    @Override public String id() { return "example.output-index"; }

    @Override public UtxoIndexContributor create(UtxoIndexContext context) {
        return new UtxoIndexContributor() {
            @Override public void stageApply(UtxoChanges changes, IndexWriter writer) {
                int transaction = 0;
                for (var tx : changes.transactions()) {
                    for (var output : tx.created()) {
                        byte[] key = ByteBuffer.allocate(16).putLong(changes.point().blockNumber())
                                .putInt(transaction).putInt(output.outpoint().index()).array();
                        writer.put("outputs", key, output.address().getBytes(StandardCharsets.UTF_8));
                    }
                    transaction++;
                }
            }

            @Override public void stageRollback(ChainPoint target, IndexWriter writer) {
                // Append-only block facts need no copied prior values: their inverse is a range delete.
                writer.deleteRange("outputs", number(target.blockNumber() + 1), number(Long.MAX_VALUE));
            }
        };
    }

    private static byte[] number(long value) { return ByteBuffer.allocate(8).putLong(value).array(); }
}
