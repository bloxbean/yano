package com.bloxbean.cardano.yano.appchain.eutxo.zk.indexer;

import com.bloxbean.cardano.yano.appchain.eutxo.indexer.EutxoValidityBatchRecord;
import com.bloxbean.cardano.yano.appchain.eutxo.indexer.EutxoValidityIndexSource;
import com.bloxbean.cardano.yano.appchain.eutxo.indexer.EutxoValidityIndexSourceProvider;
import com.bloxbean.cardano.yano.appchain.eutxo.zk.contracts.EutxoZkBatchProof;
import com.bloxbean.cardano.yano.appchain.eutxo.zk.contracts.EutxoZkBatchVerificationKey;
import com.bloxbean.cardano.yano.appchain.eutxo.zk.zeroj.EutxoJubjubBatchDevelopmentSetup;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** ZeroJ artifact adapter. The indexer sees only neutral public metadata. */
public final class ZerojValidityIndexSourceProvider
        implements EutxoValidityIndexSourceProvider {
    private static final int MAX_ARTIFACT_BYTES = 2 * 1024 * 1024;

    @Override
    public String id() {
        return "zeroj";
    }

    @Override
    public Optional<EutxoValidityIndexSource> open(
            Path lifecycleRoot,
            String chainId,
            String network
    ) {
        Path root = Objects.requireNonNull(
                lifecycleRoot, "lifecycleRoot").toAbsolutePath().normalize();
        return Optional.of(new ZerojSource(
                root, chainId, network, new ObjectMapper()));
    }

    private record ZerojSource(
            Path root,
            String chainId,
            String network,
            ObjectMapper json
    ) implements EutxoValidityIndexSource {
        @Override
        public List<EutxoValidityBatchRecord> batches() {
            if (!Files.isRegularFile(
                    root.resolve("state.json"),
                    LinkOption.NOFOLLOW_LINKS)) {
                return List.of();
            }
            requireIdentity();
            try {
                Path proofs = root.resolve("proofs");
                if (!Files.isDirectory(
                        proofs, LinkOption.NOFOLLOW_LINKS)
                        || isEmpty(proofs)) {
                    return List.of();
                }
                EutxoZkBatchVerificationKey key =
                        EutxoZkBatchVerificationKey.decode(read(
                                root.resolve(
                                        "ceremony/verification-key.bin")));
                List<Map<String, Object>> operations = operations();
                List<EutxoValidityBatchRecord> result = new ArrayList<>();
                try (var paths = Files.list(proofs)) {
                    for (Path path : paths.sorted().toList()) {
                        if (!Files.isRegularFile(
                                path, LinkOption.NOFOLLOW_LINKS)
                                || !path.getFileName().toString()
                                .matches("[0-9a-f]{64}\\.proof")) {
                            continue;
                        }
                        EutxoZkBatchProof proof =
                                EutxoZkBatchProof.decode(read(path));
                        if (!path.getFileName().toString()
                                .equals(proof.digestHex() + ".proof")
                                || !proof.verificationKeyDigest()
                                .equals(key.digestHex())
                                || !EutxoJubjubBatchDevelopmentSetup.verify(
                                proof, key)) {
                            throw new IllegalStateException(
                                    "validity proof artifact failed identity or verification");
                        }
                        Map<String, Object> settlement =
                                settlement(operations, proof.digestHex());
                        var inputs = proof.settlementInputs();
                        result.add(new EutxoValidityBatchRecord(
                                proof.digestHex(),
                                "zeroj",
                                "groth16",
                                proof.batchProfileId(),
                                proof.batchProfileDigest(),
                                proof.transactionIds(),
                                scalar(inputs.previousRoot()),
                                scalar(inputs.nextRoot()),
                                scalar(inputs.batchDataCommitment()),
                                "COMMITMENT_ONLY",
                                proof.digestHex(),
                                proof.verificationKeyDigest(),
                                "VERIFIED",
                                Objects.toString(
                                        settlement.getOrDefault(
                                                "status", "NOT_SUBMITTED")),
                                Objects.toString(
                                        settlement.getOrDefault(
                                                "transactionId", "")),
                                number(settlement.get("stableSlot")),
                                Objects.toString(
                                        settlement.getOrDefault(
                                                "stableBlockHash", ""))));
                    }
                }
                result.sort(Comparator.comparing(
                        EutxoValidityBatchRecord::batchId).reversed());
                return List.copyOf(result);
            } catch (IOException failure) {
                throw new IllegalStateException(
                        "validity lifecycle artifacts are unavailable",
                        failure);
            }
        }

        private static boolean isEmpty(Path directory) throws IOException {
            try (var paths = Files.list(directory)) {
                return paths.noneMatch(path -> Files.isRegularFile(
                        path, LinkOption.NOFOLLOW_LINKS)
                        && path.getFileName().toString()
                        .matches("[0-9a-f]{64}\\.proof"));
            }
        }

        private void requireIdentity() {
            try {
                Map<String, Object> state = map(root.resolve("state.json"));
                if (!chainId.equals(state.get("chainId"))
                        || !network.equals(state.get("network"))
                        || !Objects.toString(
                        state.get("schemaVersion"), "")
                        .startsWith("yano-eutxo-validity-lifecycle-")) {
                    throw new IllegalStateException(
                            "validity lifecycle belongs to another chain or network");
                }
            } catch (IOException failure) {
                throw new IllegalStateException(
                        "validity lifecycle identity is unavailable",
                        failure);
            }
        }

        private List<Map<String, Object>> operations() throws IOException {
            Path directory = root.resolve("operations");
            if (!Files.isDirectory(
                    directory, LinkOption.NOFOLLOW_LINKS)) {
                return List.of();
            }
            List<Map<String, Object>> result = new ArrayList<>();
            try (var paths = Files.list(directory)) {
                for (Path path : paths.sorted().toList()) {
                    if (Files.isRegularFile(
                            path, LinkOption.NOFOLLOW_LINKS)
                            && path.getFileName().toString()
                            .endsWith(".json")) {
                        result.add(map(path));
                    }
                }
            }
            return List.copyOf(result);
        }

        private Map<String, Object> map(Path path) throws IOException {
            return json.readValue(
                    read(path),
                    new TypeReference<Map<String, Object>>() {
                    });
        }

        private static Map<String, Object> settlement(
                List<Map<String, Object>> operations,
                String proofId
        ) {
            return operations.stream()
                    .filter(operation ->
                            "settlement".equals(operation.get("kind"))
                                    && proofId.equals(
                                    operation.get("proofId")))
                    .max(Comparator.comparingInt(operation ->
                            rank(Objects.toString(
                                    operation.get("status"), ""))))
                    .orElse(Map.of());
        }

        private static int rank(String status) {
            return switch (status) {
                case "STABLE" -> 3;
                case "SUBMITTED" -> 2;
                case "PREPARED" -> 1;
                default -> 0;
            };
        }

        private static long number(Object value) {
            return value instanceof Number number
                    ? Math.max(0, number.longValue()) : 0;
        }

        private static String scalar(BigInteger value) {
            if (value.signum() < 0 || value.bitLength() > 256) {
                throw new IllegalArgumentException(
                        "validity public input is not a 32-byte scalar");
            }
            return String.format("%064x", value);
        }

        private static byte[] read(Path path) throws IOException {
            if (!Files.isRegularFile(
                    path, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("required lifecycle artifact is absent");
            }
            long size = Files.size(path);
            if (size < 1 || size > MAX_ARTIFACT_BYTES) {
                throw new IOException(
                        "lifecycle artifact exceeds the public bound");
            }
            return Files.readAllBytes(path);
        }
    }
}
