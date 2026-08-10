package com.bloxbean.cardano.yano.appchain.history;

import com.bloxbean.cardano.yano.api.appchain.AppStateMachineProvider;
import com.bloxbean.cardano.yano.api.plugin.domain.DomainApiProvider;
import com.bloxbean.cardano.yano.catalog.BundleManifestParser;
import com.bloxbean.cardano.yano.catalog.ContributionKind;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
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
        String path = "META-INF/yano/plugins/" + CardanoHistoryProduct.BUNDLE_ID + ".json";
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(path)) {
            assertThat(input).isNotNull();
            var manifest = new BundleManifestParser().parse(path, input);
            assertThat(manifest.id()).isEqualTo(CardanoHistoryProduct.BUNDLE_ID);
            assertThat(manifest.contributions()).extracting(contribution -> contribution.kind())
                    .containsExactlyInAnyOrder(
                            ContributionKind.APP_STATE_MACHINE,
                            ContributionKind.DOMAIN_API);
            assertThat(manifest.contributions()).extracting(contribution -> contribution.name())
                    .containsExactlyInAnyOrder(
                            CardanoHistoryProduct.STATE_MACHINE_ID,
                            CardanoHistoryProduct.BUNDLE_ID);
        }
    }
}
