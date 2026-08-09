package com.bloxbean.cardano.yano.appchain.client;

import com.bloxbean.cardano.yano.appchain.stdlib.contracts.EpochParamsContract;

import java.util.Objects;
import java.util.Optional;

/** Typed read client for the out-of-box epoch-params component. */
public final class EpochParamsClient {
    private final AppChainClient client;

    public EpochParamsClient(AppChainClient client) {
        this.client = Objects.requireNonNull(client, "client");
    }

    public Optional<EpochParamsAtHeight> parameters(long epoch) {
        AppChainClient.QueryResult result = client.query(
                EpochParamsContract.QUERY_PATH, EpochParamsContract.query(epoch));
        return result.payload().length == 0 ? Optional.empty()
                : Optional.of(new EpochParamsAtHeight(epoch, result.payload(),
                result.committedHeight(), result.stateRoot()));
    }

    public OptionalLongValue latestEpoch() {
        AppChainClient.QueryResult result = client.query(
                EpochParamsContract.LATEST_QUERY_PATH, new byte[0]);
        return result.payload().length == 0
                ? OptionalLongValue.empty()
                : OptionalLongValue.of(EpochParamsContract.decodeEpoch(result.payload()));
    }

    public record EpochParamsAtHeight(long epoch, byte[] canonicalCbor,
                                      long committedHeight, byte[] stateRoot) {
        public EpochParamsAtHeight {
            canonicalCbor = canonicalCbor.clone();
            stateRoot = stateRoot.clone();
        }
        @Override public byte[] canonicalCbor() { return canonicalCbor.clone(); }
        @Override public byte[] stateRoot() { return stateRoot.clone(); }
    }

    /** Small allocation-free alternative to boxing {@code Optional<Long>}. */
    public record OptionalLongValue(boolean present, long value) {
        public static OptionalLongValue empty() { return new OptionalLongValue(false, 0); }
        public static OptionalLongValue of(long value) { return new OptionalLongValue(true, value); }
        public long orElseThrow() {
            if (!present) throw new java.util.NoSuchElementException("No epoch value present");
            return value;
        }
    }
}
