package com.bloxbean.cardano.yano.appchain.showcase;

import com.bloxbean.cardano.yano.api.appchain.AppChainConfig;
import com.bloxbean.cardano.yano.appchain.stdlib.AuthenticatedMapGenesisFactory;
import com.bloxbean.cardano.yano.appchain.stdlib.contracts.AuthenticatedMapContract;
import com.bloxbean.cardano.yano.appchain.stdlib.contracts.AuthenticatedMapSchema;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Builds the release-matched authenticated-map genesis used by the light showcase. */
public final class ShowcaseAuthenticatedMapConfig {
    static final String CHAIN_ID = "authenticated-map-chain";
    static final String VALIDATOR_BUNDLE_ID =
            "com.bloxbean.cardano.yano.appchain.authenticated-map-validators";
    static final String VALIDATOR_PROVIDER_ID = "gs1-gtin-v1";
    static final String VALIDATOR_DESCRIPTOR_ID = "gtin-v1";
    static final String CATALOG_ENTRY = "META-INF/yano-plugin-index-v1.json";
    static final String PREFIX = "yano.app-chain.chains[8].";

    private static final String SHA256_PREFIX = "sha256:";
    private static final int MAX_CATALOG_BYTES = 1_048_576;
    private static final byte[] EMPTY_CBOR_MAP = {(byte) 0xa0};
    private static final ObjectMapper JSON = new ObjectMapper();

    private ShowcaseAuthenticatedMapConfig() {
    }

    public static void main(String[] arguments) {
        try {
            Options options = Options.parse(arguments);
            byte[] closureDigest = catalogArtifactClosure(
                    options.runtimeJar(), VALIDATOR_BUNDLE_ID);
            Map<String, String> settings = settings(
                    options.chainId(), options.members(), options.threshold(), closureDigest);
            settings.forEach((key, value) -> System.out.println(PREFIX + key + "=" + value));
        } catch (IllegalArgumentException | IOException failure) {
            System.err.println("error: " + failure.getMessage());
            System.exit(2);
        }
    }

    static Map<String, String> settings(
            String chainId,
            List<String> members,
            int threshold,
            byte[] validatorArtifactClosure
    ) {
        if (!CHAIN_ID.equals(chainId)) {
            throw new IllegalArgumentException("unexpected authenticated-map showcase chain id");
        }
        List<String> normalizedMembers = normalizedMembers(members);
        if (threshold < 1 || threshold > normalizedMembers.size()) {
            throw new IllegalArgumentException(
                    "authenticated-map threshold must be between 1 and the member count");
        }

        AppChainConfig config = AppChainConfig.builder(chainId)
                .signingKeyHex("00".repeat(32))
                .memberKeysHex(new LinkedHashSet<>(normalizedMembers))
                .proposerKeyHex(normalizedMembers.getFirst())
                .threshold(threshold)
                .blockIntervalMs(1_000)
                .stateMachineId(AuthenticatedMapContract.STATE_MACHINE_ID)
                .pluginSettings(Map.of("membership.mode", "governed"))
                .build();

        AuthenticatedMapContract.ValidatorDescriptor productSchema =
                AuthenticatedMapContract.ValidatorDescriptor.schema(
                        "product-v1", productSchemaDefinition());
        AuthenticatedMapContract.ValidatorDescriptor gtinValidator =
                AuthenticatedMapContract.ValidatorDescriptor.plugin(
                        VALIDATOR_DESCRIPTOR_ID,
                        VALIDATOR_PROVIDER_ID,
                        validatorArtifactClosure,
                        EMPTY_CBOR_MAP);

        List<AuthenticatedMapContract.CollectionDescriptor> collections = List.of(
                collection("attachments", AuthenticatedMapContract.AUTH_OWNER,
                        64, 32_768, AuthenticatedMapContract.VALUE_ENCODING_OPAQUE, ""),
                collection("canonical-events", AuthenticatedMapContract.AUTH_MEMBER,
                        64, 16_384, AuthenticatedMapContract.VALUE_ENCODING_CANONICAL_CBOR, ""),
                collection("gtins", AuthenticatedMapContract.AUTH_OWNER,
                        14, 15, AuthenticatedMapContract.VALUE_ENCODING_CANONICAL_CBOR,
                        VALIDATOR_DESCRIPTOR_ID),
                collection("products", AuthenticatedMapContract.AUTH_OWNER,
                        64, 4_096, AuthenticatedMapContract.VALUE_ENCODING_CANONICAL_CBOR,
                        productSchema.id()));

        AuthenticatedMapContract.Genesis genesis = AuthenticatedMapGenesisFactory.mpf(
                config,
                new byte[32],
                32,
                AppChainConfig.DEFAULT_MAX_MESSAGE_BYTES,
                collections,
                List.of(gtinValidator, productSchema),
                List.of());
        return Collections.unmodifiableMap(
                new TreeMap<>(AuthenticatedMapGenesisFactory.settings(genesis)));
    }

    static byte[] catalogArtifactClosure(Path runtimeJar, String bundleId) throws IOException {
        try (ZipFile archive = new ZipFile(runtimeJar.toFile())) {
            ZipEntry entry = archive.getEntry(CATALOG_ENTRY);
            if (entry == null || entry.isDirectory() || entry.getSize() < 1
                    || entry.getSize() > MAX_CATALOG_BYTES) {
                throw new IllegalArgumentException(
                        "runtime JAR has no bounded authoritative plugin catalog");
            }
            byte[] encoded;
            try (InputStream input = archive.getInputStream(entry)) {
                encoded = input.readNBytes(MAX_CATALOG_BYTES + 1);
            }
            if (encoded.length > MAX_CATALOG_BYTES) {
                throw new IllegalArgumentException("runtime plugin catalog exceeds its bound");
            }
            return catalogArtifactClosure(encoded, bundleId);
        }
    }

    static byte[] catalogArtifactClosure(byte[] encodedCatalog, String bundleId) {
        try {
            JsonNode root = JSON.readTree(encodedCatalog);
            if (root == null || root.path("schemaVersion").asInt(-1) != 1
                    || !root.path("bundles").isArray()) {
                throw new IllegalArgumentException("runtime plugin catalog is malformed");
            }
            List<JsonNode> matches = new ArrayList<>();
            for (JsonNode bundle : root.path("bundles")) {
                if (bundleId.equals(bundle.path("manifest").path("id").asText())) {
                    matches.add(bundle);
                }
            }
            if (matches.size() != 1) {
                throw new IllegalArgumentException(
                        "runtime plugin catalog must contain exactly one " + bundleId);
            }
            JsonNode selected = matches.getFirst();
            if (!"ARTIFACT_CLOSURE".equals(selected.path("digestMode").asText())) {
                throw new IllegalArgumentException(
                        "authenticated-map validator is not backed by ARTIFACT_CLOSURE evidence");
            }
            String digest = selected.path("digest").asText();
            if (!digest.matches("sha256:[0-9a-f]{64}")) {
                throw new IllegalArgumentException(
                        "authenticated-map validator catalog digest is malformed");
            }
            return HexFormat.of().parseHex(digest.substring(SHA256_PREFIX.length()));
        } catch (IOException malformed) {
            throw new IllegalArgumentException("runtime plugin catalog is malformed", malformed);
        }
    }

    private static AuthenticatedMapContract.CollectionDescriptor collection(
            String id,
            int authorization,
            int maximumKeyBytes,
            int maximumValueBytes,
            int valueEncoding,
            String validatorId
    ) {
        return new AuthenticatedMapContract.CollectionDescriptor(
                id, authorization, false, maximumKeyBytes, maximumValueBytes,
                valueEncoding, validatorId);
    }

    private static byte[] productSchemaDefinition() {
        AuthenticatedMapSchema.MapNode product = new AuthenticatedMapSchema.MapNode(List.of(
                new AuthenticatedMapSchema.MapField("sku", true,
                        new AuthenticatedMapSchema.TextNode(1, 32, null)),
                new AuthenticatedMapSchema.MapField("quantity", true,
                        new AuthenticatedMapSchema.IntegerNode(
                                AuthenticatedMapSchema.INTEGER_UINT,
                                BigInteger.ZERO,
                                BigInteger.valueOf(1_000_000))),
                new AuthenticatedMapSchema.MapField("status", true,
                        new AuthenticatedMapSchema.ChoiceNode(List.of(
                                AuthenticatedMapSchema.TextNode.literal("active"),
                                AuthenticatedMapSchema.TextNode.literal("held"),
                                AuthenticatedMapSchema.TextNode.literal("retired")))),
                new AuthenticatedMapSchema.MapField("note", false,
                        new AuthenticatedMapSchema.TextNode(0, 256, null))));
        return AuthenticatedMapSchema.of(product).definition();
    }

    private static List<String> normalizedMembers(List<String> members) {
        if (members == null || members.isEmpty() || members.size() > AppChainConfig.MAX_MEMBERS) {
            throw new IllegalArgumentException("authenticated-map members must contain 1-32 keys");
        }
        Set<String> unique = new LinkedHashSet<>();
        for (String member : members) {
            if (member == null || !member.matches("[0-9a-f]{64}") || !unique.add(member)) {
                throw new IllegalArgumentException(
                        "authenticated-map members must be unique lowercase 32-byte keys");
            }
        }
        return List.copyOf(unique);
    }

    private record Options(Path runtimeJar, String chainId, List<String> members, int threshold) {
        private static Options parse(String[] arguments) {
            Path runtimeJar = null;
            String chainId = CHAIN_ID;
            List<String> members = null;
            Integer threshold = null;
            for (int index = 0; index < arguments.length; index += 2) {
                if (index + 1 >= arguments.length) {
                    throw usage();
                }
                String value = arguments[index + 1];
                switch (arguments[index]) {
                    case "--runtime-jar" -> runtimeJar = Path.of(value);
                    case "--chain-id" -> chainId = value;
                    case "--members" -> members = List.of(value.split(",", -1));
                    case "--threshold" -> {
                        try {
                            threshold = Integer.parseInt(value);
                        } catch (NumberFormatException malformed) {
                            throw usage();
                        }
                    }
                    default -> throw usage();
                }
            }
            if (runtimeJar == null || members == null || threshold == null) {
                throw usage();
            }
            return new Options(runtimeJar, chainId, members, threshold);
        }

        private static IllegalArgumentException usage() {
            return new IllegalArgumentException("usage: ShowcaseAuthenticatedMapConfig "
                    + "--runtime-jar <yano.jar> --chain-id <id> "
                    + "--members <public-key,...> --threshold <n>");
        }
    }
}
