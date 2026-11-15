package org.yanoproject.fixture.consumer;

import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.yanoproject.api.config.YanoConfig;
import org.yanoproject.app.spi.RequiresCardano;
import org.yanoproject.runtime.assembly.Yano;

import java.util.Map;

/** Downstream REST resource served under Yano's baked API prefix. */
@Path("consumer-check")
@Produces(MediaType.APPLICATION_JSON)
@RequiresCardano
public class ConsumerCheckResource {

    @Inject
    Yano yano;

    @GET
    public Map<String, Object> check() {
        return Map.of("protocolMagic", ((YanoConfig) yano.lifecycle().getConfig()).getProtocolMagic());
    }
}
