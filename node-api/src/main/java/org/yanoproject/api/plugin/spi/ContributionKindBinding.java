package org.yanoproject.api.plugin.spi;

/**
 * Host-side binding of one contribution kind to its ServiceLoader SPI.
 *
 * <p>Bindings of descriptor-defined kinds are discovered with
 * {@link java.util.ServiceLoader} on the host class loader before the plugin
 * catalog is built. Each descriptor kind must have exactly one binding whose
 * {@link #manifestKey()} equals the kind key and whose {@link #serviceType()}
 * name equals the descriptor's service interface; otherwise catalog
 * construction fails. The host calls {@link #selector} and {@link #facade}
 * under callback admission with the plugin class loader as TCCL.</p>
 *
 * @param <P> provider SPI type
 */
public interface ContributionKindBinding<P> {
    /**
     * Returns the ServiceLoader SPI of this kind.
     *
     * @return provider interface
     */
    Class<P> serviceType();

    /**
     * Returns the manifest key of the bound kind.
     *
     * @return contribution kind key
     */
    String manifestKey();

    /**
     * Reads the provider's semantic selector, which must equal the manifest contribution name.
     *
     * @param provider raw plugin provider
     * @return selector advertised by the provider
     */
    String selector(P provider);

    /**
     * Wraps a raw provider in the facade the host publishes to consumers.
     *
     * @param provider raw plugin provider
     * @param scope admission, TCCL, activation and product-reservation boundary of this provider
     * @return provider facade that routes every plugin callback through {@code scope}
     */
    P facade(P provider, PluginCallbackScope scope);
}
