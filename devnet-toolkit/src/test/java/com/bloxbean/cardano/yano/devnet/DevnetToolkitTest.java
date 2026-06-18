package com.bloxbean.cardano.yano.devnet;

import com.bloxbean.cardano.yano.api.model.DevnetRestoreResult;
import com.bloxbean.cardano.yano.api.model.DevnetRollbackResult;
import com.bloxbean.cardano.yano.api.model.DevnetRollbackTarget;
import com.bloxbean.cardano.yano.api.model.FundResult;
import com.bloxbean.cardano.yano.api.model.FundingRequest;
import com.bloxbean.cardano.yano.api.model.SnapshotInfo;
import com.bloxbean.cardano.yano.api.model.TimeAdvanceResult;
import com.bloxbean.cardano.yano.runtime.devnet.spi.DevnetChainMutation;
import com.bloxbean.cardano.yano.runtime.devnet.spi.DevnetFundingAccess;
import com.bloxbean.cardano.yano.runtime.devnet.spi.DevnetProducerExtensions;
import com.bloxbean.cardano.yano.runtime.devnet.spi.DevnetSnapshotAccess;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DevnetToolkitTest {
    @Test
    void delegatesDevnetControlOperationsToNarrowPorts() {
        AtomicReference<DevnetRollbackTarget> rollbackTarget = new AtomicReference<>();
        AtomicReference<String> snapshotName = new AtomicReference<>();
        AtomicReference<String> fundedAddress = new AtomicReference<>();
        AtomicReference<Integer> advancedSlots = new AtomicReference<>();

        DevnetChainMutation chain = target -> {
            rollbackTarget.set(target);
            return new DevnetRollbackResult(10, 2);
        };
        DevnetSnapshotAccess snapshots = new DevnetSnapshotAccess() {
            @Override
            public SnapshotInfo create(String name) {
                snapshotName.set(name);
                return new SnapshotInfo(name, 10, 2, 123);
            }

            @Override
            public DevnetRestoreResult restore(String name) {
                snapshotName.set(name);
                return new DevnetRestoreResult(11, 3);
            }

            @Override
            public List<SnapshotInfo> list() {
                return List.of(new SnapshotInfo("snap", 10, 2, 123));
            }

            @Override
            public void delete(String name) {
                snapshotName.set(name);
            }
        };
        DevnetFundingAccess funding = new DevnetFundingAccess() {
            @Override
            public FundResult fundAddress(String address, long lovelace) {
                fundedAddress.set(address);
                return new FundResult("tx", 0, lovelace);
            }

            @Override
            public List<FundResult> fundAddresses(List<FundingRequest> requests) {
                return requests.stream()
                        .map(request -> fundAddress(request.address(), request.lovelace()))
                        .toList();
            }
        };
        DevnetProducerExtensions producer = new DevnetProducerExtensions() {
            @Override
            public boolean isAvailable() {
                return true;
            }

            @Override
            public Optional<com.bloxbean.cardano.yano.runtime.producer.ProducerMode> mode() {
                return Optional.empty();
            }

            @Override
            public TimeAdvanceResult advanceBySlots(int slots) {
                advancedSlots.set(slots);
                return new TimeAdvanceResult(slots, slots, slots);
            }

            @Override
            public TimeAdvanceResult advanceUntilSlot(long targetSlot) {
                return new TimeAdvanceResult(targetSlot, targetSlot, 0);
            }

            @Override
            public TimeAdvanceResult advanceBySeconds(int seconds) {
                return new TimeAdvanceResult(seconds, seconds, seconds);
            }

            @Override
            public TimeAdvanceResult catchUpToWallClock() {
                return new TimeAdvanceResult(20, 4, 2);
            }

            @Override
            public long shiftGenesisAndStartProducer(int epochs) {
                return epochs * 1000L;
            }
        };

        DevnetToolkit toolkit = new DevnetToolkit(chain, snapshots, funding, producer);

        assertEquals(new DevnetRollbackResult(10, 2), toolkit.rollbackDevnet(DevnetRollbackTarget.slot(5)));
        assertEquals(5, rollbackTarget.get().slot());

        SnapshotInfo created = toolkit.createDevnetSnapshot("snap");
        assertEquals("snap", created.name());
        assertEquals("snap", snapshotName.get());

        DevnetRestoreResult restored = toolkit.restoreDevnetSnapshotAndGetTip("snap2");
        assertEquals(new DevnetRestoreResult(11, 3), restored);
        assertEquals("snap2", snapshotName.get());

        FundResult funded = toolkit.fundAddress("addr", 1000);
        assertEquals(new FundResult("tx", 0, 1000), funded);
        assertEquals("addr", fundedAddress.get());

        TimeAdvanceResult advanced = toolkit.advanceTimeBySlots(7);
        assertEquals(new TimeAdvanceResult(7, 7, 7), advanced);
        assertEquals(7, advancedSlots.get());

        assertEquals(1000L, toolkit.shiftGenesisAndStartProducer(1));
        assertEquals(producer.catchUpToWallClock(), toolkit.catchUpToWallClock());
    }
}
