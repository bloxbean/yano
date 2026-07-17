package com.bloxbean.cardano.yano.app.api.appchain;

import com.bloxbean.cardano.yano.api.appchain.AppChainGateway;
import com.bloxbean.cardano.yano.appchain.composite.contracts.CompositeProfileGovernanceV1;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class AppChainProfileGovernanceResourceTest {
    @Test
    void dryRunAndSubmissionUseOnlyTheReservedCompositeTopic() {
        AtomicReference<String> topic = new AtomicReference<>();
        AtomicReference<byte[]> body = new AtomicReference<>();
        AtomicInteger submits = new AtomicInteger();
        AppChainGateway gateway = (AppChainGateway) Proxy.newProxyInstance(
                AppChainGateway.class.getClassLoader(), new Class<?>[]{AppChainGateway.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "chainId" -> "orders";
                    case "validatePrivilegedSystemMessage" -> {
                        topic.set((String) args[0]);
                        body.set(((byte[]) args[1]).clone());
                        yield null;
                    }
                    case "submitPrivilegedSystemMessage" -> {
                        topic.set((String) args[0]);
                        body.set(((byte[]) args[1]).clone());
                        submits.incrementAndGet();
                        yield "ab".repeat(32);
                    }
                    case "stateMachineStatus" -> Map.of("mode", "governed");
                    default -> method.getReturnType().isPrimitive() ? 0 : null;
                });
        var resource = new AppChainResource.ChainScopedResource(gateway);

        Response dryRun = resource.submitProfileGovernanceCommand(
                new AppChainResource.ChainScopedResource.CompositeProfileCommandRequest(
                        "00ff", true));
        assertEquals(200, dryRun.getStatus());
        assertEquals("~governance/composite-profile", topic.get());
        assertArrayEquals(new byte[]{0, (byte) 0xff}, body.get());
        assertEquals(0, submits.get());

        Response submitted = resource.submitProfileGovernanceCommand(
                new AppChainResource.ChainScopedResource.CompositeProfileCommandRequest(
                        "00ff", false));
        assertEquals(202, submitted.getStatus());
        assertEquals(1, submits.get());
        assertEquals(200, resource.profileGovernanceStatus().getStatus());
    }

    @Test
    void malformedHexIsRejectedBeforeGatewayInvocation() {
        AtomicInteger calls = new AtomicInteger();
        AppChainGateway gateway = (AppChainGateway) Proxy.newProxyInstance(
                AppChainGateway.class.getClassLoader(), new Class<?>[]{AppChainGateway.class},
                (proxy, method, args) -> {
                    calls.incrementAndGet();
                    return method.getReturnType().isPrimitive() ? 0 : null;
                });
        var resource = new AppChainResource.ChainScopedResource(gateway);

        assertEquals(400, resource.submitProfileGovernanceCommand(
                new AppChainResource.ChainScopedResource.CompositeProfileCommandRequest(
                        "ABC", false)).getStatus());
        assertEquals(400, resource.submitProfileGovernanceCommand(
                new AppChainResource.ChainScopedResource.CompositeProfileCommandRequest(
                        "00".repeat(CompositeProfileGovernanceV1.MAX_COMMAND_BYTES + 1),
                        false)).getStatus());
        assertEquals(0, calls.get());
    }
}
