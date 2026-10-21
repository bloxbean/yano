package org.yanoproject.runtime.ledger.canonical;

import org.rocksdb.ColumnFamilyHandle;
import org.rocksdb.RocksDB;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yanoproject.api.EpochParamProvider;
import org.yanoproject.api.model.ProtocolParamsSnapshot;
import org.yanoproject.ledgerstate.AccountStateCfNames;
import org.yanoproject.ledgerstate.DefaultAccountStateStore;
import org.yanoproject.ledgerstate.governance.GovernanceBlockProcessor;
import org.yanoproject.ledgerstate.governance.GovernanceStateStore;
import org.yanoproject.runtime.chain.DirectRocksDBChainState;
import org.yanoproject.runtime.utxo.DefaultUtxoStore;

import java.math.BigInteger;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.function.IntFunction;

/**
 * Real RocksDB-backed stores sharing one database, as in production, with the canonical gate of the
 * chain state wired to a {@link RocksCanonicalSnapshotSource}.
 */
final class CanonicalTestStores implements AutoCloseable {

    static final long EPOCH_LENGTH = 432_000L;
    private static final Logger LOG = LoggerFactory.getLogger(CanonicalTestStores.class);

    final DirectRocksDBChainState chain;
    final DefaultAccountStateStore accounts;
    final DefaultUtxoStore utxos;
    final CanonicalStateGate gate;

    CanonicalTestStores(Path directory, boolean governance, IntFunction<Optional<ProtocolParamsSnapshot>> params) {
        chain = new DirectRocksDBChainState(directory.toString());
        accounts = new DefaultAccountStateStore(db(), name -> (ColumnFamilyHandle) chain.getColumnFamilyHandle(name),
                LOG, true, epochParams());
        if (governance) {
            accounts.setGovernanceBlockProcessor(
                    new GovernanceBlockProcessor(new GovernanceStateStore(db(), cfState()), epochParams()));
        }
        utxos = new DefaultUtxoStore(chain, LOG, Map.of("yano.utxo.enabled", true));
        gate = chain.canonicalStateGate();
        gate.configureLedgerEpochReader(() -> RocksCanonicalSnapshotSource.completedBoundaryEpoch(accounts));
        gate.configureEpochCalculator(slot -> (int) (slot / EPOCH_LENGTH));
        gate.installSnapshotSource(new RocksCanonicalSnapshotSource(
                this::db, () -> accounts, () -> utxos, params, () -> false));
    }

    RocksDB db() {
        return (RocksDB) chain.getDb();
    }

    ColumnFamilyHandle cfState() {
        return (ColumnFamilyHandle) chain.getColumnFamilyHandle(AccountStateCfNames.ACCT_STATE);
    }

    static EpochParamProvider epochParams() {
        return new EpochParamProvider() {
            @Override
            public BigInteger getKeyDeposit(long epoch) {
                return BigInteger.valueOf(2_000_000L);
            }

            @Override
            public BigInteger getPoolDeposit(long epoch) {
                return BigInteger.valueOf(500_000_000L);
            }

            @Override
            public int getProtocolMajor(long epoch) {
                return 10;
            }
        };
    }

    @Override
    public void close() {
        utxos.close();
        chain.close();
    }
}
