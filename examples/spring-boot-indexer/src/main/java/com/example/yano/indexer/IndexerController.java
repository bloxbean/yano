package com.example.yano.indexer;

import com.bloxbean.cardano.yaci.core.storage.ChainTip;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.yanoproject.runtime.assembly.Yano;

import java.util.List;

@RestController
public class IndexerController {

    public record Status(Long yanoTipBlock, Long yanoTipSlot, Long indexedBlock, boolean syncing) {
    }

    private final Yano yano;
    private final MetadataStore store;

    public IndexerController(Yano yano, MetadataStore store) {
        this.yano = yano;
        this.store = store;
    }

    @GetMapping("/api/metadata")
    public List<MetadataStore.Entry> metadata(@RequestParam(defaultValue = "20") int limit) {
        return store.latest(Math.clamp(limit, 1, 100));
    }

    @GetMapping("/api/status")
    public Status status() {
        ChainTip tip = yano.chain().getLocalTip();
        return new Status(tip != null ? tip.getBlockNumber() : null, tip != null ? tip.getSlot() : null,
                store.lastBlock().map(MetadataStore.IndexedBlock::blockNumber).orElse(null),
                yano.lifecycle().isSyncing());
    }
}
