package com.bloxbean.cardano.yano.app;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ADR app-layer/008.1 I1.9: the app-chain status page is served as a static
 * resource next to the L1 status page, and the two cross-link.
 */
@QuarkusTest
@TestProfile(NoAutoStartTestProfile.class)
class AppChainStatusPageTest {

    @Test
    void appChainStatusPage_isServed() {
        String page = given()
                .when().get("/ui/app-chain/index.html")
                .then()
                .statusCode(200)
                .extract().asString();

        assertTrue(page.contains("Yano · App Chain"));
        for (String panel : new String[]{"chainSelect", "heroTip", "anchorList",
                "effectExecutorsList", "profileModeBadge", "profileGovernanceList",
                "blocksBody", "messageDialog", "messageBodyPreview", "messageRawHex"}) {
            assertTrue(page.contains("id=\"" + panel + "\""), panel);
        }
        assertTrue(page.contains("href=\"../status/\""));

        String keyHandler = between(page, "$('keyBtn').addEventListener",
                "$('themeToggle').addEventListener");
        assertBefore(keyHandler, "stopStream();", "refresh().then(restartStream);");
        assertFalse(keyHandler.contains("refresh(); restartStream();"));

        String chainHandler = between(page, "chainSelect.addEventListener",
                "function chainBase()");
        assertBefore(chainHandler, "stopStream();", "resetHistory();");
        assertBefore(chainHandler, "clearFeed();", "refresh().then(restartStream);");
        assertFalse(chainHandler.contains("setHtml('feed'"));

        String restart = between(page, "function restartStream()", "async function streamLoop");
        assertTrue(restart.contains("hist.lastTip"));
        assertTrue(restart.contains("initialHeight"));

        String loop = between(page, "async function streamLoop", "function handleSseEvent");
        assertTrue(loop.contains("lastStreamHeight"));
        assertTrue(loop.contains("initialHeight"));
        assertTrue(loop.contains("generation !== streamGeneration"));
        assertFalse(loop.contains("hist.lastTip"));

        String handler = between(page, "function handleSseEvent",
                "document.addEventListener('visibilitychange'");
        assertTrue(handler.contains("event === 'heartbeat'"));
        assertTrue(handler.contains("lastStreamHeight"));
        assertTrue(handler.contains("msg.chainId || selectedChain"));
        assertTrue(handler.contains("openMessageInspector(msg, position)"));
        assertTrue(handler.contains("document.createElement('button')"));
        assertTrue(handler.contains("row.setAttribute('aria-label'"));
        assertFalse(handler.contains("row.innerHTML"));
        String heartbeat = between(handler, "if (event === 'heartbeat')",
                "if (event !== 'app-message'");
        assertFalse(heartbeat.contains("lastStreamHeight"),
                "heartbeats report the server tip, not the subscriber cursor");

        // Script-anchor members report independently observed confirmations;
        // the leader-only submission counter must not be presented as a
        // cluster-wide zero on follower pages.
        assertTrue(page.contains("anchor.leader === false"));
        assertTrue(page.contains("Anchors Observed"));
        assertTrue(page.contains("anchor.observedAnchorCount"));
        assertTrue(page.contains("effectRuntime.executorOperations"));
        assertTrue(page.contains("executor.sampleState"));
        assertTrue(page.contains("executor.failureCode"));
        assertTrue(page.contains("stateMachineStatus"));

        String inspector = between(page, "function hexPreview", "$('messageDialogClose')");
        assertTrue(inspector.contains("messagePreviewBytes"));
        assertTrue(inspector.contains("TextDecoder('utf-8', { fatal: true })"));
        assertTrue(inspector.contains("JSON.stringify(JSON.parse(decoded), null, 2)"));
        assertTrue(inspector.contains("textContent = bodyText"));
        assertTrue(inspector.contains("preview.truncated"));
        assertTrue(page.contains("external evidence documents remain in object storage/IPFS"));
    }

    @Test
    void l1StatusPage_linksToAppChainPage() {
        given()
                .when().get("/ui/status/index.html")
                .then()
                .statusCode(200)
                .body(Matchers.containsString("href=\"../app-chain/\""))
                // local-producer hero branch (devnet BP shows production, not sync %)
                .body(Matchers.containsString("Local Producer"))
                .body(Matchers.containsString("PRODUCING"));
    }

    private static String between(String source, String start, String end) {
        int startIndex = source.indexOf(start);
        assertTrue(startIndex >= 0, "missing start marker: " + start);
        int endIndex = source.indexOf(end, startIndex + start.length());
        assertTrue(endIndex >= 0, "missing end marker: " + end);
        return source.substring(startIndex, endIndex);
    }

    private static void assertBefore(String source, String first, String second) {
        int firstIndex = source.indexOf(first);
        int secondIndex = source.indexOf(second);
        assertTrue(firstIndex >= 0, "missing marker: " + first);
        assertTrue(secondIndex >= 0, "missing marker: " + second);
        assertTrue(firstIndex < secondIndex, first + " must precede " + second);
    }
}
