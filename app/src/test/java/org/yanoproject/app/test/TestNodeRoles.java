package org.yanoproject.app.test;

import org.yanoproject.api.ChainQuery;
import org.yanoproject.api.LedgerQuery;
import org.yanoproject.api.NodeLifecycle;

public interface TestNodeRoles extends NodeLifecycle, ChainQuery, LedgerQuery {
}
