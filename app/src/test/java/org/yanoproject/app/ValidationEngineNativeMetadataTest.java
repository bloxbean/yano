package org.yanoproject.app;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.yanoproject.ledger.rules.LedgerValidationEngineFactory;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ADR-056 Phase 7c: every validation-engine provider the application ships ({@code java}, {@code scalus}, and
 * {@code amaru} in {@code -PwithAmaru=true} builds) is registered for reflective construction in the native image.
 * Quarkus does not register {@code META-INF/services} providers by itself, and a missing registration makes the
 * native {@code ServiceLoader} fail ({@code Provider ... not found}) where the JVM works.
 */
class ValidationEngineNativeMetadataTest {

    private static final String SERVICE = "META-INF/services/" + LedgerValidationEngineFactory.class.getName();

    @Test
    void everyEngineProviderHasANativeReflectionRegistration() throws Exception {
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        Set<String> providers = new LinkedHashSet<>();
        for (URL url : Collections.list(loader.getResources(SERVICE))) {
            for (String line : read(url).lines().toList()) {
                String entry = line.replaceFirst("#.*", "").trim();
                if (!entry.isEmpty()) {
                    providers.add(entry);
                }
            }
        }
        assertThat(providers).contains("org.yanoproject.ledger.rules.conway.JavaJulcEngineFactory",
                "org.yanoproject.ledger.rules.conway.JavaScalusEngineFactory",
                "org.yanoproject.scalusbridge.ScalusEngineFactory");

        Set<String> constructible = new HashSet<>();
        ObjectMapper json = new ObjectMapper();
        for (String module : new String[]{"yano-ledger-rules", "yano-scalus-bridge", "amaru-validator", "yano-app"}) {
            String config = "META-INF/native-image/org.yanoproject/" + module + "/reflect-config.json";
            for (URL url : Collections.list(loader.getResources(config))) {
                for (JsonNode entry : json.readTree(read(url))) {
                    if (entry.path("allDeclaredConstructors").asBoolean(false)) {
                        constructible.add(entry.path("name").asText());
                    }
                }
            }
        }
        assertThat(constructible).as("providers listed in %s", SERVICE).containsAll(providers);
    }

    private static String read(URL url) throws IOException {
        try (InputStream input = url.openStream()) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
