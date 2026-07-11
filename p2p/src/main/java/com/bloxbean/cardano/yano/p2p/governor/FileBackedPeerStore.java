package com.bloxbean.cardano.yano.p2p.governor;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Small persistent peer store for rooted-relay and discovery modes.
 */
public final class FileBackedPeerStore implements PeerStore {
    private static final Logger log = LoggerFactory.getLogger(FileBackedPeerStore.class);
    private static final ObjectMapper objectMapper = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);
    private static final TypeReference<List<PeerStoreEntry>> PEER_LIST_TYPE = new TypeReference<>() { };

    private final Path file;
    private final Map<String, PeerStoreEntry> peers = new ConcurrentHashMap<>();

    public FileBackedPeerStore(Path file) {
        this.file = file.toAbsolutePath();
        load();
    }

    @Override
    public synchronized void put(PeerStoreEntry peer) {
        if (peer == null || peer.id() == null || peer.id().isBlank()) {
            return;
        }
        peers.put(peer.id(), peer);
        persist();
    }

    @Override
    public synchronized void replaceAll(List<PeerStoreEntry> replacement) {
        peers.clear();
        if (replacement != null) {
            for (PeerStoreEntry peer : replacement) {
                if (isUsable(peer)) {
                    peers.put(peer.id(), peer);
                }
            }
        }
        persist();
    }

    @Override
    public List<PeerStoreEntry> all() {
        return peers.values().stream()
                .sorted(Comparator.comparing(PeerStoreEntry::id))
                .toList();
    }

    private void load() {
        if (!Files.isRegularFile(file)) {
            return;
        }
        try {
            List<PeerStoreEntry> loaded = objectMapper.readValue(file.toFile(), PEER_LIST_TYPE);
            for (PeerStoreEntry peer : loaded) {
                if (isUsable(peer)) {
                    peers.put(peer.id(), peer);
                }
            }
            log.info("Loaded {} upstream peer-store entries from {}", peers.size(), file);
        } catch (Exception e) {
            log.warn("Unable to load upstream peer store from {}; starting with an empty peer store: {}",
                    file, e.toString());
        }
    }

    private synchronized void persist() {
        try {
            Path parent = file.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            objectMapper.writeValue(tmp.toFile(), all());
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            log.warn("Unable to persist upstream peer store to {}: {}", file, e.toString());
        }
    }

    private static boolean isUsable(PeerStoreEntry peer) {
        return peer != null
                && peer.id() != null && !peer.id().isBlank()
                && peer.host() != null && !peer.host().isBlank()
                && peer.port() > 0 && peer.port() <= 65_535;
    }
}
