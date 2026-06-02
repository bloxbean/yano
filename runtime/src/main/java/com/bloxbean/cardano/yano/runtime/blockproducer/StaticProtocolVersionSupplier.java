package com.bloxbean.cardano.yano.runtime.blockproducer;

import java.io.IOException;

/**
 * Resolves produced-block protocol version from static protocol-param.json.
 */
public class StaticProtocolVersionSupplier implements ProtocolVersionSupplier {

    private final ProtocolVersion protocolVersion;

    public StaticProtocolVersionSupplier(ProtocolVersion protocolVersion) {
        this.protocolVersion = protocolVersion;
    }

    public static StaticProtocolVersionSupplier fromProtocolParametersJson(String json) throws IOException {
        var protocolParams = ProtocolParamsMapper.fromNodeProtocolParam(json);
        Integer major = protocolParams.getProtocolMajorVer();
        Integer minor = protocolParams.getProtocolMinorVer();
        if (major == null || major <= 0 || minor == null || minor < 0) {
            throw new IllegalStateException("Protocol version not found or invalid in protocol-param.json");
        }
        return new StaticProtocolVersionSupplier(new ProtocolVersion(major, minor));
    }

    @Override
    public ProtocolVersion getProtocolVersion(long slot) {
        return protocolVersion;
    }
}
