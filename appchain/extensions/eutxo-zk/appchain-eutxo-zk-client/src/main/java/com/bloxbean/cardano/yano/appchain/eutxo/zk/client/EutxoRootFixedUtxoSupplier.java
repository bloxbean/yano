package com.bloxbean.cardano.yano.appchain.eutxo.zk.client;

import com.bloxbean.cardano.client.api.UtxoSupplier;
import com.bloxbean.cardano.client.api.common.OrderEnum;
import com.bloxbean.cardano.client.api.model.Amount;
import com.bloxbean.cardano.client.api.model.Utxo;
import com.bloxbean.cardano.client.common.cbor.CborSerializationUtil;
import com.bloxbean.cardano.client.transaction.spec.TransactionOutput;
import com.bloxbean.cardano.yano.appchain.eutxo.client.EutxoClient;
import com.bloxbean.cardano.yano.appchain.eutxo.client.EutxoSnapshot;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoOutpoint;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoRecord;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * CCL UTxO supplier pinned to one appchain state root.
 *
 * <p>If the node advances between calls, this supplier fails instead of
 * silently balancing a transaction against mixed roots.</p>
 */
public final class EutxoRootFixedUtxoSupplier implements UtxoSupplier {
    private final SnapshotSource source;
    private byte[] root;

    public EutxoRootFixedUtxoSupplier(EutxoClient client) {
        this(new ClientSnapshotSource(client));
    }

    public EutxoRootFixedUtxoSupplier(SnapshotSource source) {
        this.source = Objects.requireNonNull(source, "source");
    }

    @Override
    public synchronized List<Utxo> getPage(
            String address,
            Integer numberOfItems,
            Integer page,
            OrderEnum order
    ) {
        int size = numberOfItems == null ? DEFAULT_NR_OF_ITEMS_TO_FETCH
                : numberOfItems;
        int index = page == null ? 0 : page;
        if (size < 1 || size > 1_024 || index < 0) {
            throw new IllegalArgumentException("invalid EUTxO page request");
        }
        EutxoSnapshot<List<EutxoRecord>> snapshot = source.address(address);
        requireRoot(snapshot.stateRoot());
        Comparator<EutxoRecord> comparator =
                Comparator.comparing(record -> record.outpoint().toString());
        if (order == OrderEnum.desc) {
            comparator = comparator.reversed();
        }
        List<EutxoRecord> records = snapshot.value().stream()
                .sorted(comparator)
                .toList();
        int start = Math.min(Math.multiplyExact(index, size), records.size());
        int end = Math.min(start + size, records.size());
        List<Utxo> result = new ArrayList<>(end - start);
        for (EutxoRecord record : records.subList(start, end)) {
            result.add(convert(record));
        }
        return List.copyOf(result);
    }

    @Override
    public synchronized Optional<Utxo> getTxOutput(
            String transactionHash,
            int outputIndex
    ) {
        EutxoSnapshot<Optional<EutxoRecord>> snapshot =
                source.outpoint(new EutxoOutpoint(
                        transactionHash, outputIndex));
        requireRoot(snapshot.stateRoot());
        return snapshot.value().map(EutxoRootFixedUtxoSupplier::convert);
    }

    public synchronized byte[] stateRoot() {
        if (root == null) {
            throw new IllegalStateException(
                    "root-fixed supplier has not read a snapshot");
        }
        return root.clone();
    }

    private void requireRoot(byte[] candidate) {
        if (root == null) {
            root = candidate.clone();
        } else if (!Arrays.equals(root, candidate)) {
            throw new IllegalStateException(
                    "EUTxO state root changed during a root-fixed client session");
        }
    }

    private static Utxo convert(EutxoRecord record) {
        try {
            TransactionOutput output = TransactionOutput.deserialize(
                    CborSerializationUtil.deserialize(record.outputCbor()));
            if (output.getValue().getMultiAssets() != null
                    && !output.getValue().getMultiAssets().isEmpty()) {
                throw new IllegalArgumentException(
                        "L2 key-payment supplier does not expose native assets");
            }
            return Utxo.builder()
                    .txHash(record.outpoint().transactionId())
                    .outputIndex(record.outpoint().index())
                    .address(output.getAddress())
                    .amount(List.of(Amount.lovelace(
                            output.getValue().getCoin())))
                    .dataHash(output.getDatumHash() == null ? null
                            : HexFormat.of().formatHex(output.getDatumHash()))
                    .inlineDatum(output.getInlineDatum() == null ? null
                            : HexFormat.of().formatHex(
                            output.getInlineDatum().serializeToBytes()))
                    .referenceScriptHash(null)
                    .build();
        } catch (Exception failure) {
            throw new IllegalArgumentException(
                    "committed EUTxO output cannot be converted for CCL",
                    failure);
        }
    }

    public interface SnapshotSource {
        EutxoSnapshot<List<EutxoRecord>> address(String address);

        EutxoSnapshot<Optional<EutxoRecord>> outpoint(EutxoOutpoint outpoint);
    }

    private record ClientSnapshotSource(EutxoClient client)
            implements SnapshotSource {
        private ClientSnapshotSource {
            Objects.requireNonNull(client, "client");
        }

        @Override
        public EutxoSnapshot<List<EutxoRecord>> address(String address) {
            return client.utxosSnapshot(address);
        }

        @Override
        public EutxoSnapshot<Optional<EutxoRecord>> outpoint(
                EutxoOutpoint outpoint
        ) {
            return client.utxoSnapshot(outpoint);
        }
    }
}
