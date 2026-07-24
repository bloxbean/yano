package com.bloxbean.cardano.yano.appchain.eutxo.zk.contracts;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * Canonical, content-addressed identity for one EUTxO validity release.
 *
 * <p>The manifest contains identities only. Private proving material and
 * credentials must never be included.</p>
 */
public record EutxoZkReleaseManifest(
        String releaseId,
        String zerojVersion,
        String julcVersion,
        String profileDigest,
        Map<String, String> artifactDigests
) {
    private static final Pattern DIGEST =
            Pattern.compile("[0-9a-f]{64}");

    public EutxoZkReleaseManifest {
        requireText(releaseId, "releaseId");
        requireText(zerojVersion, "zerojVersion");
        requireText(julcVersion, "julcVersion");
        requireDigest(profileDigest, "profileDigest");
        Objects.requireNonNull(artifactDigests, "artifactDigests");
        if (artifactDigests.isEmpty()) {
            throw new IllegalArgumentException(
                    "at least one release artifact is required");
        }
        TreeMap<String, String> sorted = new TreeMap<>();
        artifactDigests.forEach((name, digest) -> {
            requireText(name, "artifact name");
            requireDigest(digest, "artifact digest");
            if (sorted.put(name, digest) != null) {
                throw new IllegalArgumentException(
                        "duplicate artifact name " + name);
            }
        });
        artifactDigests = Map.copyOf(sorted);
    }

    public byte[] canonicalBytes() {
        StringBuilder canonical = new StringBuilder()
                .append("yano-eutxo-zk-release-v1\n")
                .append(releaseId).append('\n')
                .append(zerojVersion).append('\n')
                .append(julcVersion).append('\n')
                .append(profileDigest).append('\n');
        new TreeMap<>(artifactDigests).forEach((name, digest) ->
                canonical.append(name).append('=').append(digest).append('\n'));
        return canonical.toString().getBytes(StandardCharsets.UTF_8);
    }

    public String digestHex() {
        return EutxoZkCodec.digestHex(canonicalBytes());
    }

    private static void requireText(String value, String label) {
        if (value == null || value.isBlank() || value.length() > 256
                || value.indexOf('\n') >= 0 || value.indexOf('=') >= 0) {
            throw new IllegalArgumentException("invalid " + label);
        }
    }

    private static void requireDigest(String value, String label) {
        if (value == null || !DIGEST.matcher(value).matches()) {
            throw new IllegalArgumentException("invalid " + label);
        }
    }
}
