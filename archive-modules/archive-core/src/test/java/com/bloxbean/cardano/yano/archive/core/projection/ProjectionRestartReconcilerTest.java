package com.bloxbean.cardano.yano.archive.core.projection;

import com.bloxbean.cardano.yano.api.CanonicalBlockReference;
import com.bloxbean.cardano.yano.api.archive.ProjectionCfNames;
import com.bloxbean.cardano.yano.archive.api.ArchiveNetworkIdentity;
import com.bloxbean.cardano.yano.archive.api.projection.ProjectionBlockKind;
import com.bloxbean.cardano.yano.archive.api.projection.ProjectionCoordinate;
import com.bloxbean.cardano.yano.archive.api.projection.ProjectionEnvelopeHeader;
import com.bloxbean.cardano.yano.archive.api.projection.ProjectionSectionType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.rocksdb.ColumnFamilyDescriptor;
import org.rocksdb.ColumnFamilyHandle;
import org.rocksdb.DBOptions;
import org.rocksdb.RocksDB;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProjectionRestartReconcilerTest {
    static { RocksDB.loadLibrary(); }
    private static final Set<ProjectionSectionType> REQUIRED =
            Set.of(ProjectionSectionType.TRANSACTION);

    @TempDir Path directory;
    private RocksDB db;
    private DBOptions options;
    private List<ColumnFamilyHandle> handles;
    private ProjectionOutboxStore outbox;

    @BeforeEach
    void setUp() throws Exception {
        options = new DBOptions().setCreateIfMissing(true).setCreateMissingColumnFamilies(true);
        List<ColumnFamilyDescriptor> descriptors = new ArrayList<>();
        descriptors.add(new ColumnFamilyDescriptor(RocksDB.DEFAULT_COLUMN_FAMILY));
        for (String name : ProjectionCfNames.ALL) {
            descriptors.add(new ColumnFamilyDescriptor(name.getBytes(StandardCharsets.UTF_8)));
        }
        handles = new ArrayList<>();
        db = RocksDB.open(options, directory.resolve("db").toString(), descriptors, handles);
        outbox = new ProjectionOutboxStore(db, handles.get(1), handles.get(2), handles.get(3),
                handles.get(4));
    }

    @AfterEach
    void tearDown() {
        handles.forEach(ColumnFamilyHandle::close);
        db.close();
        options.close();
    }

    @Test
    void crashBetweenRollbackHandlersConvergesInEitherOrderingBeforeDrain() {
        for (boolean projectionHandlerAlreadyRan : List.of(false, true)) {
            ProjectionEnvelopeHeader canonical = header(1, 10, 0x11);
            ProjectionEnvelopeHeader stale = header(2, 20, 0x22);
            outbox.commit(writer -> outbox.putBlockIdentity(writer, canonical));
            outbox.acknowledgeThrough(1);
            outbox.commit(writer -> outbox.putBlockIdentity(writer, stale));
            if (projectionHandlerAlreadyRan) {
                outbox.rollbackToPoint(10, canonical.blockHash(), false, REQUIRED);
            }

            CanonicalBlockReference tip = new CanonicalBlockReference(1, 10, canonical.blockHash());
            long removed = ProjectionRestartReconciler.reconcile(outbox,
                    ProjectionCoordinate.of(canonical), tip,
                    block -> block == 1 ? Optional.of(tip) : Optional.empty(), REQUIRED);

            assertThat(outbox.hasBlockIdentity(2)).isFalse();
            assertThat(removed).isIn(0L, 1L);
        }
    }

    @Test
    void canonicalBodyTipBelowSinkFailsClosed() {
        ProjectionEnvelopeHeader committed = header(2, 20, 0x22);
        CanonicalBlockReference tip = new CanonicalBlockReference(1, 10, bytes(0x11));

        assertThatThrownBy(() -> ProjectionRestartReconciler.reconcile(outbox,
                ProjectionCoordinate.of(committed), tip, ignored -> Optional.empty(), REQUIRED))
                .isInstanceOf(ProjectionActivationException.class)
                .hasMessageContaining("below the projection sink");
    }

    @Test
    void originAcceptsOnlyEmptyCommittedStateAndRemovesPendingSuffix() {
        ProjectionEnvelopeHeader pending = header(0, 0, 0x10);
        outbox.commit(writer -> outbox.putBlockIdentity(writer, pending));

        assertThat(ProjectionRestartReconciler.reconcile(outbox,
                ProjectionCoordinate.NONE, null, ignored -> Optional.empty(), REQUIRED))
                .isEqualTo(1);
        assertThat(outbox.hasBlockIdentity(0)).isFalse();

        outbox.commit(writer -> outbox.putBlockIdentity(writer, pending));
        assertThatThrownBy(() -> ProjectionRestartReconciler.reconcile(outbox,
                ProjectionCoordinate.of(pending), null, ignored -> Optional.empty(), REQUIRED))
                .isInstanceOf(ProjectionActivationException.class)
                .hasMessageContaining("at origin");
    }

    @Test
    void nonCanonicalSinkHashFailsClosedBeforePendingOutboxIsTrimmed() {
        ProjectionEnvelopeHeader canonical = header(1, 10, 0x11);
        ProjectionEnvelopeHeader stalePending = header(2, 20, 0x22);
        outbox.commit(writer -> outbox.putBlockIdentity(writer, canonical));
        outbox.commit(writer -> outbox.putBlockIdentity(writer, stalePending));
        CanonicalBlockReference tip = new CanonicalBlockReference(1, 10, canonical.blockHash());
        ProjectionEnvelopeHeader wrongSink = header(1, 10, 0x33);

        assertThatThrownBy(() -> ProjectionRestartReconciler.reconcile(outbox,
                ProjectionCoordinate.of(wrongSink), tip,
                block -> block == 1 ? Optional.of(tip) : Optional.empty(), REQUIRED))
                .isInstanceOf(ProjectionActivationException.class)
                .hasMessageContaining("not canonical");
        assertThat(outbox.hasBlockIdentity(2)).isTrue();
    }

    private static ProjectionEnvelopeHeader header(long block, long slot, int hashByte) {
        return new ProjectionEnvelopeHeader(new ArchiveNetworkIdentity(42, "genesis"),
                ProjectionBlockKind.BYRON_MAIN, block, bytes(hashByte), bytes(hashByte - 1),
                slot, 0, slot, 1, List.of(), List.of());
    }

    private static byte[] bytes(int value) {
        byte[] result = new byte[32];
        java.util.Arrays.fill(result, (byte) value);
        return result;
    }
}
