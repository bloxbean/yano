package org.yanoproject.api.plugin.spi;

import java.util.List;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

/**
 * Callback boundary of one catalog provider, shared by every facade the
 * provider and its products publish.
 *
 * <p>Callback admission and product reservations are shared across all
 * bindings of one plugin catalog, so a runtime seal drains every kind's
 * callbacks and a product instance has exactly one owner.</p>
 */
public interface PluginCallbackScope {
    /** @return owning bundle id */
    String bundleId();

    /** @return provider selector (manifest contribution name) */
    String selector();

    /** @return plugin class loader used as TCCL for callbacks */
    ClassLoader loader();

    /**
     * Runs plugin code under callback admission with {@link #loader()} as TCCL.
     *
     * @param callback plugin callback
     * @param <T> result type
     * @param <X> checked failure type
     * @return callback result
     * @throws X when the callback fails
     */
    <T, X extends Throwable> T call(Callback<T, X> callback) throws X;

    /**
     * Runs plugin code under callback admission with {@link #loader()} as TCCL.
     *
     * @param action plugin callback
     * @param <X> checked failure type
     * @throws X when the callback fails
     */
    <X extends Throwable> void run(Action<X> action) throws X;

    /**
     * Runs terminal product cleanup, admitted after ordinary callbacks are sealed.
     *
     * @param action cleanup callback
     * @param <X> checked failure type
     * @throws X when the cleanup fails
     */
    <X extends Throwable> void runCleanup(Action<X> action) throws X;

    /**
     * Runs one activation step and turns any failure into the host's fixed
     * activation diagnostic naming this provider.
     *
     * @param action short description such as "create sink products"
     * @param callback activation step
     * @param <T> result type
     * @return step result
     */
    <T> T activation(String action, Supplier<T> callback);

    /**
     * Reserves one freshly created product and returns its facade. Call it
     * inside {@link #call} so creation and reservation share one admission.
     *
     * @param value product returned by the plugin, possibly null
     * @param facade facade factory for the product
     * @param <T> product type
     * @return facade, or null when {@code value} is null
     */
    <T> T product(T value, UnaryOperator<T> facade);

    /**
     * Snapshots a factory result through one bounded iteration
     * ({@link FacadeSupport#MAX_FACTORY_PRODUCTS_PER_CALLBACK}), reserves every
     * product and returns their facades. Products that cannot be reserved are
     * closed. Call it inside {@link #call}.
     *
     * @param values products returned by the plugin
     * @param facade facade factory for one product
     * @param <T> product type
     * @return facades in plugin order
     */
    <T> List<T> products(List<T> values, UnaryOperator<T> facade);

    /**
     * Plugin callback that returns a value.
     *
     * @param <T> result type
     * @param <X> checked failure type
     */
    @FunctionalInterface
    interface Callback<T, X extends Throwable> {
        T get() throws X;
    }

    /**
     * Plugin callback without a result.
     *
     * @param <X> checked failure type
     */
    @FunctionalInterface
    interface Action<X extends Throwable> {
        void run() throws X;
    }
}
