package com.bloxbean.cardano.yano.appchain.stdlib.contracts;

import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Offline canonical action assembly and external-signature completion CLI. */
public final class AuthenticatedMapAuthorizationCli {
    private static final HexFormat HEX = HexFormat.of();

    private AuthenticatedMapAuthorizationCli() {
    }

    public static String execute(String[] args) {
        if (args == null || args.length == 0) throw invalid();
        Map<String, String> options = options(args);
        return switch (args[0]) {
            case "action" -> action(options);
            case "action-commitment" -> actionCommitment(options);
            case "direct-preimage" -> direct(options, false);
            case "direct-complete" -> direct(options, true);
            case "approval-payload" -> approvalPayload(options);
            case "approval-reference" -> approvalReference(options);
            case "command" -> command(options);
            default -> throw invalid();
        };
    }

    private static String action(Map<String, String> options) {
        requireOnly(options, "--command-hex", "--assignments");
        AuthenticatedMapContract.Command command = AuthenticatedMapContract.decodeCommand(
                bytes(options, "--command-hex"));
        List<AuthenticatedMapAuthorizationContract.AuthorizationAssignmentV1> assignments =
                assignments(require(options, "--assignments"));
        var action = new AuthenticatedMapAuthorizationContract.MapActionV1(
                command.batch(), command.mutations(), assignments);
        return HEX.formatHex(AuthenticatedMapAuthorizationContract.encodeAction(action));
    }

    private static String actionCommitment(Map<String, String> options) {
        requireOnly(options, "--action-hex");
        return HEX.formatHex(AuthenticatedMapAuthorizationContract.actionCommitment(
                decodedAction(options)));
    }

    private static String direct(Map<String, String> options, boolean complete) {
        Set<String> allowed = Set.of("--action-hex", "--authorization-id", "--chain",
                "--genesis-id", "--indexes", "--policy", "--policy-revision",
                "--actor", "--actor-revision", "--key", "--public-key",
                "--issued-height", "--deadline-height", "--signature");
        if (!allowed.containsAll(options.keySet())
                || complete != options.containsKey("--signature")) throw invalid();
        var action = decodedAction(options);
        var unsigned = new AuthenticatedMapAuthorizationContract.MapActorAuthorizationV1(
                exact(options, "--authorization-id", 32), require(options, "--chain"),
                exact(options, "--genesis-id", 32),
                AuthenticatedMapAuthorizationContract.actionCommitment(action),
                indexes(require(options, "--indexes")), require(options, "--policy"),
                number(options, "--policy-revision"), require(options, "--actor"),
                number(options, "--actor-revision"), require(options, "--key"),
                exact(options, "--public-key", 32), number(options, "--issued-height"),
                number(options, "--deadline-height"),
                AuthenticatedMapAuthorizationContract.SIGNATURE_ED25519,
                complete ? exact(options, "--signature", 64) : new byte[64]);
        if (!complete) return HEX.formatHex(unsigned.signingPreimage());
        if (!unsigned.verifyClaimedKey()) throw invalid();
        return HEX.formatHex(unsigned.encode());
    }

    private static String approvalPayload(Map<String, String> options) {
        requireOnly(options, "--action-hex", "--genesis-id");
        return HEX.formatHex(AuthenticatedMapAuthorizationContract.approvalPayloadHash(
                exact(options, "--genesis-id", 32),
                AuthenticatedMapAuthorizationContract.actionCommitment(
                        decodedAction(options))));
    }

    private static String approvalReference(Map<String, String> options) {
        requireOnly(options, "--action-hex", "--proposal", "--indexes", "--policy",
                "--policy-revision");
        var reference = new AuthenticatedMapAuthorizationContract.MapApprovalReferenceV1(
                require(options, "--proposal"),
                AuthenticatedMapAuthorizationContract.actionCommitment(
                        decodedAction(options)), indexes(require(options, "--indexes")),
                require(options, "--policy"), number(options, "--policy-revision"));
        return HEX.formatHex(reference.encode());
    }

    private static String command(Map<String, String> options) {
        requireOnly(options, "--action-hex", "--evidence-hex");
        List<AuthenticatedMapAuthorizationContract.AuthorizationEvidenceV1> evidence =
                new ArrayList<>();
        String values = options.getOrDefault("--evidence-hex", "");
        if (!values.isEmpty()) {
            for (String encoded : values.split(",", -1)) {
                byte[] bytes = parseHex(encoded);
                try {
                    evidence.add(AuthenticatedMapAuthorizationContract
                            .MapActorAuthorizationV1.decode(bytes));
                } catch (IllegalArgumentException notActor) {
                    evidence.add(AuthenticatedMapAuthorizationContract
                            .MapApprovalReferenceV1.decode(bytes));
                }
            }
        }
        var command = new AuthenticatedMapAuthorizationContract.AuthenticatedMapCommandV1(
                decodedAction(options), evidence);
        return HEX.formatHex(AuthenticatedMapAuthorizationContract.encodeCommand(command));
    }

    private static List<AuthenticatedMapAuthorizationContract.AuthorizationAssignmentV1>
    assignments(String value) {
        List<AuthenticatedMapAuthorizationContract.AuthorizationAssignmentV1> result =
                new ArrayList<>();
        if (value.isEmpty()) throw invalid();
        for (String item : value.split(",", -1)) {
            String[] fields = item.split(":", -1);
            if (fields.length != 4) throw invalid();
            int kind = switch (fields[1]) {
                case "open" -> AuthenticatedMapContract.AUTH_OPEN;
                case "owner" -> AuthenticatedMapContract.AUTH_OWNER;
                case "member" -> AuthenticatedMapContract.AUTH_MEMBER;
                case "governed-role" -> AuthenticatedMapContract.AUTH_GOVERNED_ROLE;
                case "approval" -> AuthenticatedMapContract.AUTH_APPROVAL;
                default -> throw invalid();
            };
            result.add(new AuthenticatedMapAuthorizationContract.AuthorizationAssignmentV1(
                    integer(fields[0]), kind, fields[2], integer(fields[3])));
        }
        return List.copyOf(result);
    }

    private static List<Integer> indexes(String value) {
        if (value.isEmpty()) throw invalid();
        return java.util.Arrays.stream(value.split(",", -1))
                .map(AuthenticatedMapAuthorizationCli::integer).toList();
    }

    private static AuthenticatedMapAuthorizationContract.MapActionV1 decodedAction(
            Map<String, String> options) {
        return AuthenticatedMapAuthorizationContract.decodeAction(
                bytes(options, "--action-hex"));
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

    private static void requireOnly(Map<String, String> options, String... allowed) {
        if (!Set.of(allowed).equals(options.keySet())) throw invalid();
    }

    private static String require(Map<String, String> options, String name) {
        String value = options.get(name);
        if (value == null || value.isBlank()) throw invalid();
        return value;
    }

    private static byte[] bytes(Map<String, String> options, String name) {
        return parseHex(require(options, name));
    }

    private static byte[] exact(Map<String, String> options, String name, int length) {
        byte[] value = bytes(options, name);
        if (value.length != length) throw invalid();
        return value;
    }

    private static byte[] parseHex(String value) {
        try {
            if (!value.matches("(?:[0-9a-f]{2})+")) throw invalid();
            return HEX.parseHex(value);
        } catch (RuntimeException malformed) {
            throw invalid();
        }
    }

    private static long number(Map<String, String> options, String name) {
        String value = require(options, name);
        if (!value.matches("[1-9][0-9]{0,18}")) throw invalid();
        try { return Long.parseLong(value); }
        catch (NumberFormatException malformed) { throw invalid(); }
    }

    private static int integer(String value) {
        if (!value.matches("0|[1-9][0-9]{0,9}")) throw invalid();
        try { return Integer.parseInt(value); }
        catch (NumberFormatException malformed) { throw invalid(); }
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("invalid authenticated-map authorization arguments");
    }
}
