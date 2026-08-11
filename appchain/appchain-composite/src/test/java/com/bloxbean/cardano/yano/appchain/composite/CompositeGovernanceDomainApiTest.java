package com.bloxbean.cardano.yano.appchain.composite;

import com.bloxbean.cardano.yano.api.appchain.AppQueryResult;
import com.bloxbean.cardano.yano.api.plugin.domain.DomainApiContext;
import com.bloxbean.cardano.yano.api.plugin.domain.DomainApiRequest;
import com.bloxbean.cardano.yano.api.plugin.domain.DomainHttpMethod;
import com.bloxbean.cardano.yano.api.plugin.domain.DomainQueryService;
import com.bloxbean.cardano.yano.api.plugin.domain.LocalReadModelQueryService;
import com.bloxbean.cardano.yano.api.plugin.domain.PrivilegedSystemMessageService;
import com.bloxbean.cardano.yano.appchain.composite.contracts.CompositeProfileGovernanceV1;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class CompositeGovernanceDomainApiTest {
    @Test
    void queryDryRunAndSubmissionUseBoundedHostSeams() {
        AtomicReference<String> topic = new AtomicReference<>();
        AtomicReference<byte[]> command = new AtomicReference<>();
        AtomicInteger submissions = new AtomicInteger();
        DomainQueryService queries = new DomainQueryService() {
            @Override public List<String> chainIds() { return List.of("orders"); }
            @Override public AppQueryResult query(
                    String chainId, String path, byte[] request
            ) {
                assertThat(path).isEqualTo("composite/governance-v1");
                return new AppQueryResult(
                        chainId, "composite", 7, new byte[32], new byte[]{1, 2});
            }
        };
        PrivilegedSystemMessageService privileged =
                new PrivilegedSystemMessageService() {
                    @Override public void validate(
                            String chainId, String value, byte[] body
                    ) {
                        assertThat(chainId).isEqualTo("orders");
                        topic.set(value);
                        command.set(body.clone());
                    }

                    @Override public String submit(
                            String chainId, String value, byte[] body
                    ) {
                        validate(chainId, value, body);
                        submissions.incrementAndGet();
                        return "ab".repeat(32);
                    }
                };
        var api = new CompositeGovernanceDomainApi(new DomainApiContext(
                Map.of(), queries, LocalReadModelQueryService.unavailable(), privileged));

        var status = api.handle(request(
                "profile-governance-status", DomainHttpMethod.GET,
                Map.of(), new byte[0]));
        assertThat(status.status()).isEqualTo(200);
        assertThat(status.body()).containsExactly(1, 2);

        byte[] encoded = new CompositeProfileGovernanceV1.Cancel(
                filled(7)).encode();
        var dryRun = api.handle(request(
                "profile-governance-command", DomainHttpMethod.POST,
                Map.of("dry-run", List.of("true")), encoded));
        assertThat(new String(dryRun.body(), StandardCharsets.UTF_8))
                .isEqualTo("{\"validated\":true}");
        assertThat(topic.get()).isEqualTo(CompositeProfileGovernanceV1.TOPIC);
        assertThat(command.get()).containsExactly(encoded);
        assertThat(submissions).hasValue(0);

        var submitted = api.handle(request(
                "profile-governance-command", DomainHttpMethod.POST,
                Map.of(), encoded));
        assertThat(new String(submitted.body(), StandardCharsets.UTF_8))
                .contains("ab".repeat(32));
        assertThat(submissions).hasValue(1);
    }

    private static DomainApiRequest request(
            String route,
            DomainHttpMethod method,
            Map<String, List<String>> query,
            byte[] body
    ) {
        String suffix = route.endsWith("command") ? "/commands" : "";
        return new DomainApiRequest(
                route, method,
                "chains/orders/profile-governance" + suffix,
                Map.of("chain_id", "orders"), query, body);
    }

    private static byte[] filled(int value) {
        byte[] bytes = new byte[32];
        java.util.Arrays.fill(bytes, (byte) value);
        return bytes;
    }
}
