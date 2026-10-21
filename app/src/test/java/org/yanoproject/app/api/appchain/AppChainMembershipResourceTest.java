package org.yanoproject.app.api.appchain;

import org.yanoproject.api.appchain.AppChainGateway;
import org.yanoproject.api.appchain.MembershipChangeRejectedException;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** bloxbean/yano#163: an uncertifiable governed membership change is a coded 400. */
class AppChainMembershipResourceTest {
    private static final MembershipChangeRejectedException REJECTED = new MembershipChangeRejectedException(
            MembershipChangeRejectedException.QUORUM_INVALID, "A 2-of-4 membership breaks the quorum rules");

    @Test
    void quorumRejectionCarriesItsStableCode() {
        AppChainResource.ChainScopedResource resource = resource();
        String key = "aa".repeat(32);
        assertCoded(resource.addMember(new AppChainResource.ChainScopedResource.MemberRequest(key)));
        assertCoded(resource.removeMember(new AppChainResource.ChainScopedResource.MemberRequest(key)));
        assertCoded(resource.setThreshold(new AppChainResource.ChainScopedResource.ThresholdRequest(2)));
    }

    private static void assertCoded(Response response) {
        try (response) {
            assertEquals(400, response.getStatus());
            assertEquals(Map.of("code", "MEMBERSHIP_QUORUM_INVALID",
                    "error", "A 2-of-4 membership breaks the quorum rules"), response.getEntity());
        }
    }

    private static AppChainResource.ChainScopedResource resource() {
        AppChainGateway gateway = (AppChainGateway) Proxy.newProxyInstance(AppChainGateway.class.getClassLoader(),
                new Class<?>[]{AppChainGateway.class}, (proxy, method, args) -> {
                    if (Set.of("addMember", "removeMember", "setThreshold").contains(method.getName())) {
                        throw REJECTED;
                    }
                    throw new AssertionError("Unexpected gateway operation: " + method.getName());
                });
        return new AppChainResource.ChainScopedResource(gateway);
    }
}
