package com.bloxbean.cardano.yano.appchain.stdlib;

import com.bloxbean.cardano.yano.api.plugin.domain.DomainApiProvider;
import org.junit.jupiter.api.Test;

import java.util.ServiceLoader;

import static org.assertj.core.api.Assertions.assertThat;

class AuthenticatedMapPluginMetadataTest {
    @Test
    void publishesFirstPartyDomainApiProvider() {
        DomainApiProvider provider = ServiceLoader.load(DomainApiProvider.class).stream()
                .map(ServiceLoader.Provider::get)
                .filter(value -> AuthenticatedMapDomainApiProvider.ID.equals(value.id()))
                .findFirst().orElseThrow();

        assertThat(provider).isInstanceOf(AuthenticatedMapDomainApiProvider.class);
        assertThat(AuthenticatedMapPluginMetadataTest.class.getClassLoader().getResource(
                "META-INF/yano/plugins/com.bloxbean.cardano.yano.appchain.stdlib.json"))
                .isNotNull();
    }
}
