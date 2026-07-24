package com.bloxbean.cardano.yano.appchain.eutxo.zk.lifecycle;

import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoL2Transaction;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoValidityTransition;
import com.bloxbean.cardano.yano.appchain.eutxo.zk.contracts.EutxoFinalizedProofWitness;
import com.bloxbean.cardano.yano.appchain.eutxo.zk.contracts.EutxoZkAuthorizationProfile;
import com.bloxbean.cardano.yano.appchain.eutxo.zk.contracts.EutxoZkBatchProfile;
import com.bloxbean.cardano.yano.appchain.eutxo.zk.contracts.EutxoZkBatchProof;
import com.bloxbean.cardano.yano.appchain.eutxo.zk.contracts.EutxoZkBatchVerificationKey;
import com.bloxbean.cardano.yano.appchain.eutxo.zk.zeroj.EutxoCeremonyManifest;
import com.bloxbean.cardano.yano.appchain.eutxo.zk.zeroj.EutxoJubjubBatchDevelopmentSetup;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/**
 * Project-local, restart-safe lifecycle for the experimental EUTxO validity
 * product. Secret proving material is isolated below an owner-only directory.
 */
public final class EutxoValidityLifecycle {
    public static final String UNSAFE_TESTNET_ACKNOWLEDGEMENT =
            "EUTXO_ZEROJ_UNSAFE_DEVELOPMENT_TESTNET";
    public static final String TRUST_WARNING =
            "EUTXO_ZEROJ_TRUSTED_PROVER_TEST_FUNDS_ONLY";
    public static final String SCHEMA = "yano-eutxo-validity-lifecycle-v1";

    private static final String LOCK_FILE = "appchain.lock";
    private static final int MAX_INPUT_BYTES = 2 * 1024 * 1024;
    private static final Set<PosixFilePermission> PRIVATE_DIRECTORY =
            EnumSet.of(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.OWNER_EXECUTE);
    private static final Set<PosixFilePermission> PRIVATE_FILE =
            EnumSet.of(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE);

    private final ObjectMapper json;
    private final Path project;
    private final Path root;
    private final Path stateFile;
    private final Path contractPlanFile;
    private final Path ceremonyDirectory;
    private final Path ceremonyManifestFile;
    private final Path verificationKeyFile;
    private final Path proofsDirectory;
    private final Path operationsDirectory;

    public EutxoValidityLifecycle(Path project) {
        this(project, new ObjectMapper());
    }

    EutxoValidityLifecycle(Path project, ObjectMapper json) {
        this.project = safeProject(project);
        this.json = Objects.requireNonNull(json, "json");
        this.root = this.project.resolve("runtime/validity");
        this.stateFile = root.resolve("state.json");
        this.contractPlanFile = root.resolve("contract-plan.json");
        this.ceremonyDirectory = root.resolve("ceremony/keys");
        this.ceremonyManifestFile =
                root.resolve("ceremony/manifest.json");
        this.verificationKeyFile =
                root.resolve("ceremony/verification-key.bin");
        this.proofsDirectory = root.resolve("proofs");
        this.operationsDirectory = root.resolve("operations");
    }

    public Result bootstrap(boolean developmentCeremony, boolean confirmed)
            throws IOException {
        ProjectIdentity identity = identity();
        requireLifecycleNetwork(identity);
        createDirectories();
        writeIdenticalOrNew(
                contractPlanFile,
                json.writerWithDefaultPrettyPrinter()
                        .writeValueAsBytes(contractPlan(identity)),
                false);

        if (developmentCeremony) {
            if (!confirmed) {
                throw new IllegalArgumentException(
                        "--development-ceremony requires --yes");
            }
            if (!"devnet".equals(identity.network())
                    && !identity.acknowledgements().contains(
                    UNSAFE_TESTNET_ACKNOWLEDGEMENT)) {
                throw new IllegalArgumentException(
                        "public testnet development ceremony requires "
                                + UNSAFE_TESTNET_ACKNOWLEDGEMENT);
            }
            generateDevelopmentCeremony(identity);
        }

        String stage = Files.isRegularFile(ceremonyManifestFile)
                ? "CEREMONY_READY_CONTRACTS_PLANNED"
                : "CONTRACTS_PLANNED_CEREMONY_REQUIRED";
        LifecycleState state = currentState(identity)
                .withStage(stage)
                .withUpdatedAt(Instant.now().toString());
        writeState(state);
        return result(stage, identity, state, Map.of(
                "contractPlan", relative(contractPlanFile),
                "ceremony", Files.isRegularFile(ceremonyManifestFile)
                        ? relative(ceremonyManifestFile) : "NOT_CREATED"));
    }

    public Result status() throws IOException {
        ProjectIdentity identity = identity();
        requireLifecycleNetwork(identity);
        LifecycleState state = readState(identity);
        return result(state.stage(), identity, state, Map.of(
                "contractPlan", present(contractPlanFile),
                "ceremonyManifest", present(ceremonyManifestFile),
                "verificationKey", present(verificationKeyFile),
                "proofCount", proofCount(),
                "operationCount", operationCount()));
    }

    public Result prove(
            List<Path> transitionFiles,
            String previousRootHex
    ) throws IOException {
        ProjectIdentity identity = identity();
        requireLifecycleNetwork(identity);
        if (transitionFiles == null || transitionFiles.isEmpty()) {
            throw new IllegalArgumentException(
                    "at least one --transition is required");
        }
        EutxoZkBatchProfile profile =
                EutxoZkBatchProfile.CARDANO_PAYMENT_B16;
        if (transitionFiles.size() > profile.maximumTransactions()) {
            throw new IllegalArgumentException(
                    "transition count exceeds " + profile.id());
        }
        byte[] previousRoot = digestBytes(
                previousRootHex, "previous root");
        EutxoCeremonyManifest manifest = readCeremonyManifest();
        EutxoZkBatchVerificationKey key = readVerificationKey();
        if (!profile.digest().equals(manifest.profileDigest())
                || !profile.circuitId().equals(manifest.circuitId())
                || !key.digestHex().equals(
                manifest.verificationKeyDigest())) {
            throw new IllegalStateException(
                    "ceremony, batch profile, and verification key differ");
        }
        List<EutxoL2Transaction> transactions =
                new ArrayList<>(transitionFiles.size());
        for (Path transitionFile : transitionFiles) {
            byte[] bytes = readBounded(
                    transitionFile, "finalized transition");
            EutxoFinalizedProofWitness witness =
                    EutxoFinalizedProofWitness.derive(
                            EutxoValidityTransition.decode(bytes));
            if (!identity.chainId().equals(
                    witness.transition().chainId())
                    || !identity.network().equals(
                    witness.transition().network())) {
                throw new IllegalArgumentException(
                        "finalized transition belongs to another project");
            }
            transactions.add(EutxoL2Transaction.decode(
                    witness.transition().canonicalTransaction()));
        }

        EutxoZkBatchProof proof;
        try (EutxoJubjubBatchDevelopmentSetup setup =
                     EutxoJubjubBatchDevelopmentSetup.load(
                             profile, ceremonyDirectory, manifest)) {
            proof = setup.prove(previousRoot, transactions);
        }
        if (!EutxoJubjubBatchDevelopmentSetup.verify(proof, key)) {
            throw new IllegalStateException(
                    "generated proof failed independent local verification");
        }
        Path output = proofsDirectory.resolve(
                proof.digestHex() + ".proof");
        writeIdenticalOrNew(output, proof.canonicalBytes(), false);
        LifecycleState state = readState(identity)
                .withStage("PROOF_READY")
                .withProofCount(proofCount())
                .withUpdatedAt(Instant.now().toString());
        writeState(state);
        return result("PROOF_READY", identity, state, Map.of(
                "proofId", proof.digestHex(),
                "proof", relative(output),
                "verificationKeyDigest", proof.verificationKeyDigest(),
                "batchProfile", proof.batchProfileId(),
                "transactionCount", proof.transactionIds().size(),
                "proofMillis", proof.proofMillis()));
    }

    public Result proof(String proofId) throws IOException {
        ProjectIdentity identity = identity();
        EutxoZkBatchProof proof = readProof(proofId);
        EutxoZkBatchVerificationKey key = readVerificationKey();
        boolean valid = EutxoJubjubBatchDevelopmentSetup.verify(
                proof, key);
        LifecycleState state = readState(identity);
        return result(valid ? "PROOF_VALID" : "PROOF_INVALID",
                identity, state, Map.of(
                        "proofId", proof.digestHex(),
                        "batchProfile", proof.batchProfileId(),
                        "authorizationProfile",
                        proof.authorizationProfile(),
                        "verificationKeyDigest",
                        proof.verificationKeyDigest(),
                        "transactionIds", proof.transactionIds(),
                        "valid", valid));
    }

    public DoctorReport doctor() throws IOException {
        ProjectIdentity identity = identity();
        List<Check> checks = new ArrayList<>();
        check(checks, "network",
                !"mainnet".equals(identity.network()),
                identity.network());
        check(checks, "recipe",
                identity.recipe().startsWith("eutxo-zeroj-preview:"),
                identity.recipe());
        check(checks, "authorization-profile",
                EutxoZkAuthorizationProfile.JUBJUB_DEVELOPMENT_V1
                        .id().equals(identity.authorizationProfile()),
                identity.authorizationProfile());
        check(checks, "trusted-prover",
                identity.trustedProverRequired(),
                Boolean.toString(identity.trustedProverRequired()));
        check(checks, "test-funds",
                "disposable-test-funds-only".equals(
                        identity.fundsPolicy()),
                identity.fundsPolicy());
        if (Set.of("preview", "preprod").contains(
                identity.network())) {
            check(checks, "unsafe-testnet-acknowledgement",
                    identity.acknowledgements().contains(
                            UNSAFE_TESTNET_ACKNOWLEDGEMENT),
                    UNSAFE_TESTNET_ACKNOWLEDGEMENT);
        }
        check(checks, "contract-plan",
                Files.isRegularFile(contractPlanFile),
                present(contractPlanFile));
        check(checks, "ceremony-manifest",
                Files.isRegularFile(ceremonyManifestFile),
                present(ceremonyManifestFile));
        check(checks, "verification-key",
                Files.isRegularFile(verificationKeyFile),
                present(verificationKeyFile));
        if (Files.isRegularFile(ceremonyManifestFile)
                && Files.isRegularFile(verificationKeyFile)) {
            try {
                EutxoCeremonyManifest manifest =
                        readCeremonyManifest();
                EutxoZkBatchVerificationKey key =
                        readVerificationKey();
                EutxoZkBatchProfile profile =
                        EutxoZkBatchProfile.CARDANO_PAYMENT_B16;
                EutxoJubjubBatchDevelopmentSetup setup =
                        EutxoJubjubBatchDevelopmentSetup.load(
                                profile, ceremonyDirectory, manifest);
                setup.close();
                check(checks, "ceremony-byte-identity",
                        key.digestHex().equals(
                                manifest.verificationKeyDigest()),
                        manifest.ceremonyId());
            } catch (RuntimeException failure) {
                check(checks, "ceremony-byte-identity",
                        false, safe(failure.getMessage()));
            }
        }
        boolean valid = checks.stream().allMatch(Check::passed);
        return new DoctorReport(
                valid ? "VALIDITY_DOCTOR_PASSED"
                        : "VALIDITY_DOCTOR_FAILED",
                identity.network(),
                identity.authorizationProfile(),
                true,
                "disposable-test-funds-only",
                List.copyOf(checks));
    }

    public Result prepareOperation(
            String kind,
            String operationId,
            Path request,
            String proofId
    ) throws IOException {
        ProjectIdentity identity = identity();
        requireLifecycleNetwork(identity);
        String normalizedKind = operationKind(kind);
        String id = safeId(operationId);
        Map<String, Object> operation = new TreeMap<>();
        operation.put("schemaVersion",
                "yano-eutxo-validity-operation-v1");
        operation.put("kind", normalizedKind);
        operation.put("operationId", id);
        operation.put("network", identity.network());
        operation.put("chainId", identity.chainId());
        operation.put("authorizationProfile",
                identity.authorizationProfile());
        operation.put("trustedProverRequired", true);
        operation.put("fundsPolicy", identity.fundsPolicy());
        operation.put("status", "PREPARED");
        if (request != null) {
            byte[] bytes = readBounded(request, "operation request");
            operation.put("requestDigest", sha256(bytes));
            operation.put("requestFile",
                    request.toAbsolutePath().normalize().getFileName()
                            .toString());
        }
        if (proofId != null) {
            EutxoZkBatchProof proof = readProof(proofId);
            operation.put("proofId", proof.digestHex());
            operation.put("verificationKeyDigest",
                    proof.verificationKeyDigest());
        }
        operation.put("createdAt", Instant.now().toString());
        Path output = operationPath(normalizedKind, id);
        writeIdenticalSemanticOrNew(output, operation);
        LifecycleState state = readState(identity)
                .withOperationCount(operationCount())
                .withUpdatedAt(Instant.now().toString());
        writeState(state);
        return result("OPERATION_PREPARED", identity, state,
                Map.of("kind", normalizedKind,
                        "operationId", id,
                        "operation", relative(output)));
    }

    public Result submitOperation(
            String kind,
            String operationId,
            Path signedTransaction,
            URI nodeUrl,
            String apiKey
    ) throws IOException, InterruptedException {
        ProjectIdentity identity = identity();
        requireLifecycleNetwork(identity);
        String normalizedKind = operationKind(kind);
        String id = safeId(operationId);
        Path operationFile = operationPath(normalizedKind, id);
        if (!Files.isRegularFile(operationFile)) {
            throw new IllegalStateException(
                    "operation must be prepared before submission");
        }
        Map<String, Object> operation = readMap(operationFile);
        if ("SUBMITTED".equals(operation.get("status"))
                || "STABLE".equals(operation.get("status"))) {
            return result("OPERATION_ALREADY_SUBMITTED",
                    identity, readState(identity), operation);
        }
        byte[] transaction = readBounded(
                signedTransaction, "signed Cardano transaction");
        URI endpoint = txSubmitEndpoint(nodeUrl);
        HttpRequest.Builder request = HttpRequest.newBuilder(endpoint)
                .header("Content-Type", "application/cbor")
                .POST(HttpRequest.BodyPublishers.ofByteArray(transaction));
        if (apiKey != null && !apiKey.isBlank()) {
            request.header("X-API-Key", apiKey);
        }
        HttpResponse<String> response = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .build()
                .send(request.build(),
                        HttpResponse.BodyHandlers.ofString(
                                StandardCharsets.UTF_8));
        if (response.statusCode() < 200
                || response.statusCode() >= 300) {
            throw new IllegalStateException(
                    "Cardano submission failed with HTTP "
                            + response.statusCode());
        }
        String transactionId = submittedTransactionId(response.body());
        operation.put("status", "SUBMITTED");
        operation.put("transactionId",
                transactionId.toLowerCase(Locale.ROOT));
        operation.put("signedTransactionDigest",
                sha256(transaction));
        operation.put("submittedAt", Instant.now().toString());
        writeAtomic(operationFile,
                json.writerWithDefaultPrettyPrinter()
                        .writeValueAsBytes(new TreeMap<>(operation)),
                false);
        return result("OPERATION_SUBMITTED",
                identity, readState(identity), operation);
    }

    private String submittedTransactionId(String responseBody)
            throws IOException {
        String body = Objects.requireNonNullElse(responseBody, "").trim();
        String transactionId;
        if (body.startsWith("{")) {
            Object value = readResponseMap(body).get("txHash");
            transactionId = value == null ? "" : value.toString();
        } else {
            transactionId = body.replace("\"", "");
        }
        if (!transactionId.matches("[0-9a-fA-F]{64}")) {
            throw new IllegalStateException(
                    "Cardano submission returned an invalid transaction id");
        }
        return transactionId;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readResponseMap(String body)
            throws IOException {
        return json.readValue(body, Map.class);
    }

    public Result markStable(
            String kind,
            String operationId,
            String transactionId
    ) throws IOException {
        ProjectIdentity identity = identity();
        String normalizedKind = operationKind(kind);
        String id = safeId(operationId);
        Path operationFile = operationPath(normalizedKind, id);
        Map<String, Object> operation = readMap(operationFile);
        String normalizedTransaction =
                safeDigest(transactionId, "transaction id");
        if (!normalizedTransaction.equals(
                operation.get("transactionId"))) {
            throw new IllegalArgumentException(
                    "stable transaction differs from submitted transaction");
        }
        operation.put("status", "STABLE");
        operation.put("stableAt", Instant.now().toString());
        writeAtomic(operationFile,
                json.writerWithDefaultPrettyPrinter()
                        .writeValueAsBytes(new TreeMap<>(operation)),
                false);
        return result("OPERATION_STABLE",
                identity, readState(identity), operation);
    }

    public Result reconcile() throws IOException {
        ProjectIdentity identity = identity();
        int prepared = 0;
        int submitted = 0;
        int stable = 0;
        if (Files.isDirectory(operationsDirectory)) {
            try (var files = Files.list(operationsDirectory)) {
                for (Path path : files.sorted().toList()) {
                    if (!Files.isRegularFile(path)
                            || !path.getFileName().toString()
                            .endsWith(".json")) {
                        continue;
                    }
                    String status = Objects.toString(
                            readMap(path).get("status"), "");
                    switch (status) {
                        case "PREPARED" -> prepared++;
                        case "SUBMITTED" -> submitted++;
                        case "STABLE" -> stable++;
                        default -> throw new IllegalStateException(
                                "operation journal has an invalid status");
                    }
                }
            }
        }
        LifecycleState state = readState(identity)
                .withOperationCount(prepared + submitted + stable)
                .withUpdatedAt(Instant.now().toString());
        writeState(state);
        return result("RECONCILED", identity, state, Map.of(
                "prepared", prepared,
                "submitted", submitted,
                "stable", stable));
    }

    private void generateDevelopmentCeremony(
            ProjectIdentity identity
    ) throws IOException {
        EutxoZkBatchProfile profile =
                EutxoZkBatchProfile.CARDANO_PAYMENT_B16;
        if (Files.isRegularFile(ceremonyManifestFile)
                && Files.isRegularFile(verificationKeyFile)) {
            EutxoCeremonyManifest manifest = readCeremonyManifest();
            try (EutxoJubjubBatchDevelopmentSetup ignored =
                         EutxoJubjubBatchDevelopmentSetup.load(
                                 profile, ceremonyDirectory, manifest)) {
                EutxoZkBatchVerificationKey key =
                        readVerificationKey();
                if (!key.digestHex().equals(
                        manifest.verificationKeyDigest())) {
                    throw new IllegalStateException(
                            "retained ceremony key identity differs");
                }
                return;
            }
        }
        if (Files.exists(ceremonyDirectory)
                && hasEntries(ceremonyDirectory)) {
            throw new IllegalStateException(
                    "refusing to overwrite an incomplete ceremony directory");
        }
        Files.createDirectories(ceremonyDirectory);
        restrict(ceremonyDirectory, true);
        System.setProperty(
                "zeroj.allowInsecureTrustedSetup", "true");
        EutxoZkBatchVerificationKey key;
        try (EutxoJubjubBatchDevelopmentSetup setup =
                     EutxoJubjubBatchDevelopmentSetup.create(
                             profile, ceremonyDirectory)) {
            key = setup.verificationKey();
        }
        EutxoCeremonyManifest manifest =
                EutxoCeremonyManifest.development(
                        "yano-" + identity.network()
                                + "-" + identity.chainId()
                                + "-b16-development",
                        ceremonyDirectory,
                        profile.digest(),
                        profile.circuitId(),
                        key.digestHex());
        writeIdenticalOrNew(
                verificationKeyFile, key.canonicalBytes(), true);
        writeIdenticalOrNew(
                ceremonyManifestFile,
                json.writerWithDefaultPrettyPrinter()
                        .writeValueAsBytes(manifest),
                false);
    }

    private ProjectIdentity identity() throws IOException {
        JsonNode lock = json.readTree(readBounded(
                project.resolve(LOCK_FILE), "app-chain lock"));
        String network = requiredText(lock, "network");
        String recipe = requiredText(lock, "recipe");
        List<String> capabilities =
                stringList(lock.path("selectedCapabilities"));
        if (!recipe.startsWith("eutxo-zeroj-preview:")
                || !capabilities.contains(
                "settlement:zeroj-validity")) {
            throw new IllegalArgumentException(
                    "project does not select eutxo-zeroj-preview");
        }
        Map<String, String> consensus = json.convertValue(
                lock.path("consensusValues"),
                new TypeReference<Map<String, String>>() {
                });
        String chainId = requiredConsensus(
                consensus, "chain-id");
        String authorization = requiredConsensus(
                consensus,
                "machines.eutxo.validity.authorization-profile");
        boolean trusted = Boolean.parseBoolean(requiredConsensus(
                consensus,
                "machines.eutxo.validity."
                        + "authorization-trusted-prover-required"));
        String funds = requiredConsensus(
                consensus, "machines.eutxo.validity.funds-policy");
        return new ProjectIdentity(
                requiredText(lock, "blueprintDigest"),
                network,
                recipe,
                chainId,
                authorization,
                trusted,
                funds,
                stringList(lock.path("acknowledgements")));
    }

    private static String requiredConsensus(
            Map<String, String> values,
            String suffix
    ) {
        String ending = "." + suffix;
        return values.entrySet().stream()
                .filter(entry -> entry.getKey().endsWith(ending))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "project lock is missing " + suffix));
    }

    private static void requireLifecycleNetwork(
            ProjectIdentity identity
    ) {
        if ("mainnet".equals(identity.network())) {
            throw new IllegalArgumentException(
                    "EUTxO ZeroJ development lifecycle rejects mainnet");
        }
        if (!Set.of("devnet", "preview", "preprod")
                .contains(identity.network())) {
            throw new IllegalArgumentException(
                    "unsupported lifecycle network");
        }
        if (Set.of("preview", "preprod").contains(identity.network())
                && !identity.acknowledgements().contains(
                UNSAFE_TESTNET_ACKNOWLEDGEMENT)) {
            throw new IllegalArgumentException(
                    identity.network()
                            + " requires the durable acknowledgement "
                            + UNSAFE_TESTNET_ACKNOWLEDGEMENT);
        }
        if (!identity.trustedProverRequired()
                || !"disposable-test-funds-only".equals(
                identity.fundsPolicy())) {
            throw new IllegalArgumentException(
                    "development lifecycle trust policy is not pinned");
        }
    }

    private Map<String, Object> contractPlan(
            ProjectIdentity identity
    ) {
        Map<String, Object> plan = new LinkedHashMap<>();
        plan.put("schemaVersion",
                "yano-eutxo-validity-contract-plan-v1");
        plan.put("network", identity.network());
        plan.put("chainId", identity.chainId());
        plan.put("authorizationProfile",
                identity.authorizationProfile());
        plan.put("trustedProverRequired", true);
        plan.put("fundsPolicy", identity.fundsPolicy());
        plan.put("deploymentStatus", "PLANNED_NOT_SUBMITTED");
        plan.put("validators", List.of(
                validator("deposit-staging",
                        "com.bloxbean.cardano.yano.appchain.eutxo.bridge.onchain.DepositStagingValidator"),
                validator("validity-root",
                        "com.bloxbean.cardano.yano.appchain.eutxo.zk.onchain.EutxoValidityRootValidator"),
                validator("batch-data",
                        "com.bloxbean.cardano.yano.appchain.eutxo.zk.onchain.EutxoValidityRootValidator#data-availability"),
                validator("proof-withdrawal-vault",
                        "com.bloxbean.cardano.yano.appchain.eutxo.zk.onchain.EutxoProofWithdrawalVaultValidator")));
        plan.put("requiredOperatorInputs", List.of(
                "funded-testnet-cardano-key-reference",
                "collateral-outpoint",
                "thread-token-seed-outpoint",
                "change-address"));
        return Map.copyOf(plan);
    }

    private static Map<String, String> validator(
            String role,
            String implementation
    ) {
        return Map.of(
                "role", role,
                "implementation", implementation,
                "sourceIdentity", sha256(
                        ("yano:eutxo:validator-plan:v1\n"
                                + implementation)
                                .getBytes(StandardCharsets.UTF_8)));
    }

    private LifecycleState currentState(
            ProjectIdentity identity
    ) throws IOException {
        return Files.isRegularFile(stateFile)
                ? readState(identity)
                : new LifecycleState(
                SCHEMA,
                identity.blueprintDigest(),
                identity.network(),
                identity.chainId(),
                identity.authorizationProfile(),
                true,
                identity.fundsPolicy(),
                EutxoZkBatchProfile.CARDANO_PAYMENT_B16.id(),
                EutxoZkBatchProfile.CARDANO_PAYMENT_B16.digest(),
                "NEW",
                0,
                0,
                Instant.now().toString());
    }

    private LifecycleState readState(
            ProjectIdentity identity
    ) throws IOException {
        if (!Files.isRegularFile(stateFile)) {
            return currentState(identity);
        }
        LifecycleState state = json.readValue(
                readBounded(stateFile, "lifecycle state"),
                LifecycleState.class);
        if (!SCHEMA.equals(state.schemaVersion())
                || !identity.blueprintDigest().equals(
                state.blueprintDigest())
                || !identity.network().equals(state.network())
                || !identity.chainId().equals(state.chainId())
                || !identity.authorizationProfile().equals(
                state.authorizationProfile())
                || !EutxoZkBatchProfile.CARDANO_PAYMENT_B16
                .digest().equals(state.batchProfileDigest())) {
            throw new IllegalStateException(
                    "retained lifecycle state belongs to another project or profile");
        }
        return state;
    }

    private void writeState(LifecycleState state)
            throws IOException {
        writeAtomic(
                stateFile,
                json.writerWithDefaultPrettyPrinter()
                        .writeValueAsBytes(state),
                false);
    }

    private Result result(
            String status,
            ProjectIdentity identity,
            LifecycleState state,
            Map<String, ?> details
    ) {
        return new Result(
                status,
                identity.network(),
                identity.chainId(),
                identity.authorizationProfile(),
                true,
                identity.fundsPolicy(),
                state.batchProfile(),
                state.batchProfileDigest(),
                Map.copyOf(details));
    }

    private EutxoCeremonyManifest readCeremonyManifest()
            throws IOException {
        if (!Files.isRegularFile(ceremonyManifestFile)) {
            throw new IllegalStateException(
                    "ceremony is not bootstrapped");
        }
        return json.readValue(readBounded(
                        ceremonyManifestFile, "ceremony manifest"),
                EutxoCeremonyManifest.class);
    }

    private EutxoZkBatchVerificationKey readVerificationKey()
            throws IOException {
        if (!Files.isRegularFile(verificationKeyFile)) {
            throw new IllegalStateException(
                    "verification key is not bootstrapped");
        }
        return EutxoZkBatchVerificationKey.decode(
                readBounded(verificationKeyFile,
                        "verification key"));
    }

    private EutxoZkBatchProof readProof(String proofId)
            throws IOException {
        String id = safeDigest(proofId, "proof id");
        Path path = proofsDirectory.resolve(id + ".proof");
        EutxoZkBatchProof proof = EutxoZkBatchProof.decode(
                readBounded(path, "proof"));
        if (!id.equals(proof.digestHex())) {
            throw new IllegalStateException(
                    "proof filename differs from its digest");
        }
        return proof;
    }

    private void createDirectories() throws IOException {
        for (Path directory : List.of(
                root, proofsDirectory, operationsDirectory,
                ceremonyDirectory.getParent())) {
            Files.createDirectories(directory);
        }
        restrict(root, true);
        restrict(ceremonyDirectory.getParent(), true);
    }

    private int proofCount() throws IOException {
        return count(proofsDirectory, ".proof");
    }

    private int operationCount() throws IOException {
        return count(operationsDirectory, ".json");
    }

    private static int count(Path directory, String suffix)
            throws IOException {
        if (!Files.isDirectory(directory)) {
            return 0;
        }
        try (var files = Files.list(directory)) {
            return Math.toIntExact(files.filter(path ->
                    Files.isRegularFile(path)
                            && path.getFileName().toString()
                            .endsWith(suffix)).count());
        }
    }

    private static boolean hasEntries(Path directory)
            throws IOException {
        try (var files = Files.list(directory)) {
            return files.findFirst().isPresent();
        }
    }

    private Path operationPath(String kind, String id) {
        return operationsDirectory.resolve(kind + "-" + id + ".json");
    }

    private Map<String, Object> readMap(Path path) throws IOException {
        return new LinkedHashMap<>(json.readValue(
                readBounded(path, "operation journal"),
                new TypeReference<Map<String, Object>>() {
                }));
    }

    private void writeIdenticalSemanticOrNew(
            Path path,
            Map<String, Object> value
    ) throws IOException {
        if (Files.isRegularFile(path)) {
            Map<String, Object> existing = readMap(path);
            for (String volatileKey : List.of(
                    "createdAt", "submittedAt", "stableAt")) {
                existing.remove(volatileKey);
                value.remove(volatileKey);
            }
            if (!existing.equals(value)) {
                throw new IllegalStateException(
                        "operation id already belongs to different content");
            }
            return;
        }
        writeIdenticalOrNew(
                path,
                json.writerWithDefaultPrettyPrinter()
                        .writeValueAsBytes(value),
                false);
    }

    private void writeIdenticalOrNew(
            Path path,
            byte[] bytes,
            boolean privateFile
    ) throws IOException {
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                    || Files.isSymbolicLink(path)
                    || !MessageDigest.isEqual(
                    Files.readAllBytes(path), bytes)) {
                throw new IllegalStateException(
                        "refusing to overwrite retained lifecycle artifact");
            }
            return;
        }
        writeAtomic(path, bytes, privateFile);
    }

    private static void writeAtomic(
            Path path,
            byte[] bytes,
            boolean privateFile
    ) throws IOException {
        Files.createDirectories(path.getParent());
        Path temporary = Files.createTempFile(
                path.getParent(),
                "." + path.getFileName(),
                ".tmp");
        try {
            Files.write(temporary, bytes);
            if (privateFile) {
                restrict(temporary, false);
            }
            try {
                Files.move(
                        temporary, path,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(
                        temporary, path,
                        StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static byte[] readBounded(Path path, String label)
            throws IOException {
        Path normalized = Objects.requireNonNull(path, label)
                .toAbsolutePath().normalize();
        if (!Files.isRegularFile(
                normalized, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(normalized)) {
            throw new IOException(label + " is not a regular file");
        }
        long size = Files.size(normalized);
        if (size < 1 || size > MAX_INPUT_BYTES) {
            throw new IOException(label + " is outside its size bound");
        }
        return Files.readAllBytes(normalized);
    }

    private static Path safeProject(Path project) {
        Path normalized = Objects.requireNonNull(
                project, "project").toAbsolutePath().normalize();
        if (!Files.isDirectory(normalized)
                || Files.isSymbolicLink(normalized)) {
            throw new IllegalArgumentException(
                    "project must be an existing directory");
        }
        return normalized;
    }

    private static URI txSubmitEndpoint(URI base) {
        Objects.requireNonNull(base, "nodeUrl");
        if (!Set.of("http", "https").contains(base.getScheme())
                || base.getUserInfo() != null
                || base.getFragment() != null
                || base.getQuery() != null) {
            throw new IllegalArgumentException(
                    "node URL must be an HTTP(S) origin or API base");
        }
        String text = base.toString().replaceAll("/+$", "");
        if (!text.endsWith("/api/v1")) {
            text += "/api/v1";
        }
        return URI.create(text + "/tx/submit");
    }

    private static void restrict(Path path, boolean directory) {
        try {
            Files.setPosixFilePermissions(
                    path, directory ? PRIVATE_DIRECTORY : PRIVATE_FILE);
        } catch (UnsupportedOperationException | IOException ignored) {
            // Non-POSIX platforms retain their normal owner/ACL semantics.
        }
    }

    private static String relative(Path path) {
        Path parent = path.getParent();
        return parent == null
                ? path.getFileName().toString()
                : parent.getFileName() + "/"
                + path.getFileName();
    }

    private static String present(Path path) {
        return Files.isRegularFile(path) ? "PRESENT" : "MISSING";
    }

    private static byte[] digestBytes(String value, String label) {
        return HexFormat.of().parseHex(safeDigest(value, label));
    }

    private static String safeDigest(String value, String label) {
        if (value == null || !value.matches("[0-9a-fA-F]{64}")) {
            throw new IllegalArgumentException(
                    label + " must be 32-byte hexadecimal");
        }
        return value.toLowerCase(Locale.ROOT);
    }

    private static String safeId(String value) {
        if (value == null
                || !value.matches("[a-zA-Z0-9][a-zA-Z0-9._-]{0,127}")) {
            throw new IllegalArgumentException(
                    "operation id is invalid");
        }
        return value;
    }

    private static String operationKind(String value) {
        if (!Set.of("deposit", "settlement", "withdrawal", "recovery")
                .contains(value)) {
            throw new IllegalArgumentException(
                    "operation kind must be deposit, settlement, withdrawal, or recovery");
        }
        return value;
    }

    private static String requiredText(JsonNode node, String field) {
        String value = node.path(field).asText("");
        if (value.isBlank()) {
            throw new IllegalArgumentException(
                    "project lock is missing " + field);
        }
        return value;
    }

    private static List<String> stringList(JsonNode array) {
        if (!array.isArray()) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        array.forEach(value -> result.add(value.asText()));
        return List.copyOf(result);
    }

    private static void check(
            List<Check> checks,
            String id,
            boolean passed,
            String detail
    ) {
        checks.add(new Check(id, passed, detail));
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256")
                            .digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(
                    "SHA-256 unavailable", impossible);
        }
    }

    private static String safe(String value) {
        if (value == null || value.isBlank()) {
            return "operation rejected";
        }
        return value.replaceAll("[\\r\\n\\t]", " ")
                .substring(0, Math.min(value.length(), 240));
    }

    record ProjectIdentity(
            String blueprintDigest,
            String network,
            String recipe,
            String chainId,
            String authorizationProfile,
            boolean trustedProverRequired,
            String fundsPolicy,
            List<String> acknowledgements
    ) {
        ProjectIdentity {
            acknowledgements = List.copyOf(acknowledgements);
        }
    }

    public record LifecycleState(
            String schemaVersion,
            String blueprintDigest,
            String network,
            String chainId,
            String authorizationProfile,
            boolean trustedProverRequired,
            String fundsPolicy,
            String batchProfile,
            String batchProfileDigest,
            String stage,
            int proofCount,
            int operationCount,
            String updatedAt
    ) {
        LifecycleState withStage(String value) {
            return new LifecycleState(
                    schemaVersion, blueprintDigest, network, chainId,
                    authorizationProfile, trustedProverRequired,
                    fundsPolicy, batchProfile, batchProfileDigest,
                    value, proofCount, operationCount, updatedAt);
        }

        LifecycleState withProofCount(int value) {
            return new LifecycleState(
                    schemaVersion, blueprintDigest, network, chainId,
                    authorizationProfile, trustedProverRequired,
                    fundsPolicy, batchProfile, batchProfileDigest,
                    stage, value, operationCount, updatedAt);
        }

        LifecycleState withOperationCount(int value) {
            return new LifecycleState(
                    schemaVersion, blueprintDigest, network, chainId,
                    authorizationProfile, trustedProverRequired,
                    fundsPolicy, batchProfile, batchProfileDigest,
                    stage, proofCount, value, updatedAt);
        }

        LifecycleState withUpdatedAt(String value) {
            return new LifecycleState(
                    schemaVersion, blueprintDigest, network, chainId,
                    authorizationProfile, trustedProverRequired,
                    fundsPolicy, batchProfile, batchProfileDigest,
                    stage, proofCount, operationCount, value);
        }
    }

    public record Result(
            String status,
            String network,
            String chainId,
            String authorizationProfile,
            boolean trustedProverRequired,
            String fundsPolicy,
            String batchProfile,
            String batchProfileDigest,
            Map<String, ?> details
    ) {
    }

    public record Check(String id, boolean passed, String detail) {
    }

    public record DoctorReport(
            String status,
            String network,
            String authorizationProfile,
            boolean trustedProverRequired,
            String fundsPolicy,
            List<Check> checks
    ) {
    }
}
