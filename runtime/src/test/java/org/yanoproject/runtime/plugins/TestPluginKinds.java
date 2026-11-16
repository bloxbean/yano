package org.yanoproject.runtime.plugins;

import org.yanoproject.catalog.ContributionKind;
import org.yanoproject.runtime.plugins.fixture.FixtureBinding;

/** The runtime test class path's kinds: built-ins plus the test descriptor kind {@code fixture-product}. */
final class TestPluginKinds {
    static final PluginKinds KINDS = PluginKinds.load(TestPluginKinds.class.getClassLoader());
    static final ContributionKind FIXTURE = KINDS.registry().fromManifestKey(FixtureBinding.KIND);

    private TestPluginKinds() {
    }
}
