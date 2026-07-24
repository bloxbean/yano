package com.bloxbean.cardano.yano.appchain.eutxo.zk.zeroj;

import com.bloxbean.cardano.yano.appchain.eutxo.zk.contracts.EutxoZkProfile;
import com.bloxbean.cardano.yano.appchain.eutxo.zk.contracts.EutxoZkVerificationKey;

import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;

/** Fail-closed byte and identity verification for imported ceremony bundles. */
public final class EutxoCeremonyBundleVerifier {
    private EutxoCeremonyBundleVerifier() {
    }

    public static void verifyBeforeLoad(
            Path keyDirectory,
            EutxoCeremonyManifest manifest
    ) {
        EutxoZkProfile profile = EutxoZkProfile.Z3_VALIDITY_SETTLEMENT;
        verifyBeforeLoad(
                keyDirectory, manifest,
                profile.digestHex(), profile.circuitId());
    }

    public static void verifyBeforeLoad(
            Path keyDirectory,
            EutxoCeremonyManifest manifest,
            String expectedProfileDigest,
            String expectedCircuitId
    ) {
        Objects.requireNonNull(manifest, "manifest");
        if (!Objects.equals(expectedProfileDigest, manifest.profileDigest())
                || !Objects.equals(expectedCircuitId, manifest.circuitId())) {
            throw new IllegalArgumentException(
                    "ceremony profile or circuit identity mismatch");
        }
        Map<String, String> actual =
                EutxoCeremonyManifest.inventory(keyDirectory);
        if (!actual.equals(manifest.fileDigests())) {
            throw new IllegalArgumentException(
                    "ceremony file inventory or digest mismatch");
        }
    }

    public static void verifyAfterLoad(
            EutxoZkVerificationKey verificationKey,
            EutxoCeremonyManifest manifest
    ) {
        Objects.requireNonNull(verificationKey, "verificationKey");
        verifyAfterLoad(verificationKey.digestHex(), manifest);
    }

    public static void verifyAfterLoad(
            String verificationKeyDigest,
            EutxoCeremonyManifest manifest
    ) {
        Objects.requireNonNull(verificationKeyDigest, "verificationKeyDigest");
        Objects.requireNonNull(manifest, "manifest");
        if (!verificationKeyDigest.equals(manifest.verificationKeyDigest())) {
            throw new IllegalArgumentException(
                    "ceremony verification-key identity mismatch");
        }
    }
}
