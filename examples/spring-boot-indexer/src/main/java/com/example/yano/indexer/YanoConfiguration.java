package com.example.yano.indexer;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.yanoproject.api.config.RuntimeOptions;
import org.yanoproject.api.config.YanoConfig;
import org.yanoproject.runtime.assembly.Yano;
import org.yanoproject.runtime.assembly.YanoAssembly;
import org.yanoproject.runtime.config.GenesisFileResolver;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

@Configuration
public class YanoConfiguration {

    /**
     * Builds the node but does not start it; {@link YanoNodeLifecycle} does that once the
     * indexer has caught up. Spring calls {@code close()} on shutdown.
     */
    @Bean(destroyMethod = "close")
    public Yano yano(YanoProperties props) throws IOException {
        // RocksDB creates the last directory only.
        Files.createDirectories(Path.of(props.storagePath()));
        YanoConfig preset = YanoConfig.defaultForNetwork(props.network());
        // defaultForNetwork falls back to preprod for an unknown name.
        if (!preset.getNetwork().equalsIgnoreCase(props.network().strip())) {
            throw new IllegalArgumentException("Unknown yano.network: " + props.network());
        }
        long magic = preset.getProtocolMagic();
        YanoConfig config = preset.toBuilder()
                .remoteHost(props.remoteHost())
                .remotePort(props.remotePort())
                .rocksDBPath(props.storagePath())
                .enableServer(props.serverPort() > 0)
                .serverPort(props.serverPort())
                // Genesis files for the public networks ship inside yano-runtime.
                .shelleyGenesisFile(GenesisFileResolver.resolve(null, magic, "shelley-genesis.json"))
                .byronGenesisFile(GenesisFileResolver.resolve(null, magic, "byron-genesis.json"))
                .alonzoGenesisFile(GenesisFileResolver.resolve(null, magic, "alonzo-genesis.json"))
                .conwayGenesisFile(GenesisFileResolver.resolve(null, magic, "conway-genesis.json"))
                .build();
        config.validate();

        RuntimeOptions defaults = RuntimeOptions.defaults();
        RuntimeOptions options = new RuntimeOptions(defaults.events(), defaults.plugins(), props.globals());

        return YanoAssembly.relay(config).runtimeOptions(options).build();
    }
}
