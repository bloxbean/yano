package com.bloxbean.cardano.yano.appchain.devtools;

import com.bloxbean.cardano.yano.appchain.config.AppChainPropertyRegistry;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Build entry point for release metadata resources. */
public final class AppChainMetadataExportMain {
    private AppChainMetadataExportMain() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 1) {
            throw new IllegalArgumentException("one output directory is required");
        }
        Path directory = Path.of(args[0]).resolve(AppChainMetadataExporter.RESOURCE_DIRECTORY);
        Files.createDirectories(directory);
        AppChainMetadataExporter exporter = new AppChainMetadataExporter();
        AppChainPropertyRegistry registry = AppChainPropertyRegistry.framework();
        Files.writeString(directory.resolve(AppChainMetadataExporter.RUNTIME_SCHEMA),
                exporter.runtimeSchemaJson(registry), StandardCharsets.UTF_8);
        Files.writeString(directory.resolve(AppChainMetadataExporter.PROPERTY_CATALOG),
                exporter.propertyCatalogJson(registry), StandardCharsets.UTF_8);
    }
}
