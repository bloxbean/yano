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
        Objects.requireNonNull(manifest, "manifest");
        EutxoZkProfile profile = EutxoZkProfile.Z3_VALIDITY_SETTLEMENT;
        if (!profile.digestHex().equals(manifest.profileDigest())
                || !profile.circuitId().equals(manifest.circuitId())) {
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
        Objects.requireNonNull(manifest, "manifest");
        if (!verificationKey.digestHex().equals(
                manifest.verificationKeyDigest())) {
            throw new IllegalArgumentException(
                    "ceremony verification-key identity mismatch");
        }
    }
}
