package com.bloxbean.cardano.yano.appchain.devtools;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AppChainMetadataTrustVerifierTest {
    private static final String KEY_ID = "vendor-release-2026";
    private static final String BUNDLE_ID = "com.example.appchain.counter";

    @TempDir
    Path temporary;

    @Test
    void verifiesEd25519IdentityAndBindsDescriptorToRuntimeManifest() throws Exception {
        KeyPair pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        Path artifact = signedArtifact("trusted.jar", pair, descriptor(BUNDLE_ID), manifest());
        String publicKey = rawPublicKey(pair);

        AppChainProjectModel.MetadataTrustResult result =
                new AppChainMetadataTrustVerifier().verify(
                        artifact, Map.of(KEY_ID, publicKey));

        assertThat(result.status()).isEqualTo("TRUSTED_METADATA");
        assertThat(result.bundleId()).isEqualTo(BUNDLE_ID);
        assertThat(result.descriptorId()).isEqualTo(BUNDLE_ID);
        assertThat(result.validationCoverage()).isEqualTo("PARTIAL");
        assertThat(result.descriptorSha256()).matches("[0-9a-f]{64}");

        StringWriter output = new StringWriter();
        StringWriter error = new StringWriter();
        int exit = new AppChainDevtoolsCli().run(new String[]{
                        "appchain", "metadata", "verify", artifact.toString(),
                        "--trust-key", KEY_ID + "=" + publicKey, "--format", "json"
                }, new PrintWriter(output), new PrintWriter(error));
        assertThat(exit).isZero();
        assertThat(error.toString()).isEmpty();
        assertThat(output.toString()).contains("TRUSTED_METADATA", BUNDLE_ID, "PARTIAL")
                .doesNotContain(temporary.toString());
    }

    @Test
    void rejectsTamperingUntrustedKeysAndUnsignedMetadata() throws Exception {
        KeyPair pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        KeyPair other = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        Path trusted = signedArtifact("trusted.jar", pair, descriptor(BUNDLE_ID), manifest());
        Path tampered = signedArtifact(
                "tampered.jar", pair, descriptor(BUNDLE_ID),
                manifest().replace("CounterMachine", "ChangedMachine"), manifest());
        Path wrongManifestId = signedArtifact(
                "wrong-manifest-id.jar", pair, descriptor(BUNDLE_ID),
                manifest().replace(BUNDLE_ID, "com.example.other"));
        Path extraManifest = signedArtifact(
                "extra-manifest.jar", pair, descriptor(BUNDLE_ID),
                manifest(), manifest(), true);

        assertThatThrownBy(() -> new AppChainMetadataTrustVerifier().verify(
                trusted, Map.of(KEY_ID, rawPublicKey(other))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("signature verification failed");
        assertThatThrownBy(() -> new AppChainMetadataTrustVerifier().verify(
                tampered, Map.of(KEY_ID, rawPublicKey(pair))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("runtime manifest digest");
        assertThatThrownBy(() -> new AppChainMetadataTrustVerifier().verify(
                wrongManifestId, Map.of(KEY_ID, rawPublicKey(pair))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("match bundleId");
        assertThatThrownBy(() -> new AppChainMetadataTrustVerifier().verify(
                extraManifest, Map.of(KEY_ID, rawPublicKey(pair))))
                .isInstanceOf(java.io.IOException.class)
                .hasMessageContaining("exactly one runtime manifest");

        Path unsigned = temporary.resolve("unsigned.jar");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(unsigned))) {
            entry(zip, "META-INF/yano/appchain-config-metadata-v1.json",
                    descriptor(BUNDLE_ID).getBytes(StandardCharsets.UTF_8));
        }
        assertThatThrownBy(() -> new AppChainMetadataTrustVerifier().verify(
                unsigned, Map.of(KEY_ID, rawPublicKey(pair))))
                .isInstanceOf(java.io.IOException.class)
                .hasMessageContaining("appchain-config-metadata-v1.sig.json");
    }

    private Path signedArtifact(
            String name, KeyPair pair, String descriptor, String manifest) throws Exception {
        return signedArtifact(name, pair, descriptor, manifest, manifest);
    }

    private Path signedArtifact(
            String name,
            KeyPair pair,
            String descriptor,
            String manifestInArchive,
            String manifestForSignature) throws Exception {
        return signedArtifact(name, pair, descriptor, manifestInArchive,
                manifestForSignature, false);
    }

    private Path signedArtifact(
            String name,
            KeyPair pair,
            String descriptor,
            String manifestInArchive,
            String manifestForSignature,
            boolean extraManifest) throws Exception {
        byte[] descriptorBytes = descriptor.getBytes(StandardCharsets.UTF_8);
        byte[] signedManifestBytes = manifestForSignature.getBytes(StandardCharsets.UTF_8);
        AppChainMetadataTrustVerifier.TrustEnvelope unsigned =
                new AppChainMetadataTrustVerifier.TrustEnvelope(
                        1, "Ed25519", KEY_ID, BUNDLE_ID,
                        AppChainProjectCatalog.sha256(descriptorBytes),
                        AppChainProjectCatalog.sha256(signedManifestBytes), "");
        Signature signer = Signature.getInstance("Ed25519");
        signer.initSign(pair.getPrivate());
        signer.update(AppChainMetadataTrustVerifier.canonicalPayload(unsigned, BUNDLE_ID));
        AppChainMetadataTrustVerifier.TrustEnvelope envelope =
                new AppChainMetadataTrustVerifier.TrustEnvelope(
                        unsigned.schemaVersion(), unsigned.algorithm(), unsigned.keyId(),
                        unsigned.bundleId(), unsigned.descriptorSha256(),
                        unsigned.runtimeManifestSha256(),
                        Base64.getEncoder().encodeToString(signer.sign()));
        Path artifact = temporary.resolve(name);
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(artifact))) {
            entry(zip, "META-INF/yano/appchain-config-metadata-v1.json", descriptorBytes);
            entry(zip, "META-INF/yano/plugins/" + BUNDLE_ID + ".json",
                    manifestInArchive.getBytes(StandardCharsets.UTF_8));
            if (extraManifest) {
                entry(zip, "META-INF/yano/plugins/com.example.extra.json",
                        manifest().replace(BUNDLE_ID, "com.example.extra")
                                .getBytes(StandardCharsets.UTF_8));
            }
            entry(zip, AppChainMetadataTrustVerifier.SIGNATURE_PATH,
                    new ObjectMapper().writeValueAsBytes(envelope));
        }
        return artifact;
    }

    private static String descriptor(String id) {
        return """
                {"schemaVersion":1,"id":"%s","properties":[],"dynamicNamespaces":[]}
                """.formatted(id).strip();
    }

    private static String manifest() {
        return """
                {"schemaVersion":1,"id":"%s","version":"1.0.0","requires":[],
                 "contributions":[{"kind":"state-machine","name":"counter",
                 "provider":"com.example.CounterMachine"}]}
                """.formatted(BUNDLE_ID).strip();
    }

    private static String rawPublicKey(KeyPair pair) {
        byte[] encoded = pair.getPublic().getEncoded();
        return HexFormat.of().formatHex(Arrays.copyOfRange(encoded, encoded.length - 32,
                encoded.length));
    }

    private static void entry(ZipOutputStream zip, String name, byte[] bytes) throws Exception {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(bytes);
        zip.closeEntry();
    }
}
