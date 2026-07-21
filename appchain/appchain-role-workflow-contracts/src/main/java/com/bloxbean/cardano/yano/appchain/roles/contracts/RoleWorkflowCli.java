package com.bloxbean.cardano.yano.appchain.roles.contracts;

import com.bloxbean.cardano.client.crypto.KeyGenUtil;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

/** Small dependency-light CLI for offline actor signing and governance command encoding. */
public final class RoleWorkflowCli {
    private static final HexFormat HEX = HexFormat.of();

    private RoleWorkflowCli() {
    }

    public static void main(String[] args) {
        try {
            System.out.println(run(args));
        } catch (RuntimeException failure) {
            System.err.println("error: invalid role-workflow command");
            System.exit(2);
        }
    }

    static String run(String[] args) {
        if (args == null || args.length == 0) throw invalid();
        String command = args[0];
        Map<String, String> options = options(args);
        return switch (command) {
            case "public-key" -> {
                requireOnly(options, "--seed-file");
                yield HEX.formatHex(KeyGenUtil.getPublicKeyFromPrivateKey(seed(options)));
            }
            case "sign" -> {
                requireOnly(options, "--action", "--chain", "--proposal", "--policy",
                        "--policy-revision", "--payload-domain", "--payload-hash",
                        "--deadline-height", "--actor", "--actor-revision", "--key",
                        "--clause", "--seed-file");
                yield HEX.formatHex(sign(options).encode());
            }
            case "key-proof" -> {
                requireOnly(options, "--chain", "--actor", "--actor-revision", "--key",
                        "--public-key", "--valid-from-height", "--valid-until-height",
                        "--seed-file");
                yield HEX.formatHex(keyProof(options).encode());
            }
            case "govern-propose" -> {
                requireOnly(options, "--mutation-id", "--mutation-hex", "--expiry-height");
                yield HEX.formatHex(new GovernedMutationCommandV1.Propose(
                        require(options, "--mutation-id"), bytes(options, "--mutation-hex"),
                        number(options, "--expiry-height")).encode());
            }
            case "govern-approve" -> {
                requireOnly(options, "--mutation-id", "--mutation-hash");
                yield HEX.formatHex(new GovernedMutationCommandV1.Approve(
                        require(options, "--mutation-id"),
                        bytes(options, "--mutation-hash")).encode());
            }
            case "govern-activate" -> {
                requireOnly(options, "--mutation-id", "--mutation-hash");
                yield HEX.formatHex(new GovernedMutationCommandV1.Activate(
                        require(options, "--mutation-id"),
                        bytes(options, "--mutation-hash")).encode());
            }
            default -> throw invalid();
        };
    }

    private static SignedActorCommandV1 sign(Map<String, String> options) {
        ActorStatementV1.Action action = ActorStatementV1.Action.valueOf(
                require(options, "--action").toUpperCase(java.util.Locale.ROOT));
        String clause = options.getOrDefault("--clause", "");
        ActorStatementV1 statement = new ActorStatementV1(action,
                require(options, "--chain"), require(options, "--proposal"),
                require(options, "--policy"), number(options, "--policy-revision"),
                require(options, "--payload-domain"), bytes(options, "--payload-hash"),
                number(options, "--deadline-height"), require(options, "--actor"),
                number(options, "--actor-revision"), require(options, "--key"), clause);
        return SignedActorCommandV1.sign(statement, seed(options));
    }

    private static ActorKeyProofV1 keyProof(Map<String, String> options) {
        byte[] seed = seed(options);
        byte[] publicKey = KeyGenUtil.getPublicKeyFromPrivateKey(seed);
        String expected = options.get("--public-key");
        if (expected != null && !java.security.MessageDigest.isEqual(
                publicKey, parseHex(expected, 32))) throw invalid();
        ActorKeyEpochV1 key = new ActorKeyEpochV1(require(options, "--key"), publicKey,
                number(options, "--valid-from-height"),
                optionalNumber(options, "--valid-until-height", 0), RecordStatus.ACTIVE);
        return ActorKeyProofV1.sign(require(options, "--chain"),
                require(options, "--actor"), number(options, "--actor-revision"), key, seed);
    }

    private static byte[] seed(Map<String, String> options) {
        String file = require(options, "--seed-file");
        try {
            String encoded = Files.readString(Path.of(file)).trim();
            return parseHex(encoded, 32);
        } catch (Exception failure) {
            throw invalid();
        }
    }

    private static Map<String, String> options(String[] args) {
        if ((args.length - 1) % 2 != 0) throw invalid();
        Map<String, String> result = new LinkedHashMap<>();
        for (int index = 1; index < args.length; index += 2) {
            if (!args[index].startsWith("--")
                    || result.putIfAbsent(args[index], args[index + 1]) != null) throw invalid();
        }
        return Map.copyOf(result);
    }

    private static String require(Map<String, String> options, String name) {
        String value = options.get(name);
        if (value == null || value.isBlank()) throw invalid();
        return value;
    }

    private static void requireOnly(Map<String, String> options, String... allowed) {
        if (!java.util.Set.of(allowed).containsAll(options.keySet())) throw invalid();
    }

    private static byte[] bytes(Map<String, String> options, String name) {
        try { return HEX.parseHex(require(options, name)); }
        catch (RuntimeException invalid) { throw invalid(); }
    }

    private static byte[] parseHex(String value, int length) {
        byte[] parsed;
        try { parsed = HEX.parseHex(value); }
        catch (RuntimeException invalid) { throw invalid(); }
        if (parsed.length != length) throw invalid();
        return parsed;
    }

    private static long number(Map<String, String> options, String name) {
        return optionalNumber(options, name, -1);
    }

    private static long optionalNumber(Map<String, String> options, String name, long fallback) {
        String value = options.get(name);
        if (value == null) {
            if (fallback < 0) throw invalid();
            return fallback;
        }
        if (!value.matches("0|[1-9][0-9]{0,18}")) throw invalid();
        try { return Long.parseLong(value); }
        catch (NumberFormatException invalid) { throw invalid(); }
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("invalid role-workflow CLI arguments");
    }
}
