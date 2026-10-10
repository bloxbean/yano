package org.yanoproject.runtime.plugins;

import org.yanoproject.api.plugin.PluginActivationException;

/**
 * A provider activation failure raised by the host for a provider declared in a validated catalog manifest: the
 * registry failing to construct it, or the host's facade wrapping a failed factory or product callback. Its bundle
 * id and contribution kind come from that manifest, never from plugin code; legacy providers without a manifest
 * never get this type, because their identity comes from the plugin.
 *
 * <p>Only this package constructs it, so an application boundary may treat its identity fields as host-supplied
 * provenance. A plugin can still throw an ordinary {@link PluginActivationException} with any field values; those
 * are plugin-controlled and must not be shown as catalog identity. This is provenance, not a sandbox: plugins are
 * trusted in-process code and could defeat it with reflection.</p>
 */
public final class HostPluginActivationException extends PluginActivationException {

    HostPluginActivationException(
            String message,
            String bundleId,
            String contributionKind,
            String selector,
            String providerClass,
            Throwable cause
    ) {
        super(message, bundleId, contributionKind, selector, providerClass, cause);
    }
}
