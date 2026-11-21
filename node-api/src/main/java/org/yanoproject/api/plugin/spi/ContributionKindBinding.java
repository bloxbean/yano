package org.yanoproject.api.plugin.spi;

/**
 * Host-side binding of one contribution kind to its ServiceLoader SPI.
 *
 * <p>Bindings of descriptor-defined kinds are discovered with
 * {@link java.util.ServiceLoader} on the host class loader before the plugin
 * catalog is built. Each descriptor kind must have exactly one binding whose
 * {@link #manifestKey()} equals the kind key and whose {@link #serviceType()}
 * name equals the descriptor's service interface; otherwise catalog
 * construction fails. The host loads only the descriptors of the owners its
 * bindings name ({@link #descriptorOwner()}), by resource path, so descriptors
 * need no directory listing and survive merging into one uber-JAR. A descriptor
 * takes effect at runtime only when at least one binding names its owner; index
 * and CLI tooling read every host descriptor. The host calls {@link #selector}
 * and {@link #facade} under callback admission with the plugin class loader as
 * TCCL.</p>
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
     * Returns the owner of the descriptor that declares this kind; the host
     * loads it from {@code META-INF/yano/contribution-kinds/<owner>.json}.
     *
     * @return reverse-DNS descriptor owner
     */
    String descriptorOwner();

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
