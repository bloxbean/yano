package com.bloxbean.cardano.yano.appchain.devtools;

import com.bloxbean.cardano.yano.appchain.config.AppChainMetadataDescriptor;
import com.bloxbean.cardano.yano.appchain.config.TemplateContract;
import com.bloxbean.cardano.yano.appchain.config.ValidationCoverage;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Strict data-only loader for contracts and component metadata; it never loads plugin classes. */
final class AppChainDescriptorLoader {
    private static final long MAX_DESCRIPTOR_BYTES = 1024 * 1024;
    static final String BUILT_IN_CLUSTER_CONTRACT =
            "/appchain-dx/v1/appchain-cluster-template-contract.json";
    static final String BUILT_IN_FIRST_PARTY_METADATA =
            "/appchain-dx/v1alpha1/appchain-first-party-metadata.json";

    private final ObjectMapper json = strict(new ObjectMapper(
            JsonFactory.builder()
                    .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                    .build()));
    private final ObjectMapper yaml = strict(new ObjectMapper(YAMLFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .build()));

    TemplateContract loadContract(String location) throws IOException {
        if ("builtin:cluster".equals(location)) {
            try (InputStream input = AppChainDescriptorLoader.class
                    .getResourceAsStream(BUILT_IN_CLUSTER_CONTRACT)) {
                if (input == null) {
                    throw new IOException("built-in cluster template contract is missing");
                }
                return json.readValue(readBounded(input), TemplateContract.class);
            }
        }
        Path path = Path.of(location);
        return mapper(path).readValue(readFile(path), TemplateContract.class);
    }

    AppChainMetadataDescriptor loadMetadata(Path artifact) throws IOException {
        AppChainMetadataDescriptor descriptor;
        if (Files.isDirectory(artifact)) {
            Path descriptorFile = artifact.resolve(AppChainMetadataDescriptor.RESOURCE_PATH);
            descriptor = json.readValue(readFile(descriptorFile), AppChainMetadataDescriptor.class);
        } else if (isArchive(artifact)) {
            try (ZipFile archive = new ZipFile(artifact.toFile())) {
                ZipEntry entry = archive.getEntry(AppChainMetadataDescriptor.RESOURCE_PATH);
                if (entry == null || entry.isDirectory()) {
                    throw new IOException("artifact has no "
                            + AppChainMetadataDescriptor.RESOURCE_PATH);
                }
                if (entry.getSize() > MAX_DESCRIPTOR_BYTES) {
                    throw new IOException("metadata descriptor exceeds size limit");
                }
                try (InputStream input = archive.getInputStream(entry)) {
                    descriptor = json.readValue(
                            readBounded(input), AppChainMetadataDescriptor.class);
                }
            }
        } else {
            descriptor = mapper(artifact).readValue(
                    readFile(artifact), AppChainMetadataDescriptor.class);
        }
        validateUntrustedMetadata(descriptor);
        return descriptor;
    }

    AppChainMetadataDescriptor loadMetadata(byte[] descriptorBytes) throws IOException {
        if (descriptorBytes == null || descriptorBytes.length == 0
                || descriptorBytes.length > MAX_DESCRIPTOR_BYTES) {
            throw new IOException("configuration metadata is empty or exceeds size limit");
        }
        AppChainMetadataDescriptor descriptor = json.readValue(
                descriptorBytes, AppChainMetadataDescriptor.class);
        validateUntrustedMetadata(descriptor);
        return descriptor;
    }

    List<AppChainMetadataDescriptor> loadBuiltInMetadata() throws IOException {
        try (InputStream input = AppChainDescriptorLoader.class
                .getResourceAsStream(BUILT_IN_FIRST_PARTY_METADATA)) {
            if (input == null) throw new IOException("built-in first-party metadata is missing");
            return List.copyOf(json.readValue(readBounded(input),
                    new TypeReference<List<AppChainMetadataDescriptor>>() { }));
        }
    }

    private static boolean isArchive(Path artifact) {
        String name = artifact.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
        return name.endsWith(".jar") || name.endsWith(".zip");
    }

    static void validateUntrustedMetadata(AppChainMetadataDescriptor descriptor) {
        descriptor.properties().stream()
                .filter(property -> property.constraintProvenance().enforceable())
                .findFirst()
                .ifPresent(property -> {
                    throw new IllegalArgumentException("external metadata '" + descriptor.id()
                            + "' cannot claim runtime-verified constraint provenance for "
                            + property.key());
                });
        if (descriptor.properties().stream()
                .anyMatch(property -> property.coverage() == ValidationCoverage.FULL)
                || descriptor.dynamicNamespaces().stream()
                .anyMatch(namespace -> namespace.coverage() == ValidationCoverage.FULL)) {
            throw new IllegalArgumentException("external metadata '" + descriptor.id()
                    + "' cannot claim FULL validation coverage");
        }
    }

    private ObjectMapper mapper(Path path) {
        String name = path.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
        return name.endsWith(".yml") || name.endsWith(".yaml") ? yaml : json;
    }

    private static byte[] readFile(Path path) throws IOException {
        if (!Files.isRegularFile(path)) {
            throw new IOException("descriptor does not exist or is not a regular file");
        }
        if (Files.size(path) > MAX_DESCRIPTOR_BYTES) {
            throw new IOException("descriptor exceeds size limit");
        }
        try (InputStream input = Files.newInputStream(path)) {
            return readBounded(input);
        }
    }

    private static byte[] readBounded(InputStream input) throws IOException {
        byte[] bytes = input.readNBytes((int) MAX_DESCRIPTOR_BYTES + 1);
        if (bytes.length > MAX_DESCRIPTOR_BYTES) {
            throw new IOException("descriptor exceeds size limit");
        }
        return bytes;
    }

    private static ObjectMapper strict(ObjectMapper mapper) {
        return mapper.enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    }
}
