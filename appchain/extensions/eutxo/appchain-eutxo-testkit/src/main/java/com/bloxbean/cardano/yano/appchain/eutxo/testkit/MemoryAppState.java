package com.bloxbean.cardano.yano.appchain.eutxo.testkit;

import com.bloxbean.cardano.yano.api.appchain.AppQueryContext;
import com.bloxbean.cardano.yano.api.appchain.AppStateWriter;

import java.util.Arrays;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/** Deterministic read-your-writes state for state-machine unit and replay tests. */
public final class MemoryAppState implements AppStateWriter, AppQueryContext {
    private final Map<String, byte[]> values = new TreeMap<>();
    private long committedHeight;

    @Override
    public Optional<byte[]> get(byte[] key) {
        byte[] value = values.get(HexFormat.of().formatHex(key));
        return value == null ? Optional.empty() : Optional.of(value.clone());
    }

    @Override
    public byte[] stateRoot() {
        java.security.MessageDigest digest;
        try {
            digest = java.security.MessageDigest.getInstance("SHA-256");
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
        values.forEach((key, value) -> {
            digest.update(HexFormat.of().parseHex(key));
            digest.update(value);
        });
        return digest.digest();
    }

    @Override
    public void put(byte[] key, byte[] value) {
        values.put(HexFormat.of().formatHex(key), value.clone());
    }

    @Override
    public void delete(byte[] key) {
        values.remove(HexFormat.of().formatHex(key));
    }

    @Override
    public long committedHeight() {
        return committedHeight;
    }

    public void committedHeight(long value) {
        if (value < 0) {
            throw new IllegalArgumentException("committed height cannot be negative");
        }
        committedHeight = value;
    }

    public MemoryAppState copy() {
        MemoryAppState copy = new MemoryAppState();
        values.forEach((key, value) -> copy.values.put(key, value.clone()));
        copy.committedHeight = committedHeight;
        return copy;
    }

    public boolean sameState(MemoryAppState other) {
        if (!values.keySet().equals(other.values.keySet())) {
            return false;
        }
        return values.entrySet().stream()
                .allMatch(entry -> Arrays.equals(entry.getValue(), other.values.get(entry.getKey())));
    }
}
