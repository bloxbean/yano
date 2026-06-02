package com.bloxbean.cardano.yano.runtime.blockproducer;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class StaticProtocolVersionSupplierTest {

    @Test
    void parsesNestedProtocolVersionFromProtocolParametersJson() throws Exception {
        String json = """
                {
                  "protocolVersion": { "major": 11, "minor": 0 }
                }
                """;

        var supplier = StaticProtocolVersionSupplier.fromProtocolParametersJson(json);

        assertThat(supplier.getProtocolVersion(123)).isEqualTo(new ProtocolVersion(11, 0));
    }

    @Test
    void parsesFlatProtocolVersionFromProtocolParametersJson() throws Exception {
        String json = """
                {
                  "protocol_major_ver": 10,
                  "protocol_minor_ver": 2
                }
                """;

        var supplier = StaticProtocolVersionSupplier.fromProtocolParametersJson(json);

        assertThat(supplier.getProtocolVersion(123)).isEqualTo(new ProtocolVersion(10, 2));
    }

    @Test
    void parsesDevnetPv11StaticFallbackFile() throws Exception {
        String json = Files.readString(Path.of("../app/config/network/devnet/protocol-param.json"));

        var supplier = StaticProtocolVersionSupplier.fromProtocolParametersJson(json);

        assertThat(supplier.getProtocolVersion(123)).isEqualTo(new ProtocolVersion(11, 0));
    }
}
