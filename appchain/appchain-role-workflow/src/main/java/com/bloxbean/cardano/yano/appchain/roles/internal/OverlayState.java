package com.bloxbean.cardano.yano.appchain.roles.internal;

import com.bloxbean.cardano.yano.api.appchain.AppStateWriter;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/** Makes earlier writes in the same routed block visible to later commands. */
public final class OverlayState implements AppStateWriter {
    private final AppStateWriter delegate;
    private final Map<Key, byte[]> values = new HashMap<>();
    private final Map<Key, Boolean> deleted = new HashMap<>();

    public OverlayState(AppStateWriter delegate) {
        this.delegate = java.util.Objects.requireNonNull(delegate, "delegate");
    }

    @Override
    public Optional<byte[]> get(byte[] key) {
        Key wrapped = new Key(key);
        if (deleted.containsKey(wrapped)) return Optional.empty();
        byte[] value = values.get(wrapped);
        return value != null ? Optional.of(value.clone()) : delegate.get(key).map(byte[]::clone);
    }

    @Override public byte[] stateRoot() { return delegate.stateRoot().clone(); }
    @Override public long committedHeight() { return delegate.committedHeight(); }

    @Override
    public void put(byte[] key, byte[] value) {
        Key wrapped = new Key(key);
        values.put(wrapped, value.clone());
        deleted.remove(wrapped);
        delegate.put(key, value.clone());
    }

    @Override
    public void delete(byte[] key) {
        Key wrapped = new Key(key);
        values.remove(wrapped);
        deleted.put(wrapped, Boolean.TRUE);
        delegate.delete(key);
    }

    private static final class Key {
        private final byte[] value;
        private Key(byte[] value) { this.value = value.clone(); }
        @Override public boolean equals(Object other) {
            return other instanceof Key key && Arrays.equals(value, key.value);
        }
        @Override public int hashCode() { return Arrays.hashCode(value); }
    }
}
