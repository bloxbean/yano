package org.yanoproject.runtime.utxo;

import com.bloxbean.cardano.yaci.core.model.Amount;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import org.rocksdb.ColumnFamilyHandle;
import org.rocksdb.RocksDB;
import org.rocksdb.RocksDBException;
import org.yanoproject.runtime.db.UtxoCfNames;
import org.yanoproject.runtime.chain.DirectRocksDBChainState;

import java.math.BigInteger;
import java.util.List;

/**
 * Test-only raw writer for unspent-output records in the store's own encoding, for tests that need
 * to control exactly when a UTxO write lands (ADR-056 snapshot gates).
 */
public final class UtxoTestRecords {

    private UtxoTestRecords() {
    }

    public static void putUnspent(DirectRocksDBChainState chain, String txHash, int index, String address,
                                  BigInteger lovelace, List<Amount> assets, String datumHash, byte[] inlineDatum,
                                  String referenceScriptHash, byte[] referenceScript) throws RocksDBException {
        RocksDB db = (RocksDB) chain.getDb();
        byte[] scriptHash = referenceScriptHash != null ? HexUtil.decodeHexString(referenceScriptHash) : null;
        if (scriptHash != null && referenceScript != null) {
            db.put(handle(chain, UtxoCfNames.SCRIPT_REF), scriptHash, referenceScript);
        }
        byte[] record = UtxoCborCodec.encodeUtxoRecord(address, lovelace, assets, datumHash, inlineDatum, scriptHash,
                false, 1, 1, "00".repeat(32));
        db.put(handle(chain, UtxoCfNames.UTXO_UNSPENT), UtxoKeyUtil.outpointKey(txHash, index), record);
    }

    public static void putLovelace(DirectRocksDBChainState chain, String txHash, int index, BigInteger lovelace)
            throws RocksDBException {
        putUnspent(chain, txHash, index, "addr_test1vqxyz", lovelace, List.of(), null, null, null, null);
    }

    public static void delete(DirectRocksDBChainState chain, String txHash, int index) throws RocksDBException {
        ((RocksDB) chain.getDb()).delete(handle(chain, UtxoCfNames.UTXO_UNSPENT),
                UtxoKeyUtil.outpointKey(txHash, index));
    }

    private static ColumnFamilyHandle handle(DirectRocksDBChainState chain, String name) {
        return (ColumnFamilyHandle) chain.getColumnFamilyHandle(name);
    }
}
