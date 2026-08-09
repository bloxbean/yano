package com.bloxbean.cardano.yano.appchain.history.cli;

import com.bloxbean.cardano.yano.appchain.client.ProofVerifier;
import com.bloxbean.cardano.yano.appchain.history.client.CardanoHistoryClient;
import com.bloxbean.cardano.yano.appchain.history.client.CardanoHistoryProofBundle;
import com.bloxbean.cardano.yano.appchain.history.client.CardanoHistoryPortableProofVerifier;
import com.bloxbean.cardano.yano.appchain.history.client.CardanoHistoryPortableParametersProof;
import com.bloxbean.cardano.yano.appchain.history.client.CardanoHistoryPortableStakeProof;
import com.bloxbean.cardano.yano.appchain.history.client.CardanoHistoryTrustedRoot;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;

import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

/** Dependency-light command line interface for Cardano History. */
public final class CardanoHistoryCli {
    public static final int OK = 0;
    public static final int USAGE = 2;
    public static final int UNAVAILABLE = 3;
    public static final int INVALID = 4;
    public static final int ROOT_ONLY = 5;
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int MAX_BUNDLE_JSON_BYTES = 9 * 1024 * 1024;
    private static final int MAX_TRUSTED_ROOT_JSON_BYTES = 64 * 1024;

    private CardanoHistoryCli() { }

    public static void main(String[] args) {
        System.exit(run(args));
    }

    static int run(String[] args) {
        try {
            if (args != null && args.length == 1
                    && ("--help".equals(args[0]) || "-h".equals(args[0]))) {
                System.out.println(usage());
                return OK;
            }
            Arguments input = Arguments.parse(args);
            if (input.command("config", "render")) {
                System.out.println(render(input.option("preset", "params-only-v1")));
                return OK;
            }
            if (input.command("verify")) return verify(input);
            CardanoHistoryClient client = CardanoHistoryClient.builder(
                            input.required("url"), input.required("chain"))
                    .apiKey(input.option("api-key", null)).build();
            if (input.command("status")) print(client.status());
            else if (input.command("epochs")) print(client.epochs(input.integer(
                    "limit", CardanoHistoryClient.DEFAULT_EPOCH_PAGE)));
            else if (input.command("query", "params")) {
                var value = client.parameters(input.longValue("epoch"));
                print(value);
                if (!value.found()) return UNAVAILABLE;
            } else if (input.command("query", "stake")) {
                var value = client.stake(input.longValue("epoch"), input.credentialType(),
                        input.hex("credential", 28));
                print(value);
                if (!value.complete()) return UNAVAILABLE;
            } else if (input.command("query", "drep")) {
                var value = client.drep(input.longValue("epoch"), input.integer("drep-type", 0),
                        input.hex("drep", 28));
                print(value);
                if (!value.complete()) return UNAVAILABLE;
            } else if (input.command("query", "proposal")) {
                var value = client.proposal(input.longValue("epoch"), input.hex("tx-id", 32),
                        input.integer("index", -1));
                print(value);
                if (!value.complete()) return UNAVAILABLE;
            } else if (input.command("proof", "stake")) {
                return proofStake(client, input);
            } else if (input.command("proof", "params")) {
                return proofParameters(client, input);
            } else throw new UsageException();
            return OK;
        } catch (UsageException failure) {
            System.err.println(usage());
            return USAGE;
        } catch (IllegalArgumentException failure) {
            System.err.println(failure.getMessage());
            System.err.println(usage());
            return USAGE;
        } catch (CardanoHistoryClient.CardanoHistoryClientException unavailable) {
            System.err.println(unavailable.getMessage());
            return UNAVAILABLE;
        } catch (Exception failure) {
            System.err.println("Cardano History command failed: " + failure.getMessage());
            return INVALID;
        }
    }

    private static int proofStake(CardanoHistoryClient client, Arguments input) throws Exception {
        long epoch = input.longValue("epoch");
        int type = input.credentialType();
        byte[] credential = input.hex("credential", 28);
        BigInteger coin = new BigInteger(input.option("coin", "0"));
        byte[] pool = input.optionalHex("pool", 28);
        var mode = CardanoHistoryProofBundle.StakeMode.valueOf(
                input.option("mode", "MINIMUM").toUpperCase().replace('-', '_'));
        var result = client.stakeProof(epoch, type, credential, mode, coin, pool);
        if (result.isEmpty()) return UNAVAILABLE;
        var nested = result.orElseThrow().proof();
        var primary = nested.primaryProof();
        var root = new ProofVerifier.TrustedStateRoot(nested.anchor().chainId(), primary.profile(),
                primary.genesisIdHex(), nested.anchor().anchoredHeight(),
                nested.anchor().stateRootHex(), ProofVerifier.TrustedRootSource.CALLER_PINNED,
                nested.anchor().blockHashHex());
        var portable = CardanoHistoryPortableStakeProof.from(result.orElseThrow(), root);
        String encoded = JSON.writerWithDefaultPrettyPrinter().writeValueAsString(portable);
        String output = input.required("output");
        Files.writeString(Path.of(output), encoded + System.lineSeparator());
        System.out.println("Wrote root-fixed proof bundle to " + output);
        return OK;
    }

    private static int proofParameters(CardanoHistoryClient client, Arguments input) throws Exception {
        long epoch = input.longValue("epoch");
        var result = client.parameterProof(epoch);
        if (result.isEmpty()) return UNAVAILABLE;
        var fact = result.orElseThrow().fact().proof();
        var root = new ProofVerifier.TrustedStateRoot(fact.chainId(), fact.profile(),
                fact.genesisIdHex(), fact.committedHeight(), fact.stateRootHex(),
                ProofVerifier.TrustedRootSource.CALLER_PINNED,
                fact.block() == null ? "" : fact.block().blockHashHex());
        var portable = CardanoHistoryPortableParametersProof.from(result.orElseThrow(), root);
        String output = input.required("output");
        Files.writeString(Path.of(output), JSON.writerWithDefaultPrettyPrinter()
                .writeValueAsString(portable) + System.lineSeparator());
        System.out.println("Wrote root-fixed protocol-parameters proof to " + output);
        return OK;
    }

    private static int verify(Arguments input) throws Exception {
        String encoded = readBounded(Path.of(input.required("bundle")), MAX_BUNDLE_JSON_BYTES);
        JsonNode shape = JSON.readTree(encoded);
        String trusted = input.option("trusted-root", null);
        ProofVerifier.TrustedStateRoot external = trusted == null ? null
                : JSON.readValue(readBounded(Path.of(trusted), MAX_TRUSTED_ROOT_JSON_BYTES),
                CardanoHistoryTrustedRoot.class).toVerifierRoot();
        if (shape.has("canonicalBundleHex")) {
            var portable = JSON.readValue(encoded, CardanoHistoryPortableStakeProof.class);
            var result = external == null ? CardanoHistoryPortableProofVerifier.verify(portable)
                    : CardanoHistoryPortableProofVerifier.verify(portable, external);
            System.out.println(result);
            return verificationExit(result.name());
        }
        if (shape.has("proof")) {
            var portable = JSON.readValue(encoded, CardanoHistoryPortableParametersProof.class);
            var result = external == null ? portable.verify() : portable.verify(external);
            System.out.println(result);
            return verificationExit(result.name());
        }
        return INVALID;
    }

    private static int verificationExit(String result) {
        return switch (result) {
            case "INVALID" -> INVALID;
            case "ROOT_VERIFIED_ANCHOR_UNCHECKED" -> ROOT_ONLY;
            case "L1_ANCHORED_VALID" -> OK;
            default -> INVALID;
        };
    }

    private static String readBounded(Path path, int maximumBytes) throws Exception {
        byte[] encoded;
        try (var input = Files.newInputStream(path)) {
            encoded = input.readNBytes(maximumBytes + 1);
        }
        if (encoded.length > maximumBytes) throw new IllegalArgumentException("input file exceeds limit");
        return new String(encoded, java.nio.charset.StandardCharsets.UTF_8);
    }

    private static String render(String preset) {
        boolean stake = preset.equals("params-stake-v1") || preset.equals("full-v1");
        boolean governance = preset.equals("params-governance-v1") || preset.equals("full-v1");
        if (!preset.equals("params-only-v1") && !stake && !governance) throw new UsageException();
        StringBuilder yaml = new StringBuilder("state-machine: cardano-history\n")
                .append("machines:\n  cardano-history:\n    preset: ").append(preset).append('\n')
                .append("observers:\n  epoch-params:\n    type: l1-epoch-params-v1\n");
        if (stake) yaml.append("  epoch-stake:\n    type: l1-epoch-stake-v1\n");
        if (governance) yaml.append("  epoch-governance:\n    type: l1-epoch-governance-v1\n");
        return yaml.toString();
    }

    private static void print(Object value) throws Exception {
        System.out.println(JSON.writerWithDefaultPrettyPrinter().writeValueAsString(value));
    }

    private static String usage() {
        return "Usage: yano-cardano-history <command> --url <.../api/v1> --chain <id> [--api-key KEY]\n"
                + "  status | epochs [--limit 1-15]\n"
                + "  query params --epoch E\n"
                + "  query stake --epoch E --credential HEX56 [--credential-type key|script]\n"
                + "  query drep --epoch E --drep HEX56 [--drep-type N]\n"
                + "  query proposal --epoch E --tx-id HEX64 --index N\n"
                + "  proof params --epoch E --output FILE\n"
                + "  proof stake --epoch E --credential HEX56 --coin LOVELACE --output FILE\n"
                + "    [--credential-type key|script] [--pool HEX56] [--mode minimum|pool|combined|exact|absence]\n"
                + "  verify --bundle FILE [--trusted-root INDEPENDENT_ROOT.json]\n"
                + "  config render --preset params-only-v1|params-stake-v1|params-governance-v1|full-v1";
    }

    private static final class Arguments {
        private final String[] command;
        private final Map<String, String> options;

        private Arguments(String[] command, Map<String, String> options) {
            this.command = command;
            this.options = options;
        }

        static Arguments parse(String[] args) {
            if (args == null || args.length == 0) throw new UsageException();
            int firstOption = 0;
            while (firstOption < args.length && !args[firstOption].startsWith("--")) firstOption++;
            if (firstOption == 0 || firstOption > 2) throw new UsageException();
            Map<String, String> options = new LinkedHashMap<>();
            for (int index = firstOption; index < args.length; index += 2) {
                if (!args[index].startsWith("--") || index + 1 >= args.length) throw new UsageException();
                String key = args[index].substring(2);
                if (key.isBlank() || options.putIfAbsent(key, args[index + 1]) != null) throw new UsageException();
            }
            return new Arguments(Arrays.copyOf(args, firstOption), Map.copyOf(options));
        }

        boolean command(String... expected) { return Arrays.equals(command, expected); }
        String required(String name) {
            String value = options.get(name);
            if (value == null || value.isBlank()) throw new UsageException();
            return value;
        }
        String option(String name, String fallback) { return options.getOrDefault(name, fallback); }
        int integer(String name, int fallback) {
            try { return Integer.parseInt(option(name, Integer.toString(fallback))); }
            catch (NumberFormatException invalid) { throw new UsageException(); }
        }
        long longValue(String name) {
            try { long value = Long.parseLong(required(name)); if (value < 0) throw new UsageException(); return value; }
            catch (NumberFormatException invalid) { throw new UsageException(); }
        }
        int credentialType() {
            String type = option("credential-type", "key");
            if ("key".equals(type) || "0".equals(type)) return 0;
            if ("script".equals(type) || "1".equals(type)) return 1;
            throw new UsageException();
        }
        byte[] hex(String name, int bytes) {
            String value = required(name);
            if (value.length() != bytes * 2 || !value.matches("[0-9a-f]+")) throw new UsageException();
            return HexFormat.of().parseHex(value);
        }
        byte[] optionalHex(String name, int bytes) {
            String value = options.get(name);
            return value == null ? new byte[0] : hex(name, bytes);
        }
    }

    private static final class UsageException extends RuntimeException { }
}
