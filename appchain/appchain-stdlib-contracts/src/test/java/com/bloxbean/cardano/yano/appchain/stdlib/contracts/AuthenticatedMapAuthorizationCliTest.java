package com.bloxbean.cardano.yano.appchain.stdlib.contracts;

import com.bloxbean.cardano.client.crypto.KeyGenUtil;
import com.bloxbean.cardano.client.crypto.config.CryptoConfiguration;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class AuthenticatedMapAuthorizationCliTest {
    @Test
    void assemblesActionExternalPreimageAndCanonicalCommand() {
        byte[] seed = java.util.HexFormat.of().parseHex("44".repeat(32));
        byte[] publicKey = KeyGenUtil.getPublicKeyFromPrivateKey(seed);
        byte[] command = AuthenticatedMapContract.encodeCommand(
                AuthenticatedMapContract.Command.single(
                        AuthenticatedMapContract.Mutation.put("records",
                                "key".getBytes(StandardCharsets.US_ASCII),
                                "value".getBytes(StandardCharsets.US_ASCII))));
        String action = AuthenticatedMapAuthorizationCli.execute(new String[]{
                "action", "--command-hex", hex(command), "--assignments",
                "0:governed-role:issuer-write:1"});
        String preimage = AuthenticatedMapAuthorizationCli.execute(directArguments(
                "direct-preimage", action, publicKey, null));
        byte[] signature = CryptoConfiguration.INSTANCE.getSigningProvider().sign(
                java.util.HexFormat.of().parseHex(preimage), seed);
        String evidence = AuthenticatedMapAuthorizationCli.execute(directArguments(
                "direct-complete", action, publicKey, signature));
        String encoded = AuthenticatedMapAuthorizationCli.execute(new String[]{
                "command", "--action-hex", action, "--evidence-hex", evidence});

        var decoded = AuthenticatedMapAuthorizationContract.decodeCommand(
                java.util.HexFormat.of().parseHex(encoded));
        assertThat(decoded.evidence()).singleElement()
                .isInstanceOf(AuthenticatedMapAuthorizationContract
                        .MapActorAuthorizationV1.class);
    }

    private static String[] directArguments(
            String command, String action, byte[] publicKey, byte[] signature) {
        java.util.List<String> values = new java.util.ArrayList<>(java.util.List.of(
                command,
                "--action-hex", action,
                "--authorization-id", "55".repeat(32),
                "--chain", "chain-a",
                "--genesis-id", "66".repeat(32),
                "--indexes", "0",
                "--policy", "issuer-write",
                "--policy-revision", "1",
                "--actor", "issuer-a",
                "--actor-revision", "1",
                "--key", "issuer-a-key",
                "--public-key", hex(publicKey),
                "--issued-height", "10",
                "--deadline-height", "20"));
        if (signature != null) {
            values.add("--signature");
            values.add(hex(signature));
        }
        return values.toArray(String[]::new);
    }

    private static String hex(byte[] value) {
        return java.util.HexFormat.of().formatHex(value);
    }
}
