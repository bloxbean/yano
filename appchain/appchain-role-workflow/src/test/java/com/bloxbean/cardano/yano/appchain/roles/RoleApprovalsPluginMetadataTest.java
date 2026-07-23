package com.bloxbean.cardano.yano.appchain.roles;

import com.bloxbean.cardano.yano.api.appchain.AppStateMachineProvider;
import com.bloxbean.cardano.yano.api.plugin.domain.DomainApiProvider;
import com.bloxbean.cardano.yano.catalog.BundleManifestParser;
import com.bloxbean.cardano.yano.catalog.ContributionKind;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.ServiceLoader;

import static org.assertj.core.api.Assertions.assertThat;

class RoleApprovalsPluginMetadataTest {
    private static final String BUNDLE_ID =
            "com.bloxbean.cardano.yano.appchain.role-workflow";
    private static final String MANIFEST =
            "META-INF/yano/plugins/" + BUNDLE_ID + ".json";

    @Test
    void servicesAndManifestExposeOnlyTheGenericRoleProduct() throws Exception {
        AppStateMachineProvider machine = ServiceLoader.load(AppStateMachineProvider.class)
                .stream().map(ServiceLoader.Provider::get)
                .filter(provider -> RoleApprovalsStateMachineProvider.ID.equals(provider.id()))
                .findFirst().orElseThrow();
        DomainApiProvider domain = ServiceLoader.load(DomainApiProvider.class)
                .stream().map(ServiceLoader.Provider::get)
                .filter(provider -> BUNDLE_ID.equals(provider.id()))
                .findFirst().orElseThrow();
        assertThat(machine).isInstanceOf(RoleApprovalsStateMachineProvider.class);
        assertThat(domain).isInstanceOf(RoleApprovalsDomainApiProvider.class);

        try (InputStream input = getClass().getClassLoader().getResourceAsStream(MANIFEST)) {
            assertThat(input).isNotNull();
            var manifest = new BundleManifestParser().parse(MANIFEST, input);
            assertThat(manifest.id()).isEqualTo(BUNDLE_ID);
            assertThat(manifest.contributions()).hasSize(2);
            assertThat(manifest.contributions()).anySatisfy(contribution -> {
                assertThat(contribution.kind()).isEqualTo(ContributionKind.APP_STATE_MACHINE);
                assertThat(contribution.name())
                        .isEqualTo(RoleApprovalsStateMachineProvider.ID);
            }).anySatisfy(contribution -> {
                assertThat(contribution.kind()).isEqualTo(ContributionKind.DOMAIN_API);
                assertThat(contribution.name()).isEqualTo(BUNDLE_ID);
            });
        }
    }
}
