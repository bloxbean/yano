package com.bloxbean.cardano.yano.app.test;

import com.bloxbean.cardano.yano.api.ChainQuery;
import com.bloxbean.cardano.yano.api.LedgerQuery;
import com.bloxbean.cardano.yano.api.NodeLifecycle;

public interface TestNodeRoles extends NodeLifecycle, ChainQuery, LedgerQuery {
}
