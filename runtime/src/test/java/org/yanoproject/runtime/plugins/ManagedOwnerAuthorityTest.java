package org.yanoproject.runtime.plugins;

import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.yanoproject.runtime.plugins.PluginContextFacades.CURRENT_GENERATION;

/**
 * Managed PluginContext callbacks (event listeners and filters, storage filters, scheduled tasks) all run
 * through {@link PluginContextFacades.ManagedCallbackResources}; each suspends the catalog owner frame and
 * restores it, also after a failure.
 */
class ManagedOwnerAuthorityTest {
    private static final String OWNER = "com.example.owner";

    @Test
    void managedCallbacksRunWithoutOwnerAuthorityAndRestoreItAfterFailures() {
        var tracker = new PluginSpiFacades.CallbackTracker();
        var managed = new PluginContextFacades.ManagedCallbackResources(completion -> { }, tracker);
        AtomicReference<Optional<String>> seen = new AtomicReference<>();
        var failure = new IllegalStateException("listener failed");

        tracker.callAsOwner(OWNER, () -> {
            managed.runRequired(CURRENT_GENERATION, () -> seen.set(tracker.owner()));
            assertThat(seen.get()).isEmpty();
            assertThat(tracker.owner()).contains(OWNER);

            assertThatThrownBy(() -> managed.runOrSkip(CURRENT_GENERATION, () -> {
                seen.set(tracker.owner());
                throw failure;
            })).isSameAs(failure);
            assertThat(seen.get()).isEmpty();
            assertThat(tracker.owner()).contains(OWNER);

            assertThat(managed.callOrElse(CURRENT_GENERATION, () -> tracker.owner().isEmpty(), false)).isTrue();
            // A managed callback nested in a managed callback stays without authority, and the frame survives both.
            managed.runRequired(CURRENT_GENERATION, () -> managed.runRequired(CURRENT_GENERATION,
                    () -> seen.set(tracker.owner())));
            assertThat(seen.get()).isEmpty();
            assertThat(tracker.owner()).contains(OWNER);
            return null;
        });
        assertThat(tracker.owner()).isEmpty();
    }
}
