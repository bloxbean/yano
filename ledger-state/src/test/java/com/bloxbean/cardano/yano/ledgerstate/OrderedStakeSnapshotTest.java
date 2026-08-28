package com.bloxbean.cardano.yano.ledgerstate;

import com.bloxbean.cardano.yaci.core.util.HexUtil;
import com.bloxbean.cardano.yano.api.CanonicalBlockReference;
import com.bloxbean.cardano.yano.api.ChainBlockReader;
import com.bloxbean.cardano.yano.api.era.EraProvider;
import com.bloxbean.cardano.yano.api.utxo.StakeBalanceView;
import com.bloxbean.cardano.yano.api.utxo.StakeCredentialBalance;
import com.bloxbean.cardano.yano.api.utxo.StakeCredentialId;
import com.bloxbean.cardano.yano.api.utxo.UtxoState;
import com.bloxbean.cardano.yano.api.utxo.model.Outpoint;
import com.bloxbean.cardano.yano.api.utxo.model.Utxo;
import com.bloxbean.cardano.yano.ledgerstate.test.TestRocksDBHelper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;
import java.util.Optional;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class OrderedStakeSnapshotTest {
    private static final String CREDENTIAL_HASH = "31".repeat(28);
    private static final String POOL_HASH = "42".repeat(28);

    @TempDir
    Path tempDir;

    @Test
    void snapshotStreamsUtxoBalanceAndAddsLiveRewardWithoutMaterializingMap() throws Exception {
        try (var rocks = TestRocksDBHelper.create(tempDir)) {
            var store = new DefaultAccountStateStore(rocks.db(), rocks.cfSupplier(),
                    LoggerFactory.getLogger(getClass()), true);
            store.setEraProvider(new EraProvider() {
                @Override
                public boolean isConwayOrLater(int epoch) {
                    return true;
                }
            });
            store.setStakeSnapshotService(new EpochStakeSnapshotService(true));
            store.setBalanceMode("auto");

            byte[] blockHash = HexUtil.decodeHexString("ab".repeat(32));
            var coordinate = new CanonicalBlockReference(9, 900, blockHash);
            store.setChainBlockReader(new ChainBlockReader() {
                @Override
                public com.bloxbean.cardano.yaci.core.storage.ChainTip getLocalTip() {
                    return null;
                }

                @Override
                public byte[] getBlockByNumber(long blockNumber) {
                    return null;
                }

                @Override
                public com.bloxbean.cardano.yaci.core.model.Era getBlockEra(long blockNumber) {
                    return null;
                }

                @Override
                public Optional<CanonicalBlockReference> getCanonicalBlockReference(long blockNumber) {
                    return blockNumber == 9 ? Optional.of(coordinate) : Optional.empty();
                }
            });
            store.prepareEpochBoundary(9, 10, 1_000, 10);

            StakeBalanceView view = new SingleRowStakeBalanceView(coordinate,
                    new StakeCredentialBalance(
                            new StakeCredentialId(0, HexUtil.decodeHexString(CREDENTIAL_HASH)),
                            BigInteger.valueOf(1_000)));
            store.setUtxoState(new UtxoState() {
                @Override
                public List<Utxo> getUtxosByAddress(String address, int page, int pageSize) {
                    return List.of();
                }

                @Override
                public List<Utxo> getUtxosByPaymentCredential(String credential, int page, int pageSize) {
                    return List.of();
                }

                @Override
                public Optional<Utxo> getUtxo(Outpoint outpoint) {
                    return Optional.empty();
                }

                @Override
                public boolean isEnabled() {
                    return true;
                }

                @Override
                public boolean isStakeBalanceIndexReady() {
                    return true;
                }

                @Override
                public Optional<StakeBalanceView> openStakeBalanceView(
                        CanonicalBlockReference expectedCoordinate) {
                    return Optional.of(view);
                }
            });

            rocks.db().put(rocks.cfState(),
                    DefaultAccountStateStore.accountKey(0, CREDENTIAL_HASH),
                    AccountStateCborCodec.encodeStakeAccount(
                            BigInteger.valueOf(50), BigInteger.valueOf(2_000_000)));
            rocks.db().put(rocks.cfState(),
                    DefaultAccountStateStore.poolDelegKey(0, CREDENTIAL_HASH),
                    AccountStateCborCodec.encodePoolDelegation(POOL_HASH, 800, 0, 0));

            assertThat(store.createAndCommitDelegationSnapshot(9, null)).isNull();

            byte[] snapshotKey = new byte[33];
            ByteBuffer.wrap(snapshotKey).order(ByteOrder.BIG_ENDIAN).putInt(9).put((byte) 0)
                    .put(HexUtil.decodeHexString(CREDENTIAL_HASH));
            var snapshot = AccountStateCborCodec.decodeEpochDelegSnapshot(
                    rocks.db().get(rocks.cfSnapshot(), snapshotKey));
            assertThat(snapshot.poolHash()).isEqualTo(POOL_HASH);
            assertThat(snapshot.amount()).isEqualTo(BigInteger.valueOf(1_050));
            assertThat(view.advance()).isFalse();
        }
    }

    private static final class SingleRowStakeBalanceView implements StakeBalanceView {
        private final CanonicalBlockReference coordinate;
        private final StakeCredentialBalance row;
        private int position;
        private boolean closed;

        private SingleRowStakeBalanceView(CanonicalBlockReference coordinate,
                                          StakeCredentialBalance row) {
            this.coordinate = coordinate;
            this.row = row;
        }

        @Override
        public CanonicalBlockReference coordinate() {
            return coordinate;
        }

        @Override
        public boolean advance() {
            if (closed) return false;
            return position++ == 0;
        }

        @Override
        public StakeCredentialBalance current() {
            if (position != 1) throw new IllegalStateException("no current row");
            return row;
        }

        @Override
        public void close() {
            closed = true;
        }
    }
}
