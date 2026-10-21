package com.example.yano.indexer;

import com.bloxbean.cardano.yaci.core.model.AuxData;
import com.bloxbean.cardano.yaci.core.model.Block;
import com.bloxbean.cardano.yaci.core.model.Era;
import com.bloxbean.cardano.yaci.core.protocol.chainsync.messages.Point;
import com.bloxbean.cardano.yaci.events.api.DomainEventListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.yanoproject.api.events.BlockAppliedEvent;
import org.yanoproject.api.events.RollbackEvent;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Indexes transaction metadata under one label, the way a yaci-store scoped indexer would.
 *
 * <p>Yano calls the annotated methods on its sync thread, after it has stored the block. A slow
 * handler slows sync. A handler that throws stops the block from being applied: Yano removes it
 * from its own chain state and fetches it again, so the index never skips a block.
 *
 * <p>Keep this class free of Spring AOP annotations such as {@code @Transactional}. Yano finds the
 * listener methods by reflection on the registered object, and a proxy hides them.
 */
@Component
public class MetadataIndexer {

    private static final Logger log = LoggerFactory.getLogger(MetadataIndexer.class);

    private final MetadataStore store;
    private final JsonMapper json;
    private final long label;

    public MetadataIndexer(MetadataStore store, JsonMapper json,
                           @Value("${indexer.metadata-label:674}") long label) {
        this.store = store;
        this.json = json;
        this.label = label;
    }

    @DomainEventListener
    public void onBlockApplied(BlockAppliedEvent event) {
        index(event);
    }

    @DomainEventListener
    public void onRollback(RollbackEvent event) {
        Point target = event.target();
        store.rollbackToSlot(target.getHash() == null ? -1 : target.getSlot());
        if (event.realReorg()) {
            log.info("Rolled back to slot {}", target.getSlot());
        }
    }

    /** Writes one block. Used for live events and for the startup catch-up. */
    void index(BlockAppliedEvent event) {
        // Byron blocks carry no metadata, and a Byron epoch-boundary block reuses the
        // number of the block before it, so the cursor starts with the Shelley era.
        if (event.era() == Era.Byron) {
            return;
        }
        List<MetadataStore.Entry> entries = extract(event);
        store.saveBlock(new MetadataStore.IndexedBlock(event.blockNumber(), event.slot(), event.blockHash()),
                entries);
        if (!entries.isEmpty()) {
            log.info("Block {}: {} metadata entries under label {}", event.blockNumber(), entries.size(), label);
        }
    }

    private List<MetadataStore.Entry> extract(BlockAppliedEvent event) {
        Block block = event.block();
        if (block == null || block.getAuxiliaryDataMap() == null || block.getAuxiliaryDataMap().isEmpty()) {
            return List.of();
        }
        // Phase-2 invalid transactions are in the block but the ledger ignores their metadata.
        Set<Integer> invalid = block.getInvalidTransactions() == null
                ? Set.of() : new HashSet<>(block.getInvalidTransactions());
        String key = Long.toString(label);
        List<MetadataStore.Entry> entries = new ArrayList<>();
        for (int i = 0; i < block.getTransactionBodies().size(); i++) {
            AuxData aux = block.getAuxiliaryDataMap().get(i);
            if (aux == null || aux.getMetadataJson() == null || invalid.contains(i)) {
                continue;
            }
            JsonNode value = json.readTree(aux.getMetadataJson()).get(key);
            if (value != null) {
                String txHash = block.getTransactionBodies().get(i).getTxHash();
                entries.add(new MetadataStore.Entry(txHash, label, value.toString(),
                        event.blockNumber(), event.slot()));
            }
        }
        return entries;
    }
}
