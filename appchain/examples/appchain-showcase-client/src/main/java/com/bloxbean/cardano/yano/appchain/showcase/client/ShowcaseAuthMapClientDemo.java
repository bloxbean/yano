package com.bloxbean.cardano.yano.appchain.showcase.client;

import com.bloxbean.cardano.client.crypto.KeyGenUtil;
import com.bloxbean.cardano.yano.appchain.client.AppChainClient;
import com.bloxbean.cardano.yano.appchain.client.ProofVerifier;
import com.bloxbean.cardano.yano.appchain.client.StdlibAppChainClient;
import com.bloxbean.cardano.yano.appchain.stdlib.contracts.AuthenticatedMapAuthorizationContract;
import com.bloxbean.cardano.yano.appchain.stdlib.contracts.AuthenticatedMapContract;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.List;

/**
 * Demo-only walkthrough of the release-matched Java client against a running
 * showcase authenticated-map chain. Every scenario resolves the chain's own
 * committed genesis first, so the same commands work for the governed MPF
 * chain (authenticated-map-chain) and the basic classic-JMT contrast chain
 * (authenticated-map-jmt-chain).
 *
 * <p>Signing: a real integration signs the direct-role preimage in a
 * wallet/KMS/HSM. This demo derives the deterministic showcase actor seed
 * (sha256("yano-showcase-demo-actor:" + actorId)) — showcase-only material.
 */
public final class ShowcaseAuthMapClientDemo {
    private static final HexFormat HEX = HexFormat.of();

    private ShowcaseAuthMapClientDemo() {
    }

    public static void main(String[] arguments) throws Exception {
        if (arguments.length < 3) {
            usage();
            System.exit(2);
        }
        String baseUrl = arguments[0];
        String chainId = arguments[1];
        String scenario = arguments[2];
        AppChainClient client = AppChainClient.builder(baseUrl).chainId(chainId).build();
        StdlibAppChainClient stdlib = new StdlibAppChainClient(client);
        switch (scenario) {
            case "basic-put" -> basicPut(stdlib, argument(arguments, 3, "collection"),
                    argument(arguments, 4, "key"), argument(arguments, 5, "value"));
            case "governed-put" -> governedPut(stdlib, chainId,
                    argument(arguments, 3, "key"), argument(arguments, 4, "value"));
            case "reads" -> reads(stdlib, argument(arguments, 3, "collection"),
                    argument(arguments, 4, "key"));
            case "load" -> load(stdlib, argument(arguments, 3, "collection"),
                    Integer.parseInt(argument(arguments, 4, "count")));
            case "verified-entry" -> verifiedEntry(client, chainId,
                    argument(arguments, 3, "collection"), argument(arguments, 4, "key"));
            default -> {
                usage();
                System.exit(2);
            }
        }
    }

    /**
     * Basic (open/owner/member) write: the authorization kind is discovered
     * from the committed genesis and the command is submitted as the final v1
     * envelope with an evidence-free assignment.
     */
    private static void basicPut(StdlibAppChainClient stdlib, String collectionId,
                                 String key, String value) throws Exception {
        AuthenticatedMapContract.Genesis genesis = genesis(stdlib);
        AuthenticatedMapContract.CollectionDescriptor collection =
                genesis.collections().stream()
                        .filter(entry -> entry.id().equals(collectionId))
                        .findFirst().orElseThrow(() -> new IllegalArgumentException(
                                "unknown collection: " + collectionId));
        if (collection.authorization() > AuthenticatedMapContract.AUTH_MEMBER) {
            throw new IllegalArgumentException(
                    "collection requires governed evidence; use governed-put");
        }
        var command = AuthenticatedMapContract.Command.single(
                AuthenticatedMapContract.Mutation.put(collectionId,
                        key.getBytes(StandardCharsets.UTF_8),
                        value.getBytes(StandardCharsets.UTF_8)));
        var action = AuthenticatedMapAuthorizationContract.MapActionV1.basic(
                command, List.of(collection.authorization()));
        AppChainClient.SubmitResult accepted = stdlib.authenticatedMapGovernedCommand(
                new AuthenticatedMapAuthorizationContract.AuthenticatedMapCommandV1(
                        action, List.of()));
        System.out.println("accepted for sequencing: " + accepted.messageId());
        awaitApplied(stdlib, HEX.parseHex(accepted.messageId()));
        printEntry(stdlib, collectionId, key);
    }

    /**
     * Direct-role write for a governed collection: assemble the action, sign
     * the actor authorization preimage with the demo issuer's Ed25519 seed,
     * submit the evidence-bound command, and verify the one-time consumption.
     */
    private static void governedPut(StdlibAppChainClient stdlib, String chainId,
                                    String key, String value) throws Exception {
        AuthenticatedMapContract.Genesis genesis = genesis(stdlib);
        String collectionId = "governed-catalog";
        String policyId = "issuer-write";
        String actorId = "issuer-a";
        byte[] genesisId = AuthenticatedMapContract.genesisId(genesis);
        long height = stdlib.authenticatedMapEntry(
                collectionId, key.getBytes(StandardCharsets.UTF_8)).committedHeight();

        var command = AuthenticatedMapContract.Command.single(
                AuthenticatedMapContract.Mutation.put(collectionId,
                        key.getBytes(StandardCharsets.UTF_8),
                        value.getBytes(StandardCharsets.UTF_8)));
        var action = new AuthenticatedMapAuthorizationContract.MapActionV1(
                false, command.mutations(), List.of(
                new AuthenticatedMapAuthorizationContract.AuthorizationAssignmentV1(
                        0, AuthenticatedMapContract.AUTH_GOVERNED_ROLE, policyId, 1)));

        byte[] seed = demoActorSeed(actorId);
        byte[] publicKey = KeyGenUtil.getPublicKeyFromPrivateKey(seed);
        byte[] authorizationId = new byte[32];
        new SecureRandom().nextBytes(authorizationId);
        // In production this sign(...) call is replaced by exporting
        // signingPreimage() to an external wallet/KMS/HSM signer.
        var evidence = AuthenticatedMapAuthorizationContract.MapActorAuthorizationV1.sign(
                authorizationId, chainId, genesisId,
                AuthenticatedMapAuthorizationContract.actionCommitment(action),
                List.of(0), policyId, 1, actorId, 1, actorId + "-k1", publicKey,
                Math.max(1, height), Math.max(1, height) + 90, seed);

        AppChainClient.SubmitResult accepted = stdlib.authenticatedMapGovernedCommand(
                new AuthenticatedMapAuthorizationContract.AuthenticatedMapCommandV1(
                        action, List.of(evidence)));
        System.out.println("accepted for sequencing: " + accepted.messageId());
        awaitApplied(stdlib, HEX.parseHex(accepted.messageId()));
        var consumption = stdlib.authenticatedMapDirectConsumption(actorId, authorizationId)
                .orElseThrow(() -> new IllegalStateException("consumption record missing"));
        System.out.println("direct authorization consumed once at height "
                + consumption.appliedHeight() + " under policy "
                + consumption.policyId() + " r" + consumption.policyRevision());
        printEntry(stdlib, collectionId, key);
    }

    /**
     * Bulk load: submits {@code count} single-mutation commands with unique
     * keys to one basic collection through a small concurrent pool, then
     * polls receipts and reports throughput. Pool-full (backpressure)
     * responses are retried a few times so the scenario also demonstrates the
     * node's bounded ingress behavior under burst.
     */
    private static void load(StdlibAppChainClient stdlib, String collectionId, int count)
            throws Exception {
        if (count < 1 || count > 1_000) {
            throw new IllegalArgumentException("count must be 1-1000");
        }
        AuthenticatedMapContract.Genesis genesis = genesis(stdlib);
        AuthenticatedMapContract.CollectionDescriptor collection =
                genesis.collections().stream()
                        .filter(entry -> entry.id().equals(collectionId))
                        .findFirst().orElseThrow(() -> new IllegalArgumentException(
                                "unknown collection: " + collectionId));
        if (collection.authorization() > AuthenticatedMapContract.AUTH_MEMBER) {
            throw new IllegalArgumentException("load supports open/owner/member collections");
        }
        byte[] tag = new byte[4];
        new SecureRandom().nextBytes(tag);
        String runTag = HEX.formatHex(tag);
        System.out.println("loading " + count + " messages into " + collectionId
                + " (run tag " + runTag + ")");

        long started = System.nanoTime();
        var pool = java.util.concurrent.Executors.newFixedThreadPool(4);
        var accepted = new java.util.concurrent.ConcurrentLinkedQueue<String>();
        var failures = new java.util.concurrent.atomic.AtomicInteger();
        try {
            var tasks = new java.util.ArrayList<java.util.concurrent.Future<?>>();
            for (int sequence = 0; sequence < count; sequence++) {
                final int index = sequence;
                tasks.add(pool.submit(() -> {
                    var command = AuthenticatedMapContract.Command.single(
                            AuthenticatedMapContract.Mutation.put(collectionId,
                                    ("load-" + runTag + "-" + index)
                                            .getBytes(StandardCharsets.UTF_8),
                                    ("value-" + index).getBytes(StandardCharsets.UTF_8)));
                    var action = AuthenticatedMapAuthorizationContract.MapActionV1.basic(
                            command, List.of(collection.authorization()));
                    var envelope = new AuthenticatedMapAuthorizationContract
                            .AuthenticatedMapCommandV1(action, List.of());
                    for (int attempt = 1; ; attempt++) {
                        try {
                            accepted.add(stdlib.authenticatedMapGovernedCommand(envelope)
                                    .messageId());
                            return;
                        } catch (RuntimeException backpressure) {
                            if (attempt >= 5) {
                                failures.incrementAndGet();
                                return;
                            }
                            try {
                                Thread.sleep(250L * attempt);
                            } catch (InterruptedException interrupted) {
                                Thread.currentThread().interrupt();
                                failures.incrementAndGet();
                                return;
                            }
                        }
                    }
                }));
            }
            for (var task : tasks) {
                task.get();
            }
        } finally {
            pool.shutdown();
        }
        long submitMillis = (System.nanoTime() - started) / 1_000_000;
        System.out.printf("submitted %d (failed %d) in %d ms (%.1f msg/s)%n",
                accepted.size(), failures.get(), submitMillis,
                accepted.size() * 1000.0 / Math.max(1, submitMillis));

        int applied = 0;
        int rejected = 0;
        long deadline = System.currentTimeMillis() + 120_000;
        for (String messageId : accepted) {
            byte[] id = HEX.parseHex(messageId);
            while (true) {
                AuthenticatedMapContract.ReceiptResult receipt =
                        stdlib.authenticatedMapReceipt(id);
                if (receipt.receipt() != null) {
                    if (receipt.receipt().status()
                            == AuthenticatedMapContract.RECEIPT_APPLIED) {
                        applied++;
                    } else {
                        rejected++;
                    }
                    break;
                }
                if (System.currentTimeMillis() > deadline) {
                    throw new IllegalStateException(
                            "finality polling timed out with " + applied
                                    + " applied so far");
                }
                Thread.sleep(500);
            }
        }
        long totalMillis = (System.nanoTime() - started) / 1_000_000;
        long height = stdlib.authenticatedMapReceipt(
                HEX.parseHex(accepted.peek())).committedHeight();
        System.out.printf(
                "finalized: %d applied, %d rejected in %d ms end to end "
                        + "(%.1f applied/s), committed height now %d%n",
                applied, rejected, totalMillis,
                applied * 1000.0 / Math.max(1, totalMillis), height);

        int samples = Math.min(10, count);
        System.out.println("sample keys to explore (of " + count + " loaded):");
        for (int sample = 0; sample < samples; sample++) {
            // Spread evenly across the range so samples cover early and late blocks.
            int index = (int) ((long) sample * (count - 1) / Math.max(1, samples - 1));
            System.out.println("  load-" + runTag + "-" + index);
        }
        System.out.println("try: reads " + collectionId + " load-" + runTag + "-0"
                + "  |  verified-entry " + collectionId + " load-" + runTag + "-0"
                + "  |  console entry lookup on collection '" + collectionId + "'");
    }

    /** Root-attested reads: exact entry and receipt lookups. */
    private static void reads(StdlibAppChainClient stdlib, String collectionId, String key) {
        printEntry(stdlib, collectionId, key);
        AuthenticatedMapContract.Genesis genesis = genesis(stdlib);
        System.out.println("chain genesis id: "
                + HEX.formatHex(AuthenticatedMapContract.genesisId(genesis)));
        System.out.println("commitment profile: " + genesis.commitmentProfileId());
        System.out.println("collections: " + genesis.collections().stream()
                .map(AuthenticatedMapContract.CollectionDescriptor::id).toList());
    }

    /**
     * The trust boundary demo: decode the entry only after its merkle proof
     * verifies against a caller-pinned trusted root. In production the pinned
     * root comes from an independently observed finality certificate or L1
     * anchor, not from the same HTTP response.
     */
    private static void verifiedEntry(AppChainClient client, String chainId,
                                      String collectionId, String key) {
        // The helper proves the composite physical key of the entry leaf and
        // decodes only after the proof verifies against the resolved root.
        StdlibAppChainClient verifying = new StdlibAppChainClient(client, proof ->
                new ProofVerifier.TrustedStateRoot(chainId, proof.profile(),
                        proof.genesisIdHex(), proof.committedHeight(),
                        proof.stateRootHex(),
                        ProofVerifier.TrustedRootSource.CALLER_PINNED));
        var verified = verifying.authenticatedMapProof(
                        collectionId, key.getBytes(StandardCharsets.UTF_8))
                .orElseThrow(() -> new IllegalStateException("no entry to verify"));
        System.out.println("proof-verified entry: status=" + verified.value().status()
                + " revision=" + verified.value().revision()
                + " value=" + new String(verified.value().value(), StandardCharsets.UTF_8)
                + " root=" + verified.proof().stateRootHex()
                + " height=" + verified.proof().committedHeight());
    }

    private static AuthenticatedMapContract.Genesis genesis(StdlibAppChainClient stdlib) {
        AppChainClient.QueryResult result = stdlib.client().query(
                AuthenticatedMapContract.CAPABILITIES_QUERY_PATH, new byte[0]);
        return AuthenticatedMapContract.decodeGenesis(result.payload());
    }

    private static void awaitApplied(StdlibAppChainClient stdlib, byte[] messageId)
            throws InterruptedException {
        for (int attempt = 0; attempt < 60; attempt++) {
            AuthenticatedMapContract.ReceiptResult receipt =
                    stdlib.authenticatedMapReceipt(messageId);
            if (receipt.receipt() != null) {
                var value = receipt.receipt();
                System.out.println("state machine "
                        + (value.status() == AuthenticatedMapContract.RECEIPT_APPLIED
                        ? "APPLIED" : "REJECTED (error " + value.errorCode() + ")")
                        + " at height " + value.height());
                if (value.status() != AuthenticatedMapContract.RECEIPT_APPLIED) {
                    throw new IllegalStateException("command was rejected");
                }
                return;
            }
            Thread.sleep(1_000);
        }
        throw new IllegalStateException("command did not finalize within 60 seconds");
    }

    private static void printEntry(StdlibAppChainClient stdlib, String collectionId,
                                   String key) {
        AuthenticatedMapContract.PointResult point = stdlib.authenticatedMapEntry(
                collectionId, key.getBytes(StandardCharsets.UTF_8));
        if (point.entry() == null) {
            System.out.println("entry " + collectionId + "/" + key + ": "
                    + (point.presence() == AuthenticatedMapContract.PRESENCE_REVOKED
                    ? "REVOKED" : "ABSENT")
                    + " at height " + point.committedHeight());
            return;
        }
        System.out.println("entry " + collectionId + "/" + key
                + ": revision " + point.entry().revision()
                + " value \"" + new String(point.entry().value(), StandardCharsets.UTF_8)
                + "\" at height " + point.committedHeight()
                + " root " + HEX.formatHex(point.stateRoot()));
    }

    private static byte[] demoActorSeed(String actorId) throws Exception {
        return MessageDigest.getInstance("SHA-256").digest(
                ("yano-showcase-demo-actor:" + actorId).getBytes(StandardCharsets.UTF_8));
    }

    private static String argument(String[] arguments, int index, String name) {
        if (index >= arguments.length) {
            throw new IllegalArgumentException("missing argument: " + name);
        }
        return arguments[index];
    }

    private static void usage() {
        System.err.println("""
                usage: ShowcaseAuthMapClientDemo <base-url> <chain-id> <scenario> [args]
                  basic-put <collection> <key> <value>
                  governed-put <key> <value>
                  reads <collection> <key>
                  verified-entry <collection> <key>
                  load <collection> <count>""");
    }
}
