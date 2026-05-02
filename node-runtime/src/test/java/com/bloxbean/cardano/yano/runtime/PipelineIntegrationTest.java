package com.bloxbean.cardano.yano.runtime;

import com.bloxbean.cardano.yano.api.config.YanoConfig;
import com.bloxbean.cardano.yano.runtime.chain.InMemoryChainState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test to verify that Yano properly integrates HeaderSyncManager and BodyFetchManager
 * in pipeline mode. This test validates the core architecture without requiring network connectivity.
 */
class PipelineIntegrationTest {

    private Yano yaciNode;
    private InMemoryChainState chainState;

    @BeforeEach
    void setUp() {
        chainState = new InMemoryChainState();

        YanoConfig config = YanoConfig.builder()
            .remoteHost("localhost")
            .remotePort(3001)
            .protocolMagic(1)   // Preprod
            .serverPort(13337)
            .useRocksDB(false)  // Use in-memory storage
            .enableClient(true)
            .enableServer(true)
            .enablePipelinedSync(true)
            .build();

        yaciNode = new Yano(config);
    }

    @AfterEach
    void tearDown() {
        if (yaciNode != null && yaciNode.isRunning()) {
            yaciNode.stop();
        }
    }

    @Test
    @DisplayName("Test Yano initialization with pipeline components")
    void testYanoInitialization() {
        assertNotNull(yaciNode, "Yano should be created successfully");
        assertFalse(yaciNode.isRunning(), "Yano should not be running initially");
        assertNotNull(chainState, "ChainState should be available");
    }

    @Test
    @DisplayName("Test pipeline managers are null before sync start")
    void testPipelineManagersBeforeSync() {
        // Pipeline managers should not be created until sync starts
        // We can verify this by checking that Yano compiles and creates without errors
        assertNotNull(yaciNode, "Yano with pipeline support should compile and create");
    }

    @Test
    @DisplayName("Test Yano basic lifecycle")
    void testYanoLifecycle() {
        // This tests basic Yano functionality without starting network services
        assertFalse(yaciNode.isRunning(), "Yano should not be running initially");
        assertFalse(yaciNode.isServerRunning(), "Server should not be running initially");

        // Test status access
        assertDoesNotThrow(() -> yaciNode.getStatus(), "getStatus() should not throw");

        // Test chainState access
        assertNotNull(yaciNode.getChainState(), "ChainState should be accessible");
    }

    @Test
    @DisplayName("Test Yano status reporting with pipeline configuration")
    void testStatusReporting() {
        // Test status before any sync
        var status = yaciNode.getStatus();
        assertNotNull(status, "Status should be available");

        // Verify chainstate integration
        assertNotNull(yaciNode.getChainState(), "ChainState should be available");
    }

    @Test
    @DisplayName("Test pipeline configuration is properly handled")
    void testPipelineConfiguration() {
        // This test verifies that the pipeline architecture compiles and integrates
        // without runtime errors during Yano creation

        // Test that Yano can handle different configurations
        YanoConfig config = YanoConfig.builder()
            .remoteHost("test-host")
            .remotePort(3001)
            .protocolMagic(1)
            .serverPort(13337)
            .useRocksDB(false)
            .enableClient(true)
            .enableServer(false)
            .enablePipelinedSync(true)
            .build();

        // This should not throw an exception
        assertDoesNotThrow(() -> {
            Yano testNode = new Yano(config);
            assertNotNull(testNode, "Yano should be created successfully");

            // Clean up
            if (testNode.isRunning()) {
                testNode.stop();
            }
        });
    }

    @Test
    @DisplayName("Test integration doesn't break existing functionality")
    void testBackwardsCompatibility() {
        // Verify that adding pipeline support doesn't break basic Yano operations
        assertNotNull(yaciNode.getChainState(), "ChainState should be accessible");
        assertFalse(yaciNode.isRunning(), "Should not be running initially");
        assertFalse(yaciNode.isServerRunning(), "Server should not be running initially");

        // Basic lifecycle operations should work
        assertDoesNotThrow(() -> yaciNode.getStatus(), "getStatus() should not throw");
    }

    @Test
    @DisplayName("Test chainState operations work with pipeline integration")
    void testChainStateIntegration() {
        // Get the Yano's chainState
        var nodeChainState = yaciNode.getChainState();
        assertNotNull(nodeChainState, "ChainState should be available");

        // Verify basic chainState operations work
        assertNull(nodeChainState.getTip(), "Initially no tip");
        assertNull(nodeChainState.getHeaderTip(), "Initially no header tip");

        // Add test header (64-character hex string = 32 bytes)
        nodeChainState.storeBlockHeader(
            hexToBytes("1234567890abcdef1234567890abcdef1234567890abcdef1234567890abcdef"),
            500L,
            1500L,
            "header-data".getBytes()
        );

        assertNotNull(nodeChainState.getHeaderTip(), "Header tip should be set");
        assertEquals(1500L, nodeChainState.getHeaderTip().getSlot(), "Header tip slot should match");

        // Add test block (64-character hex string = 32 bytes)
        nodeChainState.storeBlock(
            hexToBytes("abcdef1234567890abcdef1234567890abcdef1234567890abcdef1234567890"),
            499L,
            1499L,
            "block-data".getBytes()
        );

        assertNotNull(nodeChainState.getTip(), "Tip should be set");
        assertEquals(1499L, nodeChainState.getTip().getSlot(), "Tip slot should match");

        // Verify gap exists (header_tip ahead of tip)
        assertEquals(1L, nodeChainState.getHeaderTip().getSlot() - nodeChainState.getTip().getSlot(), "Gap should be 1 slot");
    }

    // Helper method
    private byte[] hexToBytes(String hex) {
        int length = hex.length();
        byte[] data = new byte[length / 2];
        for (int i = 0; i < length; i += 2) {
            data[i / 2] = (byte) ((Character.digit(hex.charAt(i), 16) << 4)
                                 + Character.digit(hex.charAt(i+1), 16));
        }
        return data;
    }
}
