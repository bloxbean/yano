package com.bloxbean.cardano.yano.appchain.client;

import com.bloxbean.cardano.yano.appchain.stdlib.contracts.EpochParamsContract;
import com.bloxbean.cardano.yano.appchain.stdlib.contracts.EpochStakeContract;

import java.util.Objects;
import java.util.Optional;

/** Typed complete-only reads for the out-of-box epoch-stake component. */
public final class EpochStakeClient {
    private final AppChainClient client;

    public EpochStakeClient(AppChainClient client) {
        this.client = Objects.requireNonNull(client, "client");
    }

    public Optional<StakeAtHeight> stake(long epoch, int credentialType,
                                         byte[] credentialHash) {
        AppChainClient.QueryResult result = client.query(EpochStakeContract.QUERY_PATH,
                EpochStakeContract.encodeQuery(new EpochStakeContract.Query(
                        epoch, credentialType, credentialHash)));
        if (result.payload().length == 0) return Optional.empty();
        EpochStakeContract.Value value = EpochStakeContract.decodeValue(result.payload());
        return Optional.of(new StakeAtHeight(epoch, credentialType, credentialHash,
                value.coin(), value.poolHash(), result.committedHeight(), result.stateRoot()));
    }

    public Optional<CompleteEpochAtHeight> completeness(long epoch) {
        AppChainClient.QueryResult result = client.query(EpochStakeContract.META_QUERY_PATH,
                EpochParamsContract.query(epoch));
        if (result.payload().length == 0) return Optional.empty();
        EpochStakeContract.Meta meta = EpochStakeContract.decodeMeta(result.payload());
        return Optional.of(new CompleteEpochAtHeight(meta, result.committedHeight(),
                result.stateRoot()));
    }

    public record StakeAtHeight(long epoch, int credentialType, byte[] credentialHash,
                                java.math.BigInteger coin, byte[] poolHash,
                                long committedHeight, byte[] stateRoot) {
        public StakeAtHeight {
            credentialHash = credentialHash.clone();
            poolHash = poolHash.clone();
            stateRoot = stateRoot.clone();
        }
        @Override public byte[] credentialHash() { return credentialHash.clone(); }
        @Override public byte[] poolHash() { return poolHash.clone(); }
        @Override public byte[] stateRoot() { return stateRoot.clone(); }
    }

    public record CompleteEpochAtHeight(EpochStakeContract.Meta meta,
                                        long committedHeight, byte[] stateRoot) {
        public CompleteEpochAtHeight { stateRoot = stateRoot.clone(); }
        @Override public byte[] stateRoot() { return stateRoot.clone(); }
    }
}
