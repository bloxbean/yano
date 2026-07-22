package com.bloxbean.cardano.yano.appchain.roles;

import com.bloxbean.cardano.yano.api.plugin.domain.DomainApi;
import com.bloxbean.cardano.yano.api.plugin.domain.DomainApiContext;
import com.bloxbean.cardano.yano.api.plugin.domain.DomainApiProvider;

/** Read-only JSON API for the stock role-approvals profile. */
public final class RoleApprovalsDomainApiProvider implements DomainApiProvider {
    public static final String ID = "com.bloxbean.cardano.yano.appchain.role-workflow";

    @Override public String id() { return ID; }
    @Override public DomainApi create(DomainApiContext context) {
        return new RoleApprovalsDomainApi(context);
    }
}
