package org.yanoproject.api.appchain.topics;

import com.bloxbean.cardano.client.crypto.KeyGenUtil;
import com.bloxbean.cardano.client.crypto.config.CryptoConfiguration;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import org.yanoproject.api.appchain.AppStateReader;
import org.yanoproject.api.appchain.topics.TopicWire.ActiveRevision;
import org.yanoproject.api.appchain.topics.TopicWire.Config;
import org.yanoproject.api.appchain.topics.TopicWire.EntryContent;
import org.yanoproject.api.appchain.topics.TopicWire.EntryRecord;
import org.yanoproject.api.appchain.topics.TopicWire.KeySet;
import org.yanoproject.api.appchain.topics.TopicWire.Policy;
import org.yanoproject.api.appchain.topics.TopicWire.Settings;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/** Keys and an in-memory parent state shared by the topic rule tests (ADR app-layer/039). */
final class TopicTestFixtures {
    private TopicTestFixtures() {
    }

    static KeySet keys(int threshold, Key... keys) {
        return new KeySet(threshold, Arrays.stream(keys).map(Key::hex).sorted().toList());
    }

    static Key key(int seed) {
        byte[] bytes = new byte[32];
        Arrays.fill(bytes, (byte) seed);
        return new Key(bytes, HexUtil.encodeHexString(KeyGenUtil.getPublicKeyFromPrivateKey(bytes)));
    }

    static Settings settings(KeySet registrars, boolean openClaims, boolean reject) {
        return new Settings(true, registrars, openClaims, reject, 3_600_000, 3_600_000, 4, 100, 10, 1, 600);
    }

    record Key(byte[] seed, String hex) {
        byte[] sign(byte[] message) {
            return CryptoConfiguration.INSTANCE.getSigningProvider().sign(message, seed);
        }
    }

    /** An in-memory parent state holding just the registry keys a test needs. */
    static final class State implements AppStateReader {
        private final Map<String, byte[]> values = new HashMap<>();

        State config(boolean reject) {
            return config(settings(null, false, reject));
        }

        State config(Settings settings) {
            put(TopicKeys.config(), TopicWireCodec.encodeConfig(new Config(1, 1, settings)));
            return this;
        }

        void entry(String name, Policy policy, boolean retired) {
            entry(name, keys(1, key(9)), policy, retired);
        }

        void entry(String name, KeySet owners, Policy policy, boolean retired) {
            put(TopicKeys.entry(name), TopicWireCodec.encodeEntryRecord(new EntryRecord(name,
                    new ActiveRevision(1, 1, 1, new EntryContent(owners, policy, retired)), 1, 0, 1)));
        }

        void schema(String hash, byte[] ir) {
            put(TopicKeys.schema(hash), ir);
        }

        void used(String statementHash) {
            put(TopicKeys.used(statementHash), new byte[] {1});
        }

        void revoked(String delegationHash) {
            put(TopicKeys.revoked(delegationHash), new byte[] {1});
        }

        TopicState view() {
            return new TopicState(this, 65_536);
        }

        private void put(byte[] key, byte[] value) {
            values.put(HexUtil.encodeHexString(key), value);
        }

        @Override
        public Optional<byte[]> get(byte[] key) {
            return Optional.ofNullable(values.get(HexUtil.encodeHexString(key)));
        }

        @Override
        public byte[] stateRoot() {
            return new byte[32];
        }
    }
}
