package org.yanoproject.runtime.appchain;

import com.bloxbean.cardano.yaci.core.model.Block;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The indexes of a block's phase-2-invalid transactions. Cardano includes a transaction whose script fails, but
 * consumes only its collateral and creates only its collateral-return output: its regular inputs are not spent and
 * its regular outputs and metadata never take effect. Observers must not report it as if it had happened.
 */
final class InvalidL1Transactions {
    private InvalidL1Transactions() {
    }

    static Set<Integer> of(Block block) {
        List<Integer> invalid = block == null ? null : block.getInvalidTransactions();
        if (invalid == null || invalid.isEmpty()) {
            return Set.of();
        }
        Set<Integer> indexes = new HashSet<>();
        for (Integer index : invalid) {
            if (index != null) {
                indexes.add(index);
            }
        }
        return indexes;
    }
}
