package com.bloxbean.cardano.yano.app.api.history;

import com.bloxbean.cardano.yano.app.e2e.BaseE2ETest;
import com.bloxbean.cardano.yano.app.e2e.DevnetTestProfile;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Devnet actually runs the projection archive, rather than merely starting with it configured.
 *
 * <p>The three existing integration tests submit transactions and never read history, so devnet
 * could - and did - report a healthy archive that installed no contributors and recorded no
 * genesis. YanoDevnetAssembly.DevnetYano forwarded none of the archive surface, and every Yano
 * default it fell through to was a quiet one: empty, false, NONE. Only chainstateRocksAccess
 * threw, which is the only reason anyone noticed.
 *
 * <p>So this asserts the archive is answering, not just enabled. An archive that reports itself
 * available while holding nothing is the failure this is here to catch, and no transaction test
 * can see it.
 */
@QuarkusTest
@TestProfile(DevnetTestProfile.class)
@Tag("integration")
public class DevnetProjectionArchiveIT extends BaseE2ETest {

    @Override
    protected int getAccountBaseIndex() {
        return 96;
    }

    private String get(String path) throws Exception {
        HttpResponse<String> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(baseUrl + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode())
                .as("%s must answer; a projection that failed to initialise used to 500 here", path)
                .isEqualTo(200);
        return response.body();
    }

    @Test
    void theProjectionArchiveIsRunningAndReportsItself() throws Exception {
        String coverage = get("history/coverage");

        assertThat(coverage).contains("\"enabled\":true");
        assertThat(coverage)
                .as("a failed initialisation reports its reason here rather than throwing")
                .doesNotContain("\"error\"");
        assertThat(coverage)
                .as("the identity proves the sink was opened and bound, not merely configured")
                .contains("\"identity\"");
        assertThat(coverage)
                .as("epoch staging must not have failed; it disables every epoch dataset silently")
                .doesNotContain("epochStagingError");
    }

    @Test
    void genesisIsCapturedRatherThanSilentlyEmpty() throws Exception {
        // The precise defect the decorator caused: genesisUtxoProvider fell through to EMPTY, so
        // the archive recorded no genesis distribution and looked entirely healthy doing it.
        //
        // Awaited rather than asserted outright. Genesis is captured when the archive first
        // sees a canonical block, so a devnet two blocks old legitimately has not captured it
        // yet - asserting immediately tests how fast the devnet produced blocks, not whether
        // the provider is wired. The distinction matters: a wired provider captures within
        // seconds, and one that fell through to EMPTY never captures at all, so a bounded wait
        // still fails for the defect while tolerating startup timing.
        String last = awaitCoverage(body -> body.contains("\"genesisCaptured\":true"));

        assertThat(last).contains("\"genesisCaptured\":true");
    }

    /**
     * Poll coverage until {@code condition} holds, returning the last body seen either way.
     *
     * <p>Returning the final body rather than throwing lets the caller assert on it, so a
     * timeout reports the archive state that was actually observed instead of "timed out".
     */
    private String awaitCoverage(java.util.function.Predicate<String> condition) throws Exception {
        String body = "";
        long deadline = System.nanoTime() + java.time.Duration.ofSeconds(60).toNanos();
        while (System.nanoTime() < deadline) {
            body = get("history/coverage");
            if (condition.test(body)) return body;
            Thread.sleep(500);
        }
        return body;
    }

    @Test
    void everyShippedDatasetIsProjected() throws Exception {
        // installProjectionContributor and installEpochArtifactContributor both defaulted to
        // false. A devnet that installed neither would report no datasets here.
        //
        // Read out of the datasets array rather than searched for in the whole document: several
        // of these names appear elsewhere in /status, so a substring match over the raw body
        // would pass on an archive that projects nothing.
        java.util.List<String> projected = datasetsFrom(get("status"));

        assertThat(projected).containsExactlyInAnyOrder(
                "transaction", "utxo_history", "address_transaction", "account_event",
                "reward", "epoch_stake", "ada_pot", "drep_distribution",
                "governance_proposal_status");
    }

    /** The {@code history.datasets} array from a /status body, without pulling in a JSON mapper. */
    private static java.util.List<String> datasetsFrom(String status) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("\"datasets\"\\s*:\\s*\\[(.*?)]", java.util.regex.Pattern.DOTALL)
                .matcher(status);
        assertThat(matcher.find()).as("/status must carry a history.datasets array").isTrue();
        java.util.List<String> names = new java.util.ArrayList<>();
        java.util.regex.Matcher each = java.util.regex.Pattern.compile("\"([a-z_]+)\"")
                .matcher(matcher.group(1));
        while (each.find()) names.add(each.group(1));
        return names;
    }
}
