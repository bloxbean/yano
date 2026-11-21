package org.yanoproject.runtime.plugins.fixture;

import org.yanoproject.api.plugin.spi.ContributionKindBinding;
import org.yanoproject.api.plugin.spi.FacadeSupport;
import org.yanoproject.api.plugin.spi.PluginCallbackScope;

import java.util.List;
import java.util.Map;

/** Test binding of the {@code fixture-product} descriptor kind, discovered by ServiceLoader. */
public final class FixtureBinding implements ContributionKindBinding<FixtureProvider> {
    public static final String KIND = "fixture-product";

    @Override
    public Class<FixtureProvider> serviceType() {
        return FixtureProvider.class;
    }

    @Override
    public String descriptorOwner() {
        return "org.yanoproject.runtime.fixture";
    }

    @Override
    public String manifestKey() {
        return KIND;
    }

    @Override
    public String selector(FixtureProvider provider) {
        return provider.id();
    }

    @Override
    public FixtureProvider facade(FixtureProvider provider, PluginCallbackScope scope) {
        return new ProviderFacade(provider, scope);
    }

    private record ProviderFacade(FixtureProvider delegate, PluginCallbackScope scope)
            implements FixtureProvider {
        @Override
        public String id() {
            return scope.activation("read fixture provider identity", () -> scope.call(delegate::id));
        }

        @Override
        public FixtureProduct create() {
            return scope.activation("create fixture product", () -> scope.call(() -> scope.product(
                    delegate.create(), product -> new ProductFacade(product, scope))));
        }

        @Override
        public List<FixtureProduct> createAll() {
            return scope.activation("create fixture products", () -> scope.call(() -> scope.products(
                    delegate.createAll(), product -> new ProductFacade(product, scope))));
        }
    }

    private record ProductFacade(FixtureProduct delegate, PluginCallbackScope scope) implements FixtureProduct {
        @Override
        public String id() {
            return scope.activation("identify fixture product", () -> scope.call(delegate::id));
        }

        @Override
        public String call(String input) throws Exception {
            return scope.call(() -> delegate.call(input));
        }

        @Override
        public Map<String, Object> status() {
            return scope.activation("read fixture product status",
                    () -> scope.call(() -> FacadeSupport.snapshotStatus(delegate.status(), scope)));
        }

        @Override
        public void close() {
            scope.runCleanup(delegate::close);
        }
    }
}
