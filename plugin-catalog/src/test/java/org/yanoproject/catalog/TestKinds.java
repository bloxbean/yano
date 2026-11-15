package org.yanoproject.catalog;

import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Path;
import java.util.List;

/** The test descriptor kinds, trusted from the test-resources host artifact. */
final class TestKinds {
    static final String DESCRIPTOR =
            ContributionKindRegistry.RESOURCE_DIRECTORY + "org.yanoproject.catalog.fixture.json";
    static final Path HOST_ARTIFACT = hostArtifact();
    static final ContributionKindRegistry REGISTRY = registry();
    /** Legacy-capable CONSENSUS kind; names must not contain '.' or ':'; reserves "system". */
    static final ContributionKind SINK = REGISTRY.fromManifestKey("fixture-sink");
    /** Manifest-only bundle-owned singleton kind with tracked operations. */
    static final ContributionKind DOMAIN = REGISTRY.fromManifestKey("fixture-domain");

    private TestKinds() {
    }

    private static Path hostArtifact() {
        URL descriptor = TestKinds.class.getClassLoader().getResource(DESCRIPTOR);
        try {
            return Path.of(descriptor.toURI()).getParent().getParent().getParent().getParent();
        } catch (URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }

    private static ContributionKindRegistry registry() {
        try {
            return ContributionKindRegistry.fromHostArtifacts(List.of(HOST_ARTIFACT));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
