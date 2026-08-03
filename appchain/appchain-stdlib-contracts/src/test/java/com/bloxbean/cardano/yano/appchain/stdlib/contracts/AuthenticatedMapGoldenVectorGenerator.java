package com.bloxbean.cardano.yano.appchain.stdlib.contracts;

import com.bloxbean.cardano.vds.jmt.JellyfishMerkleTree;
import com.bloxbean.cardano.vds.jmt.JmtProfile;
import com.bloxbean.cardano.vds.jmt.store.InMemoryJmtStore;
import com.bloxbean.cardano.vds.mpf.MpfTrie;
import com.bloxbean.cardano.vds.mpf.internal.TestNodeStore;
import com.bloxbean.cardano.yano.api.appchain.state.StateCommitmentProfiles;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Reproducible vector generator; output is intentionally stable properties syntax. */
public final class AuthenticatedMapGoldenVectorGenerator {
    private static final java.util.HexFormat HEX = java.util.HexFormat.of();
    private static final byte[] OWNER = HEX.parseHex("11".repeat(32));

    private AuthenticatedMapGoldenVectorGenerator() {
    }

    public static void main(String[] args) {
        vectors().forEach((key, value) -> System.out.println(key + "=" + value));
    }

    static Map<String, String> vectors() {
        Map<String, String> vectors = new TreeMap<>();
        vectors.put("schema.version", "1");
        vectors.put("dependency.ccl.version", "0.8.0-pre5-dev1");
        vectors.put("profile.mpf.id", StateCommitmentProfiles.MPF.id());
        vectors.put("profile.mpf.descriptor", StateCommitmentProfiles.MPF.dependencyDescriptor());
        vectors.put("profile.mpf.fingerprint",
                hex(StateCommitmentProfiles.MPF.formatFingerprint()));
        vectors.put("profile.jmt.id", StateCommitmentProfiles.CLASSIC_JMT.id());
        vectors.put("profile.jmt.descriptor",
                StateCommitmentProfiles.CLASSIC_JMT.dependencyDescriptor());
        vectors.put("profile.jmt.fingerprint",
                hex(StateCommitmentProfiles.CLASSIC_JMT.formatFingerprint()));
        vectors.put("profile.jmt.dependencyDescriptorBytes",
                hex(JmtProfile.classicBlake2b256V1().format().encode()));

        byte[] key = AuthenticatedMapContract.canonicalKey("products", ascii("sku-1"));
        byte[] missingKey = AuthenticatedMapContract.canonicalKey("products", ascii("missing"));
        AuthenticatedMapContract.Command command = AuthenticatedMapContract.Command.single(
                AuthenticatedMapContract.Mutation.put("products", ascii("sku-1"),
                        new byte[]{1, 2}));
        AuthenticatedMapContract.Command batch = AuthenticatedMapContract.Command.batch(List.of(
                AuthenticatedMapContract.Mutation.put("products", ascii("sku-1"),
                        new byte[]{1, 2}),
                AuthenticatedMapContract.Mutation.put("issuer-keys", ascii("issuer-a"),
                        new byte[]{3, 4, 5})));
        AuthenticatedMapContract.Entry entry = AuthenticatedMapContract.Entry.active(
                1, OWNER, new byte[]{1, 2}, 0, 0);
        byte[] entryBytes = AuthenticatedMapContract.encodeEntry(entry);
        vectors.put("key.products.sku1", hex(key));
        vectors.put("key.products.missing", hex(missingKey));
        vectors.put("value.0102.hash", hex(AuthenticatedMapContract.logicalValueHash(
                new byte[]{1, 2})));
        vectors.put("command.put", hex(AuthenticatedMapContract.encodeCommand(command)));
        vectors.put("command.batch", hex(AuthenticatedMapContract.encodeCommand(batch)));
        vectors.put("command.batch.commitment", hex(AuthenticatedMapContract.batchCommitment(batch)));
        vectors.put("entry.active", hex(entryBytes));
        vectors.put("entry.revoked", hex(AuthenticatedMapContract.encodeEntry(entry.revoked(7))));

        AuthenticatedMapContract.Genesis genesis = genesis();
        vectors.put("genesis.cbor", hex(AuthenticatedMapContract.encodeGenesis(genesis)));
        vectors.put("genesis.id", hex(AuthenticatedMapContract.genesisId(genesis)));

        Map<byte[], byte[]> updates = workload();
        vectors.put("workload.count", Integer.toString(updates.size()));
        int updateIndex = 0;
        for (Map.Entry<byte[], byte[]> update : updates.entrySet()) {
            vectors.put("workload." + updateIndex + ".key", hex(update.getKey()));
            vectors.put("workload." + updateIndex + ".value", hex(update.getValue()));
            updateIndex++;
        }
        TestNodeStore mpfStore = new TestNodeStore();
        MpfTrie mpf = new MpfTrie(mpfStore);
        updates.forEach(mpf::put);
        byte[] mpfRoot = mpf.getRootHash();
        byte[] mpfInclusion = mpf.getProofWire(key).orElseThrow();
        byte[] mpfAbsence = mpf.getProofWire(missingKey).orElseThrow();
        if (!mpf.verifyProofWire(mpfRoot, key, entryBytes, true, mpfInclusion)
                || !mpf.verifyProofWire(mpfRoot, missingKey, null, false, mpfAbsence)) {
            throw new IllegalStateException("generated MPF proofs do not verify");
        }
        vectors.put("mpf.root", hex(mpfRoot));
        vectors.put("mpf.proof.inclusion", hex(mpfInclusion));
        vectors.put("mpf.proof.absence", hex(mpfAbsence));

        try (InMemoryJmtStore jmtStore = new InMemoryJmtStore()) {
            JellyfishMerkleTree jmt = new JellyfishMerkleTree(
                    jmtStore, JmtProfile.classicBlake2b256V1());
            byte[] jmtRoot = jmt.put(0, updates).rootHash();
            byte[] jmtInclusion = jmt.getProofWire(key, 0).orElseThrow();
            byte[] jmtAbsence = jmt.getProofWire(missingKey, 0).orElseThrow();
            if (!jmt.verifyProofWire(jmtRoot, key, entryBytes, true, jmtInclusion)
                    || !jmt.verifyProofWire(jmtRoot, missingKey, null, false, jmtAbsence)) {
                throw new IllegalStateException("generated JMT proofs do not verify");
            }
            vectors.put("jmt.version", "0");
            vectors.put("jmt.root", hex(jmtRoot));
            vectors.put("jmt.proof.inclusion", hex(jmtInclusion));
            vectors.put("jmt.proof.absence", hex(jmtAbsence));
        }
        return java.util.Collections.unmodifiableMap(vectors);
    }

    private static Map<byte[], byte[]> workload() {
        Map<byte[], byte[]> updates = new LinkedHashMap<>();
        updates.put(AuthenticatedMapContract.canonicalKey("products", ascii("sku-1")),
                AuthenticatedMapContract.encodeEntry(AuthenticatedMapContract.Entry.active(
                        1, OWNER, new byte[]{1, 2}, 0, 0)));
        updates.put(AuthenticatedMapContract.canonicalKey("products", ascii("sku-2")),
                AuthenticatedMapContract.encodeEntry(AuthenticatedMapContract.Entry.active(
                        1, HEX.parseHex("22".repeat(32)), ascii("hello"), 0, 0)));
        updates.put(AuthenticatedMapContract.canonicalKey("issuer-keys", ascii("issuer-a")),
                AuthenticatedMapContract.encodeEntry(AuthenticatedMapContract.Entry.active(
                        1, new byte[0], new byte[]{3, 4, 5}, 0, 0)));
        return updates;
    }

    private static AuthenticatedMapContract.Genesis genesis() {
        return new AuthenticatedMapContract.Genesis(
                "product-registry",
                StateCommitmentProfiles.CLASSIC_JMT.id(),
                StateCommitmentProfiles.CLASSIC_JMT.formatFingerprint(),
                repeated(0x22), repeated(0x33), repeated(0x44),
                64, 65_536,
                List.of(
                        new AuthenticatedMapContract.CollectionDescriptor(
                                "products", AuthenticatedMapContract.AUTH_OWNER,
                                true, 128, 16_384),
                        new AuthenticatedMapContract.CollectionDescriptor(
                                "issuer-keys", AuthenticatedMapContract.AUTH_MEMBER,
                                false, 64, 4_096)),
                List.of(new AuthenticatedMapContract.GenesisEntry(
                        "products", ascii("sku-1"), OWNER, new byte[]{1, 2})));
    }

    private static byte[] repeated(int value) {
        byte[] bytes = new byte[32];
        java.util.Arrays.fill(bytes, (byte) value);
        return bytes;
    }

    private static byte[] ascii(String value) {
        return value.getBytes(StandardCharsets.US_ASCII);
    }

    private static String hex(byte[] bytes) {
        return HEX.formatHex(bytes);
    }
}
