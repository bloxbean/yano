package com.bloxbean.cardano.yano.appchain.roles;

import com.bloxbean.cardano.yano.api.plugin.domain.DomainApi;
import com.bloxbean.cardano.yano.api.plugin.domain.DomainApiContext;
import com.bloxbean.cardano.yano.api.plugin.domain.DomainApiProvider;

/** Read-only JSON API for role-workflow exact queries. */
public final class RoleWorkflowDomainApiProvider implements DomainApiProvider {
    public static final String ID = "com.bloxbean.cardano.yano.appchain.role-workflow";

    @Override public String id() { return ID; }
    @Override public DomainApi create(DomainApiContext context) {
        return new RoleWorkflowDomainApi(context);
    }
}
