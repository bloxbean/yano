package org.yanoproject.runtime.utxo;

import com.bloxbean.cardano.yaci.core.model.Amount;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import org.rocksdb.ColumnFamilyHandle;
import org.rocksdb.ReadOptions;
import org.rocksdb.RocksDB;
import org.rocksdb.RocksDBException;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Read-only unspent-output access bound to one RocksDB read snapshot (ADR-056 §3).
 *
 * <p>Created by {@link DefaultUtxoStore#snapshotReader(RocksDB, ReadOptions)}. Both the output
 * record and its reference script (stored separately, keyed by script hash) are read through the
 * same snapshot. It never writes.</p>
 *
 * <p>Byte-exactness of the stored record: the inline datum and the reference script are kept
 * exactly as they appeared on chain (the contents of their tag-24 wrappers, as yaci extracts them).
 * The whole output CBOR is <em>not</em> stored: the address is kept as its textual form and the
 * value as lovelace plus an asset list, so the original output encoding (map vs legacy array,
 * multi-asset key order, definite vs indefinite lengths) cannot be reproduced from the record.</p>
 */
public final class UtxoSnapshotReader {

    /**
     * One native asset of a stored output.
     *
     * @param policyIdHex policy id, lowercase hex
     * @param assetName   raw asset name bytes
     * @param quantity    quantity
     */
    public record StoredAsset(String policyIdHex, byte[] assetName, BigInteger quantity) {
        public StoredAsset {
            Objects.requireNonNull(policyIdHex, "policyIdHex");
            assetName = assetName != null ? assetName.clone() : new byte[0];
            Objects.requireNonNull(quantity, "quantity");
        }

        @Override
        public byte[] assetName() {
            return assetName.clone();
        }
    }

    /**
     * An unspent output as stored.
     *
     * @param address                the address in its textual form (bech32, or base58 for Byron)
     * @param lovelace               the lovelace amount
     * @param assets                 native assets, in stored order
     * @param datumHashHex           datum hash, lowercase hex, or {@code null}
     * @param inlineDatum            inline datum CBOR exactly as on chain, or {@code null}
     * @param referenceScriptHashHex reference script hash, lowercase hex, or {@code null}
     * @param referenceScript        reference script CBOR ({@code [type, script]}) exactly as on chain,
     *                               or {@code null} when the output has no reference script
     */
    public record StoredOutput(String address, BigInteger lovelace, List<StoredAsset> assets, String datumHashHex,
                               byte[] inlineDatum, String referenceScriptHashHex, byte[] referenceScript) {
        public StoredOutput {
            Objects.requireNonNull(address, "address");
            Objects.requireNonNull(lovelace, "lovelace");
            assets = List.copyOf(assets);
            inlineDatum = inlineDatum != null ? inlineDatum.clone() : null;
            referenceScript = referenceScript != null ? referenceScript.clone() : null;
        }

        @Override
        public byte[] inlineDatum() {
            return inlineDatum != null ? inlineDatum.clone() : null;
        }

        @Override
        public byte[] referenceScript() {
            return referenceScript != null ? referenceScript.clone() : null;
        }
    }

    private final RocksDB db;
    private final ColumnFamilyHandle cfUnspent;
    private final ColumnFamilyHandle cfScriptRef;
    private final ReadOptions reads;

    UtxoSnapshotReader(RocksDB db, ColumnFamilyHandle cfUnspent, ColumnFamilyHandle cfScriptRef,
                       ReadOptions reads) {
        this.db = Objects.requireNonNull(db, "db");
        this.cfUnspent = Objects.requireNonNull(cfUnspent, "cfUnspent");
        this.cfScriptRef = Objects.requireNonNull(cfScriptRef, "cfScriptRef");
        this.reads = Objects.requireNonNull(reads, "reads");
    }

    /**
     * @param txHash transaction id, hex
     * @param index  output index
     * @return the unspent output; empty when it does not exist or is spent
     * @throws RocksDBException      on a read failure
     * @throws IllegalStateException when the record is malformed or its reference script is missing
     */
    public Optional<StoredOutput> unspent(String txHash, int index) throws RocksDBException {
        if (index < 0 || index > 0xFFFF) {
            return Optional.empty();
        }
        byte[] val = db.get(cfUnspent, reads, UtxoKeyUtil.outpointKey(txHash, index));
        if (val == null) {
            return Optional.empty();
        }
        UtxoCborCodec.StoredUtxo stored = UtxoCborCodec.decodeUtxoRecord(val);
        List<StoredAsset> assets = new ArrayList<>();
        if (stored.assets != null) {
            for (Amount asset : stored.assets) {
                if (asset.getPolicyId() == null) {
                    continue;
                }
                assets.add(new StoredAsset(asset.getPolicyId(), asset.getAssetNameBytes(), asset.getQuantity()));
            }
        }
        byte[] referenceScript = null;
        String scriptHash = stored.referenceScriptHash;
        if (scriptHash != null && !scriptHash.isEmpty()) {
            referenceScript = db.get(cfScriptRef, reads, HexUtil.decodeHexString(scriptHash));
            if (referenceScript == null) {
                throw new IllegalStateException("Reference script " + scriptHash + " of output " + txHash + "#"
                        + index + " is missing from the script store");
            }
        }
        return Optional.of(new StoredOutput(stored.address, stored.lovelace, assets, stored.datumHash,
                stored.inlineDatum, scriptHash, referenceScript));
    }
}
