package com.example.yano.indexer;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Map;

/**
 * Node settings for this application. Only what the indexer needs is exposed here; every
 * other {@code YanoConfig} field keeps the network preset's value.
 *
 * @param network     mainnet, preprod, preview or sanchonet
 * @param remoteHost  upstream Cardano node to sync from
 * @param remotePort  upstream node port
 * @param storagePath Yano's RocksDB directory; one process at a time
 * @param serverPort  node-to-node port for downstream peers; 0 disables the server
 * @param globals     runtime feature switches, keyed by {@code YanoPropertyKeys} names
 */
@ConfigurationProperties("yano")
public record YanoProperties(String network, String remoteHost, int remotePort, String storagePath,
                             int serverPort, Map<String, Object> globals) {
}
