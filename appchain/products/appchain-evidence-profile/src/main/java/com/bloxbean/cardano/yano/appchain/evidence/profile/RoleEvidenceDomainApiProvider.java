package com.bloxbean.cardano.yano.appchain.evidence.profile;

import com.bloxbean.cardano.yano.api.plugin.domain.DomainApi;
import com.bloxbean.cardano.yano.api.plugin.domain.DomainApiContext;
import com.bloxbean.cardano.yano.api.plugin.domain.DomainApiProvider;

/** Read-only JSON API for the evidence profile's role-workflow exact queries. */
public final class RoleEvidenceDomainApiProvider implements DomainApiProvider {
    public static final String ID = "com.bloxbean.cardano.yano.appchain.evidence-profile";

    @Override public String id() { return ID; }
    @Override public DomainApi create(DomainApiContext context) {
        return new RoleEvidenceDomainApi(context);
    }
}
