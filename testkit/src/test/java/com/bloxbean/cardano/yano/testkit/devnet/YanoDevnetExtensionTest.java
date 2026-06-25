package com.bloxbean.cardano.yano.testkit.devnet;

import com.bloxbean.cardano.yano.api.ChainQuery;
import com.bloxbean.cardano.yano.api.DevnetControl;
import com.bloxbean.cardano.yano.api.LedgerQuery;
import com.bloxbean.cardano.yano.api.NodeLifecycle;
import com.bloxbean.cardano.yano.api.TxEvaluationGateway;
import com.bloxbean.cardano.yano.api.TxGateway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class YanoDevnetExtensionTest {
    @RegisterExtension
    YanoDevnetExtension yano = YanoDevnetExtension.devnet()
            .withTempRocksDbStorage();

    @Test
    void injectsKitAndHelperObjects(YanoDevnetTestKit kit,
                                    DevnetControl devnet,
                                    NodeLifecycle lifecycle,
                                    ChainQuery chain,
                                    LedgerQuery ledger,
                                    TxGateway txGateway,
                                    TxEvaluationGateway txEvaluationGateway,
                                    YanoQueries queries,
                                    YanoAwait await,
                                    YanoWallets wallets,
                                    YanoFaucet faucet,
                                    YanoSnapshots snapshots,
                                    YanoTime time,
                                    YanoTransactions transactions,
                                    YanoAssertions assertions) {
        assertNotNull(kit);
        assertSame(kit.devnet(), devnet);
        assertSame(kit.lifecycle(), lifecycle);
        assertSame(kit.chain(), chain);
        assertSame(kit.ledger(), ledger);
        assertSame(kit.txGateway(), txGateway);
        assertSame(kit.txEvaluationGateway(), txEvaluationGateway);
        assertSame(kit.queries(), queries);
        assertSame(kit.await(), await);
        assertSame(kit.wallets(), wallets);
        assertSame(kit.faucet(), faucet);
        assertSame(kit.snapshots(), snapshots);
        assertSame(kit.time(), time);
        assertSame(kit.transactions(), transactions);
        assertSame(kit.assertions(), assertions);
    }

    @Test
    void startNodeStartsAndAfterEachClosesStoredKit() {
        TestkitFakes.FakeYano node = new TestkitFakes.FakeYano(TestkitFakes.status(false, false));
        YanoDevnetExtension extension = YanoDevnetExtension
                .managed(() -> YanoDevnetTestKit.from(node))
                .startNode();
        ExtensionContext context = extensionContext();

        extension.beforeEach(context);
        assertTrue(node.lifecycle.isRunning());
        assertEquals(1, node.lifecycle.startCount);

        extension.afterEach(context);
        assertFalse(node.lifecycle.isRunning());
        assertEquals(1, node.lifecycle.stopCount);
    }

    private static ExtensionContext extensionContext() {
        Map<ExtensionContext.Namespace, ExtensionContext.Store> stores = new HashMap<>();
        return (ExtensionContext) Proxy.newProxyInstance(
                YanoDevnetExtensionTest.class.getClassLoader(),
                new Class<?>[]{ExtensionContext.class},
                (proxy, method, args) -> {
                    if ("getStore".equals(method.getName())) {
                        ExtensionContext.Namespace namespace = (ExtensionContext.Namespace) args[0];
                        return stores.computeIfAbsent(namespace, ignored -> new MapStore());
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
    }

    private static final class MapStore implements ExtensionContext.Store {
        private final Map<Object, Object> values = new HashMap<>();

        @Override
        public Object get(Object key) {
            return values.get(key);
        }

        @Override
        public <V> V get(Object key, Class<V> requiredType) {
            Object value = values.get(key);
            return value != null ? requiredType.cast(value) : null;
        }

        @Override
        public <K, V> Object getOrComputeIfAbsent(K key, Function<K, V> defaultCreator) {
            return values.computeIfAbsent(key, ignored -> defaultCreator.apply(key));
        }

        @Override
        public <K, V> V getOrComputeIfAbsent(K key, Function<K, V> defaultCreator, Class<V> requiredType) {
            return requiredType.cast(getOrComputeIfAbsent(key, defaultCreator));
        }

        @Override
        public void put(Object key, Object value) {
            values.put(key, value);
        }

        @Override
        public Object remove(Object key) {
            return values.remove(key);
        }

        @Override
        public <V> V remove(Object key, Class<V> requiredType) {
            Object value = values.remove(key);
            return value != null ? requiredType.cast(value) : null;
        }
    }
}
