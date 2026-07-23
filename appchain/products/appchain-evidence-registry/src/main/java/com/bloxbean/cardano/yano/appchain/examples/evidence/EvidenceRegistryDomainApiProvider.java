package com.bloxbean.cardano.yano.appchain.examples.evidence;

import com.bloxbean.cardano.yano.api.plugin.domain.DomainApi;
import com.bloxbean.cardano.yano.api.plugin.domain.DomainApiContext;
import com.bloxbean.cardano.yano.api.plugin.domain.DomainApiProvider;

import java.util.Objects;

/** Bundle-owned, read-only domain API provider for evidence queries. */
public final class EvidenceRegistryDomainApiProvider implements DomainApiProvider {
    public static final String BUNDLE_ID =
            "com.bloxbean.cardano.yano.appchain.evidence-registry";

    @Override
    public String id() {
        return BUNDLE_ID;
    }

    @Override
    public DomainApi create(DomainApiContext context) {
        return new EvidenceRegistryDomainApi(Objects.requireNonNull(context, "context"));
    }
}
