package com.bloxbean.cardano.yano.appchain.history;

import com.bloxbean.cardano.yano.api.appchain.AppStateMachineProvider;
import com.bloxbean.cardano.yano.api.plugin.domain.DomainApiProvider;
import com.bloxbean.cardano.yano.api.plugin.ui.UiExtensionProvider;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ServiceLoader;

import static org.assertj.core.api.Assertions.assertThat;

class CardanoHistoryPluginMetadataTest {
    @Test
    void publishesOneStateMachineProviderAndManifest() throws Exception {
        AppStateMachineProvider provider = ServiceLoader.load(AppStateMachineProvider.class).stream()
                .map(ServiceLoader.Provider::get)
                .filter(value -> CardanoHistoryProduct.STATE_MACHINE_ID.equals(value.id()))
                .findFirst().orElseThrow();
        assertThat(provider).isInstanceOf(CardanoHistoryStateMachineProvider.class);
        assertThat(ServiceLoader.load(DomainApiProvider.class).stream()
                .map(ServiceLoader.Provider::get)
                .filter(value -> CardanoHistoryProduct.BUNDLE_ID.equals(value.id())))
                .hasSize(1);
        assertThat(ServiceLoader.load(UiExtensionProvider.class).stream()
                .map(ServiceLoader.Provider::get)
                .filter(value -> CardanoHistoryProduct.BUNDLE_ID.equals(value.id())))
                .singleElement().isInstanceOf(CardanoHistoryUiExtensionProvider.class);

        String path = "META-INF/yano/plugins/" + CardanoHistoryProduct.BUNDLE_ID + ".json";
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(path)) {
            assertThat(input).isNotNull();
            String manifest = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            assertThat(manifest).contains("\"kind\": \"app-state-machine\"")
                    .contains("\"kind\": \"domain-api\"")
                    .contains("\"kind\": \"ui-extension\"")
                    .contains("\"name\": \"cardano-history\"");
        }
    }
}
