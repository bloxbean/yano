package com.bloxbean.cardano.yano.api;

import com.bloxbean.cardano.yano.api.model.FundResult;
import com.bloxbean.cardano.yano.api.model.DevnetRollbackResult;
import com.bloxbean.cardano.yano.api.model.DevnetRollbackTarget;
import com.bloxbean.cardano.yano.api.model.DevnetRestoreResult;
import com.bloxbean.cardano.yano.api.model.SnapshotInfo;
import com.bloxbean.cardano.yano.api.model.TimeAdvanceResult;

import java.util.List;

/**
 * Developer-network operations. Production assemblies may omit this API.
 */
public interface DevnetControl {
    void rollbackDevnetToSlot(long targetSlot);

    default DevnetRollbackResult rollbackDevnet(DevnetRollbackTarget target) {
        throw new UnsupportedOperationException("rollbackDevnet not supported by this implementation");
    }

    SnapshotInfo createDevnetSnapshot(String name);

    void restoreDevnetSnapshot(String name);

    default DevnetRestoreResult restoreDevnetSnapshotAndGetTip(String name) {
        throw new UnsupportedOperationException("restoreDevnetSnapshotAndGetTip not supported by this implementation");
    }

    List<SnapshotInfo> listDevnetSnapshots();

    void deleteDevnetSnapshot(String name);

    FundResult fundAddress(String address, long lovelace);

    TimeAdvanceResult advanceTimeBySlots(int slots);

    default TimeAdvanceResult advanceTimeUntilSlot(long targetSlot) {
        throw new UnsupportedOperationException("advanceTimeUntilSlot not supported by this implementation");
    }

    TimeAdvanceResult advanceTimeBySeconds(int seconds);

    default long shiftGenesisAndStartProducer(int epochs) {
        throw new UnsupportedOperationException("shiftGenesisAndStartProducer not supported by this implementation");
    }

    default TimeAdvanceResult catchUpToWallClock() {
        throw new UnsupportedOperationException("catchUpToWallClock not supported by this implementation");
    }
}
