package com.bloxbean.cardano.yano.appchain.client;

import co.nstant.in.cbor.CborEncoder;
import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.ByteString;
import co.nstant.in.cbor.model.UnicodeString;
import co.nstant.in.cbor.model.UnsignedInteger;
import com.bloxbean.cardano.client.crypto.Blake2bUtil;
import com.bloxbean.cardano.client.crypto.config.CryptoConfiguration;
import com.bloxbean.cardano.vds.core.api.NodeStore;
import com.bloxbean.cardano.vds.jmt.JmtProfile;
import com.bloxbean.cardano.vds.mpf.MpfTrie;
import com.bloxbean.cardano.yano.api.appchain.AppBlock;
import com.bloxbean.cardano.yano.api.appchain.anchor.AnchorDatumV1;
import com.bloxbean.cardano.yano.api.appchain.snapshot.SnapshotCanonicalCodec;
import com.bloxbean.cardano.yano.api.appchain.snapshot.AuthenticatedSnapshotProofBundleCodec;
import com.bloxbean.cardano.yano.api.appchain.state.StateCommitmentIdentity;
import com.bloxbean.cardano.yano.api.appchain.state.StateCommitmentProfiles;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Release-matched client proof verification for ADR-025 commitment profiles.
 * A proof is authentic only when it is checked against an independently
 * obtained {@link TrustedStateRoot}, or when its block certificate is checked
 * against an independently pinned {@link FinalityTrustContext}.
 */
public final class ProofVerifier {

    public static final String MPF_BLAKE2B256_V1 = "mpf-blake2b256-v1";
    public static final String JMT_BLAKE2B256_V1 = "jmt-blake2b256-v1";
    public static final String JMT_POSEIDON_BLS12381_V1 = "jmt-poseidon-bls12381-v1";

    private static final int HASH_BYTES = 32;
    private static final int MAX_KEY_BYTES = 256;
    private static final int MAX_VALUE_BYTES = 1024 * 1024;
    private static final int MAX_PROOF_WIRE_BYTES = 1024 * 1024;
    private static final int MAX_MEMBERS = 64;
    private static final byte[] PROFILE_FINGERPRINT_DOMAIN =
            "yano-state-commitment-format-v1\0".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] SNAPSHOT_STORAGE_DOMAIN =
            "yano-authenticated-snapshot-storage-v1\0".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] SNAPSHOT_STATEMENT_DOMAIN =
            "yano-authenticated-snapshot-statement-v1\0".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] SNAPSHOT_BUNDLE_DOMAIN =
            "yano-authenticated-snapshot-proof-bundle-v1\0".getBytes(StandardCharsets.US_ASCII);
    private static final ProfileMetadata MPF_PROFILE = profile(
            MPF_BLAKE2B256_V1, "mpf", 0,
            "mpf-blake2b256-format-v1", "mpf-proof-wire-v1",
            false, true, true);
    private static final ProfileMetadata CLASSIC_JMT_PROFILE = profile(
            JMT_BLAKE2B256_V1, "jmt", 1,
            "classic-radix16-blake2b256-v1", "jmt-proof-cbor-v1",
            true, false, true);
    private static final ProfileMetadata POSEIDON_JMT_PROFILE = profile(
            JMT_POSEIDON_BLS12381_V1, "jmt", 1,
            "jmt-poseidon-bls12381-format-v1", "jmt-poseidon-bls12381-proof-v1",
            true, false, false);

    private ProofVerifier() {
    }

    /**
     * No-op store: MPF proof verification walks the serialized proof only and
     * never touches the store.
     */
    private static final class NoOpNodeStore implements NodeStore {
        @Override public byte[] get(byte[] hash) { return null; }
        @Override public void put(byte[] hash, byte[] nodeBytes) { }
        @Override public void delete(byte[] hash) { }
    }

    /** Checks proof/root internal consistency without making an authenticity claim. */
    public static boolean verifyInternalConsistency(AppChainClient.Proof proof) {
        return proof != null && verifyAgainstRoot(proof, proof.stateRootHex());
    }

    /**
     * Verify an authenticated-snapshot nested proof against an independently
     * established L1/finality root. This validates the complete descriptor-key
     * and secondary-root trust chain; it never trusts the server-reported anchor
     * by itself.
     */
    public static boolean verifyAuthenticatedSnapshot(
            AppChainClient.AuthenticatedSnapshotProof bundle,
            TrustedStateRoot trustedPrimaryRoot) {
        if (bundle == null || trustedPrimaryRoot == null) return false;
        try {
            var descriptor = SnapshotCanonicalCodec.decodeDescriptor(bundle.descriptorBytes());
            byte[] trustedGenesis = Hex.decode(trustedPrimaryRoot.genesisIdHex());
            byte[] expectedApplicationProfile = StateCommitmentIdentity.explicit(
                    StateCommitmentProfiles.require(trustedPrimaryRoot.profile()), trustedGenesis).digest();
            if (!descriptor.equals(bundle.descriptor())
                    || !Arrays.equals(descriptor.chainGenerationId(),
                    trustedGenesis)
                    || !Arrays.equals(descriptor.applicationProfileDigest(),
                    expectedApplicationProfile)
                    || !hasCanonicalSnapshotCommitments(bundle)) return false;
            AppChainClient.SnapshotAnchor anchor = bundle.anchor();
            if (!anchor.chainId().equals(trustedPrimaryRoot.chainId())
                    || anchor.anchoredHeight() != trustedPrimaryRoot.height()
                    || !anchor.stateRootHex().equals(trustedPrimaryRoot.stateRootHex())
                    || trustedPrimaryRoot.source() == TrustedRootSource.CARDANO_ANCHOR
                    && !anchor.blockHashHex().equals(trustedPrimaryRoot.blockHashHex())) return false;
            byte[] descriptorKey = ("snapshots/v1/" + descriptor.seriesId() + "/"
                    + String.format(Locale.ROOT, "%020d", descriptor.sequence()))
                    .getBytes(StandardCharsets.US_ASCII);
            AppChainClient.SnapshotNativeProof primary = bundle.primaryProof();
            if (!primary.profile().equals(trustedPrimaryRoot.profile())
                    || !hasCanonicalSnapshotProfile(primary)
                    || !primary.genesisIdHex().equals(trustedPrimaryRoot.genesisIdHex())
                    || primary.height() != trustedPrimaryRoot.height()
                    || !primary.stateRootHex().equals(trustedPrimaryRoot.stateRootHex())
                    || !primary.keyHex().equals(Hex.encode(descriptorKey))
                    || !"PRESENT".equals(primary.presence())
                    || !primary.valueHex().equals(Hex.encode(bundle.descriptorBytes()))
                    || !verifyNative(primary.profile(), AppChainClient.ProofPresence.PRESENT,
                    Hex.decode(primary.stateRootHex()), descriptorKey, bundle.descriptorBytes(),
                    Hex.decode(primary.proofWireHex()))) return false;
            AppChainClient.SnapshotNativeProof secondary = bundle.secondaryProof();
            AppChainClient.ProofPresence presence = AppChainClient.ProofPresence.valueOf(
                    secondary.presence());
            byte[] value = presence == AppChainClient.ProofPresence.ABSENT
                    ? null : Hex.decode(secondary.valueHex());
            byte[] series = descriptor.seriesId().getBytes(StandardCharsets.US_ASCII);
            byte[] secondaryGenesis = Blake2bUtil.blake2bHash256(ByteBuffer.allocate(
                            SNAPSHOT_STORAGE_DOMAIN.length + 64 + 8 + 2 + series.length)
                    .put(SNAPSHOT_STORAGE_DOMAIN).put(descriptor.chainGenerationId())
                    .put(descriptor.snapshotFormatFingerprint()).putLong(descriptor.sequence())
                    .putShort((short) series.length).put(series).array());
            return secondary.profile().equals(descriptor.snapshotProfile())
                    && hasCanonicalSnapshotProfile(secondary)
                    && secondary.formatFingerprintHex().equals(
                    Hex.encode(descriptor.snapshotFormatFingerprint()))
                    && secondary.proofEncodingId().equals(descriptor.snapshotProofWireVersion())
                    && secondary.genesisIdHex().equals(Hex.encode(secondaryGenesis))
                    && secondary.height() == descriptor.completedAppChainHeight()
                    && secondary.stateRootHex().equals(Hex.encode(descriptor.snapshotRoot()))
                    && verifyNative(secondary.profile(), presence, descriptor.snapshotRoot(),
                    Hex.decode(secondary.keyHex()), value, Hex.decode(secondary.proofWireHex()));
        } catch (RuntimeException | StackOverflowError malformed) {
            return false;
        }
    }

    /** Validate the response commitments before the server-reported anchor is trusted. */
    public static boolean hasCanonicalSnapshotCommitments(
            AppChainClient.AuthenticatedSnapshotProof bundle) {
        if (bundle == null) return false;
        try {
            var canonical = AuthenticatedSnapshotProofBundleCodec.decode(
                    bundle.canonicalBundleBytes());
            return Arrays.equals(canonical.descriptorBytes(), bundle.descriptorBytes())
                    && sameProof(canonical.descriptorProof().proof(), bundle.primaryProof())
                    && sameProof(canonical.snapshotProof(), bundle.secondaryProof())
                    && sameAnchor(canonical.anchor(), bundle.anchor())
                    && Hex.encode(canonical.statementCommitment())
                    .equals(bundle.statementCommitmentHex())
                    && Hex.encode(canonical.bundleCommitment())
                    .equals(bundle.bundleCommitmentHex());
        } catch (RuntimeException malformed) {
            return false;
        }
    }

    private static boolean sameProof(
            com.bloxbean.cardano.yano.api.appchain.state.StateProof canonical,
            AppChainClient.SnapshotNativeProof transport) {
        return canonical.snapshot().identity().profile().id().equals(transport.profile())
                && Hex.encode(canonical.snapshot().identity().profile().formatFingerprint())
                .equals(transport.formatFingerprintHex())
                && canonical.proofEncodingId().equals(transport.proofEncodingId())
                && Hex.encode(canonical.snapshot().identity().genesisId())
                .equals(transport.genesisIdHex())
                && canonical.snapshot().height() == transport.height()
                && Hex.encode(canonical.snapshot().stateRoot()).equals(transport.stateRootHex())
                && Hex.encode(canonical.canonicalKey()).equals(transport.keyHex())
                && canonical.presence().name().equals(transport.presence())
                && Objects.equals(canonical.value() == null ? null : Hex.encode(canonical.value()),
                transport.valueHex())
                && Hex.encode(canonical.nativeProof()).equals(transport.proofWireHex());
    }

    private static boolean sameAnchor(
            com.bloxbean.cardano.yano.api.appchain.AppAnchorCommitment canonical,
            AppChainClient.SnapshotAnchor transport) {
        return canonical.chainId().equals(transport.chainId())
                && canonical.mode().equals(transport.mode())
                && canonical.anchoredHeight() == transport.anchoredHeight()
                && Hex.encode(canonical.stateRoot()).equals(transport.stateRootHex())
                && Hex.encode(canonical.blockHash()).equals(transport.blockHashHex())
                && canonical.transactionHash().equals(transport.transactionHash())
                && canonical.l1Slot() == transport.l1Slot();
    }

    private static boolean hasCanonicalSnapshotProfile(AppChainClient.SnapshotNativeProof proof) {
        Optional<ProfileMetadata> selected = profileMetadata(proof.profile());
        if (selected.isEmpty()) return false;
        ProfileMetadata metadata = selected.orElseThrow();
        return metadata.backend().equals(proof.backend())
                && metadata.commitmentFormatId().equals(proof.commitmentFormatId())
                && metadata.proofEncodingId().equals(proof.proofEncodingId())
                && metadata.formatFingerprintHex().equals(proof.formatFingerprintHex());
    }

    /** Exact profile metadata compiled into this release's client verifier. */
    public static Optional<ProfileMetadata> profileMetadata(String profile) {
        if (profile == null) return Optional.empty();
        return Optional.ofNullable(switch (profile) {
            case MPF_BLAKE2B256_V1 -> MPF_PROFILE;
            case JMT_BLAKE2B256_V1 -> CLASSIC_JMT_PROFILE;
            case JMT_POSEIDON_BLS12381_V1 -> POSEIDON_JMT_PROFILE;
            default -> null;
        });
    }

    /**
     * Convert a caller-selected, L1-verified anchor output into the exact trusted
     * state identity used by proof verification. The caller remains responsible
     * for selecting the expected thread-token output and confirming L1 stability.
     */
    public static TrustedStateRoot trustedRootFromCardanoAnchor(
            AnchorDatumV1 anchor,
            String expectedChainId,
            byte[] expectedGenesisId,
            String expectedApplicationId
    ) {
        Objects.requireNonNull(anchor, "anchor");
        if (!Objects.equals(anchor.chainId(), expectedChainId)
                || !Objects.equals(anchor.applicationId(), expectedApplicationId)
                || expectedGenesisId == null
                || !Arrays.equals(anchor.chainGenesisId(), expectedGenesisId)) {
            throw new IllegalArgumentException("Cardano anchor commitment identity mismatch");
        }
        ProfileMetadata profile = profileMetadata(anchor.commitmentProfileId())
                .orElseThrow(() -> new IllegalArgumentException(
                        "Cardano anchor uses an unsupported commitment profile"));
        if (!profile.verifierAvailable()
                || !Arrays.equals(anchor.formatFingerprint(),
                Hex.decode(profile.formatFingerprintHex()))) {
            throw new IllegalArgumentException("Cardano anchor commitment format mismatch");
        }
        return new TrustedStateRoot(anchor.chainId(), anchor.commitmentProfileId(),
                Hex.encode(anchor.chainGenesisId()), anchor.height(),
                Hex.encode(anchor.stateRoot()), TrustedRootSource.CARDANO_ANCHOR,
                Hex.encode(anchor.blockHash()));
    }

    /**
     * Checks that a profile-tagged envelope uses this release's exact backend,
     * dependency, proof-codec, flags, fingerprint, and genesis form.
     */
    public static boolean hasCanonicalProfileMetadata(AppChainClient.Proof proof) {
        if (proof == null) return false;
        Integer schema = proof.proofSchemaVersion();
        Optional<ProfileMetadata> selected = profileMetadata(proof.profile());
        if (schema == null || schema != 1 || selected.isEmpty()) return false;
        ProfileMetadata metadata = selected.orElseThrow();
        String genesis = nullToEmpty(proof.genesisIdHex());
        return metadata.backend().equals(proof.backend())
                && metadata.commitmentFormatId().equals(proof.commitmentFormatId())
                && metadata.proofEncodingId().equals(proof.proofEncodingId())
                && metadata.formatFingerprintHex().equals(proof.formatFingerprintHex())
                && metadata.nativeVersioning() == Boolean.TRUE.equals(proof.nativeVersioning())
                && metadata.physicalDelete() == Boolean.TRUE.equals(proof.physicalDelete())
                && canonicalHex(genesis, HASH_BYTES)
                && !(metadata.physicalDelete()
                && proof.presence() == AppChainClient.ProofPresence.TOMBSTONED);
    }

    /** Verify all proof identity fields before profile-specific cryptography. */
    public static boolean verify(AppChainClient.Proof proof, TrustedStateRoot trustedRoot) {
        if (proof == null || trustedRoot == null || proof.committedHeight() == null
                || !trustedRoot.chainId().equals(proof.chainId())
                || !trustedRoot.profile().equals(proof.profile())
                || !trustedRoot.genesisIdHex().equals(nullToEmpty(proof.genesisIdHex()))
                || trustedRoot.height() != proof.committedHeight()
                || !trustedRoot.stateRootHex().equals(proof.stateRootHex())) {
            return false;
        }
        return verifyAgainstRoot(proof, trustedRoot.stateRootHex());
    }

    /**
     * Verify the signed block header, threshold certificate, commitment
     * identity, and native proof against a caller-pinned membership epoch.
     */
    public static boolean verifyCertified(
            AppChainClient.Proof proof,
            FinalityTrustContext trustContext
    ) {
        if (proof == null || trustContext == null || proof.block() == null
                || proof.finalityCertificate() == null || proof.committedHeight() == null
                || !trustContext.chainId().equals(proof.chainId())
                || !trustContext.profile().equals(proof.profile())
                || !trustContext.genesisIdHex().equals(nullToEmpty(proof.genesisIdHex()))) {
            return false;
        }
        try {
            AppChainClient.CertifiedBlockHeader block = proof.block();
            if (block.version() != AppBlock.BLOCK_VERSION
                    || block.height() != proof.committedHeight()
                    || block.height() <= 0 || block.l1Slot() < 0 || block.timestamp() < 0
                    || !proof.stateRootHex().equals(block.stateRootHex())) {
                return false;
            }
            byte[] calculatedBlockHash = certifiedBlockHash(proof.chainId(), block);
            if (!Arrays.equals(calculatedBlockHash, Hex.decode(block.blockHashHex()))) {
                return false;
            }
            AppChainClient.FinalityCertificate certificate = proof.finalityCertificate();
            if (certificate.scheme() != 0 || certificate.signatures().isEmpty()
                    || certificate.signatures().size() > MAX_MEMBERS) {
                return false;
            }
            Set<String> seen = new HashSet<>();
            int valid = 0;
            for (AppChainClient.FinalitySignature signature : certificate.signatures()) {
                if (signature == null || !canonicalHex(signature.signerHex(), HASH_BYTES)
                        || !canonicalHex(signature.signatureHex(), 64)
                        || !trustContext.memberKeysHex().contains(signature.signerHex())
                        || !seen.add(signature.signerHex())) {
                    return false;
                }
                byte[] signer = Hex.decode(signature.signerHex());
                byte[] signatureBytes = Hex.decode(signature.signatureHex());
                if (!CryptoConfiguration.INSTANCE.getSigningProvider()
                        .verify(signatureBytes, calculatedBlockHash, signer)) {
                    return false;
                }
                valid++;
            }
            if (valid < trustContext.threshold()) {
                return false;
            }
            return verify(proof, new TrustedStateRoot(
                    trustContext.chainId(), trustContext.profile(),
                    trustContext.genesisIdHex(), block.height(), block.stateRootHex(),
                    TrustedRootSource.FINALITY_CERTIFICATE));
        } catch (Exception | StackOverflowError malformed) {
            return false;
        }
    }

    /**
     * Checks proof mathematics against an explicit root without authenticating that root.
     * Use {@link #verify(AppChainClient.Proof, TrustedStateRoot)} at trust boundaries.
     */
    public static boolean verifyAgainstRoot(
            AppChainClient.Proof proof,
            String expectedStateRootHex
    ) {
        if (proof == null || expectedStateRootHex == null || proof.presence() == null
                || !hasCanonicalProfileMetadata(proof)) {
            return false;
        }
        try {
            byte[] root = Hex.decode(expectedStateRootHex);
            byte[] key = Hex.decode(proof.keyHex());
            byte[] wire = Hex.decode(proof.proofWireHex());
            boolean inclusion = proof.presence() != AppChainClient.ProofPresence.ABSENT;
            byte[] value = inclusion ? Hex.decode(proof.valueHex()) : null;
            return switch (proof.profile()) {
                case MPF_BLAKE2B256_V1 -> inclusion
                        ? verifyInclusion(root, key, value, wire)
                        : verifyExclusion(root, key, wire);
                case JMT_BLAKE2B256_V1 -> verifyClassicJmt(
                        root, key, value, inclusion, wire);
                // Phase 4 remains unavailable until the exact ZeroJ release is pinned.
                case JMT_POSEIDON_BLS12381_V1 -> false;
                default -> false;
            };
        } catch (RuntimeException | StackOverflowError malformed) {
            return false;
        }
    }

    /** Raw MPF inclusion verification. */
    public static boolean verifyInclusion(byte[] expectedRoot, byte[] key, byte[] value,
                                          byte[] proofWire) {
        if (!validInputs(expectedRoot, key, value, proofWire)
                || !MpfProofWirePreflight.accepts(proofWire)) {
            return false;
        }
        try {
            MpfTrie trie = new MpfTrie(new NoOpNodeStore());
            return trie.verifyProofWire(expectedRoot, key, value, true, proofWire);
        } catch (Exception | StackOverflowError e) {
            return false;
        }
    }

    /** Raw MPF exclusion verification. */
    public static boolean verifyExclusion(byte[] expectedRoot, byte[] key, byte[] proofWire) {
        if (!validInputs(expectedRoot, key, null, proofWire)
                || !MpfProofWirePreflight.accepts(proofWire)) {
            return false;
        }
        try {
            MpfTrie trie = new MpfTrie(new NoOpNodeStore());
            return trie.verifyProofWire(expectedRoot, key, null, false, proofWire);
        } catch (Exception | StackOverflowError e) {
            return false;
        }
    }

    /** Raw CCL classic JMT inclusion/non-inclusion verification. */
    public static boolean verifyClassicJmt(
            byte[] expectedRoot,
            byte[] key,
            byte[] value,
            boolean inclusion,
            byte[] proofWire
    ) {
        if (!validInputs(expectedRoot, key, inclusion ? value : null, proofWire)
                || inclusion != (value != null)) {
            return false;
        }
        try {
            JmtProfile profile = JmtProfile.classicBlake2b256V1();
            return profile.proofCodec().verify(expectedRoot, key, value, inclusion,
                    proofWire, profile.hashFunction(), profile.commitmentScheme());
        } catch (Exception | StackOverflowError malformed) {
            return false;
        }
    }

    /** Profile-dispatched raw verification for REST/CLI adapters. */
    public static boolean verifyNative(
            String profile,
            AppChainClient.ProofPresence presence,
            byte[] expectedRoot,
            byte[] key,
            byte[] value,
            byte[] proofWire
    ) {
        if (profile == null || presence == null
                || (presence == AppChainClient.ProofPresence.ABSENT) != (value == null)) {
            return false;
        }
        boolean inclusion = presence != AppChainClient.ProofPresence.ABSENT;
        return switch (profile) {
            case MPF_BLAKE2B256_V1 -> inclusion
                    ? verifyInclusion(expectedRoot, key, value, proofWire)
                    : verifyExclusion(expectedRoot, key, proofWire);
            case JMT_BLAKE2B256_V1 -> verifyClassicJmt(
                    expectedRoot, key, value, inclusion, proofWire);
            case JMT_POSEIDON_BLS12381_V1 -> false;
            default -> false;
        };
    }

    private static byte[] certifiedBlockHash(
            String chainId,
            AppChainClient.CertifiedBlockHeader block
    ) throws Exception {
        if (chainId == null || chainId.isBlank()
                || !canonicalHex(block.prevHashHex(), HASH_BYTES)
                || !canonicalHex(block.messagesRootHex(), HASH_BYTES)
                || !canonicalHex(block.stateRootHex(), HASH_BYTES)
                || !canonicalHex(block.blockHashHex(), HASH_BYTES)
                || !(block.l1BlockHashHex().isEmpty()
                || canonicalHex(block.l1BlockHashHex(), HASH_BYTES))) {
            throw new IllegalArgumentException("invalid certified block header");
        }
        Array header = new Array();
        header.add(new UnsignedInteger(block.version()));
        header.add(new UnicodeString(chainId));
        header.add(new UnsignedInteger(block.height()));
        header.add(new ByteString(Hex.decode(block.prevHashHex())));
        header.add(new UnsignedInteger(block.l1Slot()));
        header.add(new ByteString(Hex.decode(block.l1BlockHashHex())));
        header.add(new UnsignedInteger(block.timestamp()));
        header.add(new ByteString(Hex.decode(block.messagesRootHex())));
        header.add(new ByteString(Hex.decode(block.stateRootHex())));
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        new CborEncoder(bytes).encode(header);
        return Blake2bUtil.blake2bHash256(bytes.toByteArray());
    }

    private static ProfileMetadata profile(
            String id,
            String backend,
            int backendCode,
            String commitmentFormatId,
            String proofEncodingId,
            boolean nativeVersioning,
            boolean physicalDelete,
            boolean verifierAvailable
    ) {
        ByteArrayOutputStream descriptor = new ByteArrayOutputStream();
        descriptor.writeBytes(ByteBuffer.allocate(Integer.BYTES).putInt(1).array());
        putProfileText(descriptor, id);
        descriptor.write(backendCode);
        putProfileText(descriptor, commitmentFormatId);
        putProfileText(descriptor, proofEncodingId);
        descriptor.writeBytes(ByteBuffer.allocate(Integer.BYTES).putInt(HASH_BYTES).array());
        descriptor.write((nativeVersioning ? 1 : 0) | (physicalDelete ? 2 : 0));
        byte[] canonical = descriptor.toByteArray();
        byte[] fingerprintInput = new byte[PROFILE_FINGERPRINT_DOMAIN.length + canonical.length];
        System.arraycopy(PROFILE_FINGERPRINT_DOMAIN, 0, fingerprintInput, 0,
                PROFILE_FINGERPRINT_DOMAIN.length);
        System.arraycopy(canonical, 0, fingerprintInput, PROFILE_FINGERPRINT_DOMAIN.length,
                canonical.length);
        return new ProfileMetadata(id, backend, commitmentFormatId, proofEncodingId,
                nativeVersioning, physicalDelete,
                Hex.encode(Blake2bUtil.blake2bHash256(fingerprintInput)), verifierAvailable);
    }

    private static void putProfileText(ByteArrayOutputStream out, String value) {
        byte[] encoded = value.getBytes(StandardCharsets.US_ASCII);
        out.writeBytes(ByteBuffer.allocate(Short.BYTES).putShort((short) encoded.length).array());
        out.writeBytes(encoded);
    }

    private static boolean validInputs(byte[] expectedRoot, byte[] key, byte[] value,
                                       byte[] proofWire) {
        return expectedRoot != null && expectedRoot.length == HASH_BYTES
                && key != null && key.length > 0 && key.length <= MAX_KEY_BYTES
                && (value == null || value.length <= MAX_VALUE_BYTES)
                && proofWire != null && proofWire.length > 0
                && proofWire.length <= MAX_PROOF_WIRE_BYTES;
    }

    private static boolean canonicalHex(String value, int bytes) {
        if (value == null || value.length() != bytes * 2) return false;
        for (int index = 0; index < value.length(); index++) {
            char c = value.charAt(index);
            if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f'))) return false;
        }
        return true;
    }

    private static String nullToEmpty(String value) {
        return value != null ? value : "";
    }

    /** Release-matched normalized commitment metadata and verifier availability. */
    public record ProfileMetadata(
            String id,
            String backend,
            String commitmentFormatId,
            String proofEncodingId,
            boolean nativeVersioning,
            boolean physicalDelete,
            String formatFingerprintHex,
            boolean verifierAvailable
    ) {
    }

    /** Origin of a root authenticated outside the proof-serving node. */
    public enum TrustedRootSource {
        LOCALLY_VERIFIED_BLOCK,
        FINALITY_CERTIFICATE,
        CARDANO_ANCHOR,
        CALLER_PINNED
    }

    /** Exact independently authenticated state identity used for proof verification. */
    public record TrustedStateRoot(
            String chainId,
            String profile,
            String genesisIdHex,
            long height,
            String stateRootHex,
            TrustedRootSource source,
            String blockHashHex
    ) {
        public TrustedStateRoot(String chainId, String profile, String genesisIdHex,
                                long height, String stateRootHex, TrustedRootSource source) {
            this(chainId, profile, genesisIdHex, height, stateRootHex, source, "");
        }

        public TrustedStateRoot {
            chainId = requireText(chainId, "chainId");
            profile = requireIdentifier(profile, "profile");
            if (profileMetadata(profile).isEmpty()) {
                throw new IllegalArgumentException("unsupported state commitment profile");
            }
            genesisIdHex = Objects.requireNonNull(genesisIdHex, "genesisIdHex");
            if (!canonicalHex(genesisIdHex, HASH_BYTES)) {
                throw new IllegalArgumentException(
                        "genesisIdHex must be 32-byte canonical hex");
            }
            if (height <= 0) {
                throw new IllegalArgumentException("trusted state height must be positive");
            }
            if (!canonicalHex(stateRootHex, HASH_BYTES)) {
                throw new IllegalArgumentException("stateRootHex must be 32-byte canonical hex");
            }
            source = Objects.requireNonNull(source, "source");
            blockHashHex = blockHashHex == null ? "" : blockHashHex;
            if (!blockHashHex.isEmpty() && !canonicalHex(blockHashHex, HASH_BYTES)) {
                throw new IllegalArgumentException("blockHashHex must be empty or 32-byte canonical hex");
            }
            if (source == TrustedRootSource.CARDANO_ANCHOR && blockHashHex.isEmpty()) {
                throw new IllegalArgumentException("Cardano anchor trust requires its block hash");
            }
        }
    }

    /** Independently pinned membership epoch and commitment identity. */
    public record FinalityTrustContext(
            String chainId,
            String profile,
            String genesisIdHex,
            Set<String> memberKeysHex,
            int threshold
    ) {
        public FinalityTrustContext {
            chainId = requireText(chainId, "chainId");
            profile = requireIdentifier(profile, "profile");
            if (profileMetadata(profile).isEmpty()) {
                throw new IllegalArgumentException("unsupported state commitment profile");
            }
            genesisIdHex = Objects.requireNonNull(genesisIdHex, "genesisIdHex");
            if (!canonicalHex(genesisIdHex, HASH_BYTES)) {
                throw new IllegalArgumentException("invalid genesis identity");
            }
            if (memberKeysHex == null || memberKeysHex.isEmpty()
                    || memberKeysHex.size() > MAX_MEMBERS) {
                throw new IllegalArgumentException("trusted members must not be empty");
            }
            Set<String> canonical = new HashSet<>();
            for (String member : memberKeysHex) {
                if (!canonicalHex(member, HASH_BYTES) || !canonical.add(member)) {
                    throw new IllegalArgumentException("invalid trusted member identity");
                }
            }
            memberKeysHex = Set.copyOf(canonical);
            if (threshold < 1 || threshold > memberKeysHex.size()) {
                throw new IllegalArgumentException("invalid trusted finality threshold");
            }
        }
    }

    private static String requireText(String value, String name) {
        String normalized = Objects.requireNonNull(value, name);
        if (normalized.isBlank() || !normalized.equals(normalized.trim())) {
            throw new IllegalArgumentException(name + " must be nonblank without whitespace");
        }
        return normalized;
    }

    private static String requireIdentifier(String value, String name) {
        String normalized = requireText(value, name).toLowerCase(Locale.ROOT);
        if (!normalized.equals(value)
                || !normalized.matches("[a-z0-9][a-z0-9._-]{0,127}")) {
            throw new IllegalArgumentException(name + " must be a canonical identifier");
        }
        return normalized;
    }
}
