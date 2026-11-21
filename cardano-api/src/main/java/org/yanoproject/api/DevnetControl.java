package org.yanoproject.api;

import org.yanoproject.api.model.FundResult;
import org.yanoproject.api.model.DevnetRollbackResult;
import org.yanoproject.api.model.DevnetRollbackTarget;
import org.yanoproject.api.model.DevnetRestoreResult;
import org.yanoproject.api.model.SnapshotInfo;
import org.yanoproject.api.model.TimeAdvanceResult;

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
