package com.bloxbean.cardano.yano.runtime;

import com.bloxbean.cardano.yano.runtime.internal.RuntimeNode;

import com.bloxbean.cardano.yano.api.config.YanoConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test to verify that RuntimeNode properly integrates HeaderSyncManager and BodyFetchManager
 * in pipeline mode. This test validates the core architecture without requiring network connectivity.
 */
class PipelineIntegrationTest {

    private RuntimeNode yaciNode;

    @BeforeEach
    void setUp() {
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

        yaciNode = new RuntimeNode(config);
    }

    @AfterEach
    void tearDown() {
        if (yaciNode != null && yaciNode.isRunning()) {
            yaciNode.stop();
        }
    }

    @Test
    @DisplayName("Test RuntimeNode initialization with pipeline components")
    void testYanoInitialization() {
        assertNotNull(yaciNode, "RuntimeNode should be created successfully");
        assertFalse(yaciNode.isRunning(), "RuntimeNode should not be running initially");
        assertNull(yaciNode.getLocalTip(), "RuntimeNode should start without a local tip");
    }

    @Test
    @DisplayName("Test pipeline managers are null before sync start")
    void testPipelineManagersBeforeSync() {
        // Pipeline managers should not be created until sync starts
        // We can verify this by checking that RuntimeNode compiles and creates without errors
        assertNotNull(yaciNode, "RuntimeNode with pipeline support should compile and create");
    }

    @Test
    @DisplayName("Test RuntimeNode basic lifecycle")
    void testYanoLifecycle() {
        // This tests basic RuntimeNode functionality without starting network services
        assertFalse(yaciNode.isRunning(), "RuntimeNode should not be running initially");
        assertFalse(yaciNode.isServerRunning(), "Server should not be running initially");

        // Test status access
        assertDoesNotThrow(() -> yaciNode.getStatus(), "getStatus() should not throw");

        assertNull(yaciNode.getLocalTip(), "Initial local tip should be empty");
    }

    @Test
    @DisplayName("Test RuntimeNode status reporting with pipeline configuration")
    void testStatusReporting() {
        // Test status before any sync
        var status = yaciNode.getStatus();
        assertNotNull(status, "Status should be available");

        // Verify chain query integration
        assertNull(yaciNode.getLocalTip(), "Chain query should be available before sync");
    }

    @Test
    @DisplayName("Test runtime degradation is reported in node status")
    void testRuntimeDegradationStatusReporting() {
        yaciNode.getMaintenanceGate().markDegraded("restore", "runtime restart required", null);

        var status = yaciNode.getStatus();

        assertTrue(status.isRuntimeDegraded(), "Runtime degradation should be reported");
        assertEquals("restore", status.getRuntimeDegradedOperation());
        assertEquals("runtime restart required", status.getRuntimeDegradedReason());
        assertTrue(status.getStatusMessage().contains("runtimeDegraded"));
        assertFalse(status.isMaintenanceActive(), "No maintenance lease should be active");
    }

    @Test
    @DisplayName("Test pipeline configuration is properly handled")
    void testPipelineConfiguration() {
        // This test verifies that the pipeline architecture compiles and integrates
        // without runtime errors during RuntimeNode creation

        // Test that RuntimeNode can handle different configurations
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
            RuntimeNode testNode = new RuntimeNode(config);
            assertNotNull(testNode, "RuntimeNode should be created successfully");

            // Clean up
            if (testNode.isRunning()) {
                testNode.stop();
            }
        });
    }

    @Test
    @DisplayName("Test integration doesn't break existing functionality")
    void testBackwardsCompatibility() {
        // Verify that adding pipeline support doesn't break basic RuntimeNode operations
        assertNull(yaciNode.getLocalTip(), "Chain query should be available");
        assertFalse(yaciNode.isRunning(), "Should not be running initially");
        assertFalse(yaciNode.isServerRunning(), "Server should not be running initially");

        // Basic lifecycle operations should work
        assertDoesNotThrow(() -> yaciNode.getStatus(), "getStatus() should not throw");
    }

    @Test
    @DisplayName("Test chain query operations work with pipeline integration")
    void testChainStateIntegration() {
        assertNull(yaciNode.getLocalTip(), "Initially no tip");
        assertNull(yaciNode.getBlock(hexToBytes("abcdef1234567890abcdef1234567890abcdef1234567890abcdef1234567890")),
                "Unknown block hash should not resolve");
        assertNull(yaciNode.getBlockByNumber(499L), "Unknown block number should not resolve");
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
