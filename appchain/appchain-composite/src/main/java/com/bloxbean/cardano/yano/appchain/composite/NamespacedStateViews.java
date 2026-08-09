package com.bloxbean.cardano.yano.appchain.composite;

import com.bloxbean.cardano.yano.api.appchain.AppQueryContext;
import com.bloxbean.cardano.yano.api.appchain.AppStateReader;
import com.bloxbean.cardano.yano.api.appchain.AppStateWriter;
import com.bloxbean.cardano.yano.api.appchain.AppStateCapabilities;

import java.util.Optional;

final class NamespacedStateViews {
    private NamespacedStateViews() {
    }

    static AppStateReader reader(String componentId, AppStateReader delegate) {
        return new Reader(componentId, delegate);
    }

    static AppStateWriter writer(String componentId, AppStateWriter delegate) {
        return new Writer(componentId, delegate);
    }

    static AppQueryContext query(String componentId, AppQueryContext delegate) {
        return new Query(componentId, delegate);
    }

    private static class Reader implements AppStateReader {
        final String componentId;
        final AppStateReader delegate;

        private Reader(String componentId, AppStateReader delegate) {
            this.componentId = CompositeValidation.id(componentId, "componentId");
            this.delegate = java.util.Objects.requireNonNull(delegate, "delegate");
        }

        @Override
        public Optional<byte[]> get(byte[] key) {
            return delegate.get(CompositeStateKeys.componentKey(componentId, key))
                    .map(value -> value.clone());
        }

        @Override
        public byte[] stateRoot() {
            return delegate.stateRoot().clone();
        }

        @Override
        public long committedHeight() {
            return delegate.committedHeight();
        }
    }

    private static final class Writer extends Reader implements AppStateWriter {
        private final AppStateWriter writer;

        private Writer(String componentId, AppStateWriter writer) {
            super(componentId, writer);
            this.writer = writer;
        }

        @Override
        public void put(byte[] key, byte[] value) {
            writer.put(CompositeStateKeys.componentKey(componentId, key), value.clone());
        }

        @Override
        public void delete(byte[] key) {
            writer.delete(CompositeStateKeys.componentKey(componentId, key));
        }

        @Override
        public AppStateCapabilities capabilities() {
            return writer.capabilities().scope(componentId);
        }
    }

    private static final class Query extends Reader implements AppQueryContext {
        private final AppQueryContext query;

        private Query(String componentId, AppQueryContext query) {
            super(componentId, query);
            this.query = query;
        }

        @Override
        public long committedHeight() {
            return query.committedHeight();
        }

        @Override
        public boolean authenticatedSnapshotOnline(String seriesId, long sequence) {
            return query.authenticatedSnapshotOnline(scopedSeries(seriesId), sequence);
        }

        @Override
        public Optional<byte[]> authenticatedSnapshotValue(
                String seriesId, long sequence, byte[] canonicalKey) {
            return query.authenticatedSnapshotValue(scopedSeries(seriesId), sequence, canonicalKey)
                    .map(byte[]::clone);
        }

        private String scopedSeries(String seriesId) {
            return componentId + "." + CompositeValidation.id(seriesId, "snapshot seriesId");
        }
    }
}
