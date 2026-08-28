package com.bloxbean.cardano.yano.archive.core.projection;

import com.bloxbean.cardano.yaci.core.model.Epoch;
import com.bloxbean.cardano.yaci.core.model.byron.ByronAddress;
import com.bloxbean.cardano.yaci.core.model.byron.ByronBlockBody;
import com.bloxbean.cardano.yaci.core.model.byron.ByronBlockCons;
import com.bloxbean.cardano.yaci.core.model.byron.ByronBlockHead;
import com.bloxbean.cardano.yaci.core.model.byron.ByronMainBlock;
import com.bloxbean.cardano.yaci.core.model.byron.ByronTx;
import com.bloxbean.cardano.yaci.core.model.byron.ByronTxIn;
import com.bloxbean.cardano.yaci.core.model.byron.ByronTxOut;
import com.bloxbean.cardano.yaci.core.model.byron.payload.ByronTxPayload;
import com.bloxbean.cardano.yano.api.archive.ConsumedOutputAddresses;
import com.bloxbean.cardano.yano.api.archive.ProjectionCfNames;
import com.bloxbean.cardano.yano.api.events.ByronMainBlockAppliedEvent;
import com.bloxbean.cardano.yano.archive.api.ArchiveNetworkIdentity;
import com.bloxbean.cardano.yano.archive.api.projection.ProjectionIdentity;
import com.bloxbean.cardano.yano.archive.api.projection.ProjectionSectionType;
import com.bloxbean.cardano.yano.archive.core.dataset.AddressSubjectRows;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.rocksdb.ColumnFamilyDescriptor;
import org.rocksdb.ColumnFamilyHandle;
import org.rocksdb.DBOptions;
import org.rocksdb.RocksDB;
import org.rocksdb.WriteBatch;
import org.rocksdb.WriteOptions;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ByronAddressResolutionTest {
    static { RocksDB.loadLibrary(); }

    private static final Set<ProjectionSectionType> REQUIRED =
            Set.of(ProjectionSectionType.ADDRESS_TRANSACTION);
    private static final String FUNDING =
            "Ae2tdPwUPEZ3DdaWu8jn553npu6jwEPAJiahruj3xQjPXxgoxfYDWusJz7x";
    private static final String RECEIVING =
            "Ae2tdPwUPEZFRbyhz3cpfC2CumGzNkFBN2L42rcUc2yjQpEkxDbkPodpMAi";

    @TempDir Path directory;
    private RocksDB db;
    private DBOptions dbOptions;
    private List<ColumnFamilyHandle> handles;
    private ProjectionOutboxStore store;
    private CanonicalProjectionCollector collector;

    @BeforeEach
    void setUp() throws Exception {
        dbOptions = new DBOptions().setCreateIfMissing(true).setCreateMissingColumnFamilies(true);
        List<ColumnFamilyDescriptor> descriptors = new ArrayList<>();
        descriptors.add(new ColumnFamilyDescriptor(RocksDB.DEFAULT_COLUMN_FAMILY));
        for (String name : ProjectionCfNames.ALL) {
            descriptors.add(new ColumnFamilyDescriptor(name.getBytes(StandardCharsets.UTF_8)));
        }
        handles = new ArrayList<>();
        db = RocksDB.open(dbOptions, directory.resolve("db").toString(), descriptors, handles);
        store = new ProjectionOutboxStore(db, handles.get(1), handles.get(2), handles.get(3),
                handles.get(4));
        var identity = new ProjectionIdentity(new ArchiveNetworkIdentity(764824073, "5f20df93"),
                "synthetic", 1, REQUIRED);
        collector = new CanonicalProjectionCollector(store, identity, slot -> slot / 21600,
                slot -> 1_506_203_091L + slot * 20);
    }

    @AfterEach
    void tearDown() {
        handles.forEach(ColumnFamilyHandle::close);
        db.close();
        dbOptions.close();
    }

    @Test
    void byronMainUsesAddressesCapturedByTheUtxoBatch() {
        String input = hex(0x22);
        apply(3314, block(List.of(tx(hex(0x11), input))),
                (txHash, outputIndex) -> input.equals(txHash) ? FUNDING : null);

        var fact = participation(3314);
        assertThat(fact.transactions().stream()
                .flatMap(tx -> tx.participations().stream())
                .filter(p -> p.role().equals(AddressSubjectRows.Role.INPUT.name()))
                .map(p -> p.participant().address()))
                .containsExactly(FUNDING);
    }

    @Test
    void intraBlockSpendUsesTheSameCapturedView() {
        String genesis = hex(0x22);
        String first = hex(0x11);
        apply(4001, block(List.of(tx(first, genesis), tx(hex(0x12), first))),
                (txHash, outputIndex) -> Map.of(genesis, FUNDING, first, RECEIVING).get(txHash));

        var inputs = participation(4001).transactions().stream()
                .flatMap(tx -> tx.participations().stream())
                .filter(p -> p.role().equals(AddressSubjectRows.Role.INPUT.name()))
                .map(p -> p.participant().address()).toList();
        assertThat(inputs).containsExactly(FUNDING, RECEIVING);
    }

    @Test
    void missingCapturedAddressFailsClosed() {
        assertThatThrownBy(() -> apply(3314, block(List.of(tx(hex(0x11), hex(0x22)))),
                ConsumedOutputAddresses.NONE))
                .hasRootCauseInstanceOf(IllegalStateException.class)
                .hasStackTraceContaining("could not resolve consumed output");
    }

    private void apply(long blockNumber, ByronMainBlock block, ConsumedOutputAddresses consumed) {
        try (WriteBatch batch = new WriteBatch(); WriteOptions options = new WriteOptions()) {
            collector.contributeByronMainBlock(new ByronMainBlockAppliedEvent(
                            blockNumber, blockNumber, hex(0x0b), block), consumed,
                    ProjectionOutboxStore.batchWriter(batch, store.handles()));
            db.write(options, batch);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private com.bloxbean.cardano.yano.archive.core.dataset.AddressParticipationFact
            participation(long blockNumber) {
        var section = store.readEnvelope(blockNumber, REQUIRED).orElseThrow()
                .section(ProjectionSectionType.ADDRESS_TRANSACTION).orElseThrow();
        return ProjectionFactCodec.decodeAddressParticipations(ProjectionChunking.join(section.chunks()));
    }

    private static ByronMainBlock block(List<ByronTx> transactions) {
        return ByronMainBlock.builder()
                .header(ByronBlockHead.builder().protocolMagic(764824073L).prevBlock(hex(0x0a))
                        .consensusData(ByronBlockCons.builder()
                                .slotId(Epoch.builder().epoch(0).slot(1).build())
                                .difficulty(BigInteger.ONE).build()).build())
                .body(ByronBlockBody.builder().txPayload(transactions.stream()
                        .map(tx -> ByronTxPayload.builder().transaction(tx).witnesses(List.of()).build())
                        .toList()).build()).build();
    }

    private static ByronTx tx(String hash, String input) {
        return ByronTx.builder().txHash(hash)
                .inputs(List.of(ByronTxIn.builder().txId(input).index(0).build()))
                .outputs(List.of(ByronTxOut.builder()
                        .address(ByronAddress.builder().base58Raw(RECEIVING).build())
                        .amount(BigInteger.valueOf(900_000)).build())).build();
    }

    private static String hex(int value) {
        return String.format("%02x", value).repeat(32);
    }
}
