package org.yanoproject.runtime.ledger.canonical;

import com.bloxbean.cardano.client.common.model.SlotConfig;
import com.bloxbean.cardano.client.crypto.Blake2bUtil;
import com.bloxbean.cardano.client.spec.NetworkId;
import com.bloxbean.cardano.yaci.core.model.Block;
import com.bloxbean.cardano.yaci.core.model.Era;
import com.bloxbean.cardano.yaci.core.model.TransactionBody;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.yanoproject.api.events.BlockAppliedEvent;
import org.yanoproject.api.utxo.model.Outpoint;
import org.yanoproject.ledger.rules.LedgerValidationEngine;
import org.yanoproject.ledger.rules.TxValidationOutcome;
import org.yanoproject.ledger.rules.TxValidationRequest;
import org.yanoproject.ledger.rules.ValidatedTx;
import org.yanoproject.ledger.rules.ValidationEnv;
import org.yanoproject.ledger.rules.effects.TxEffects;
import org.yanoproject.ledger.rules.view.Lookup;
import org.yanoproject.ledger.rules.view.model.CredentialKey;
import org.yanoproject.ledger.rules.view.model.UtxoEntry;
import org.yanoproject.ledgerstate.LedgerStateTestRecords;
import org.yanoproject.runtime.utxo.UtxoTestRecords;
import org.yanoproject.runtime.validation.shadowsync.PreBlockState;
import org.yanoproject.runtime.validation.shadowsync.ShadowSyncReport;
import org.yanoproject.runtime.validation.shadowsync.ShadowSyncSettings;
import org.yanoproject.runtime.validation.shadowsync.ShadowSyncValidator;
import org.yanoproject.runtime.validation.shadowsync.SyncBlock;

import java.math.BigInteger;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ADR-056 Phase 7a over real RocksDB stores: the pre-block capture inside a write section sees what the section
 * committed before it (the epoch boundary) and nothing after it (the block's own changes), keeps that state after the
 * section publishes, and is released by shadow sync.
 */
class ShadowSyncPreBlockCaptureTest {

    private static final String MARKER_TX = "ab".repeat(32);
    private static final Outpoint MARKER = new Outpoint(MARKER_TX, 0);
    private static final CredentialKey ACCOUNT = CredentialKey.key("11".repeat(28));

    @TempDir
    Path tempDir;

    private CanonicalTestStores stores;

    @BeforeEach
    void setUp() throws Exception {
        stores = new CanonicalTestStores(tempDir, false, epoch -> Optional.empty());
        stores.gate.runWrite(() -> {
            putUtxo(1);
            putAccount(1);
            store(1, 10);
        });
    }

    @AfterEach
    void tearDown() {
        stores.close();
    }

    @Test
    void aCaptureOutsideAWriteSectionIsUnavailable() {
        assertThat(stores.gate.captureInWriteSection(SnapshotPurpose.SHADOW_SYNC))
                .isInstanceOf(Lookup.Unavailable.class);
        assertThat(stores.gate.acquireSnapshot(SnapshotPurpose.SHADOW_SYNC)).isInstanceOf(Lookup.Unavailable.class);
    }

    @Test
    void theCaptureSeesTheSectionsEarlierCommitsOnlyAndKeepsThemAfterPublication() {
        long published = stores.gate.generation();
        CanonicalSnapshot[] captured = new CanonicalSnapshot[1];
        stores.gate.runWrite(() -> {
            putUtxo(2);                     // committed before the block (the epoch boundary)
            Lookup<CanonicalSnapshot> capture = stores.gate.captureInWriteSection(SnapshotPurpose.SHADOW_SYNC);
            captured[0] = ((Lookup.Present<CanonicalSnapshot>) capture).value();
            putAccount(2);                  // the block's own change
            putUtxo(3);
        });
        CanonicalSnapshot snapshot = captured[0];
        assertThat(stores.gate.generation()).isEqualTo(published + 1);
        assertThat(snapshot.generation()).isEqualTo(published);
        assertThat(snapshot.publishedState()).isFalse();
        assertThat(snapshot.tip().slot()).isEqualTo(10);          // the parent: next(parent) is the forecast basis
        try (CanonicalLedgerView view = CanonicalLedgerView.over(snapshot)) {
            assertThat(lovelace(view)).isEqualTo(2);
            assertThat(view.account(ACCOUNT).require("account").rewardBalance()).isEqualTo(BigInteger.ONE);
        }
        snapshot.release();
        assertThat(stores.gate.liveSnapshotCount()).isZero();
    }

    @Test
    void shadowSyncCapturesDoNotCountAgainstTheLiveSnapshotCap() {
        stores.gate.setMaxLiveSnapshots(1);
        CanonicalSnapshot[] captured = new CanonicalSnapshot[1];
        stores.gate.runWrite(() -> captured[0] = ((Lookup.Present<CanonicalSnapshot>) stores.gate
                .captureInWriteSection(SnapshotPurpose.SHADOW_SYNC)).value());
        Lookup<CanonicalSnapshot> shadow = stores.gate.acquireSnapshot(SnapshotPurpose.SHADOW);
        assertThat(shadow).isInstanceOf(Lookup.Present.class);
        assertThat(stores.gate.admitShadow()).isFalse();           // the SHADOW one does count
        ((Lookup.Present<CanonicalSnapshot>) shadow).value().release();
        assertThat(stores.gate.admitShadow()).isTrue();
        captured[0].release();
        assertThat(stores.gate.liveSnapshotCount()).isZero();
    }

    @Test
    void shadowSyncValidatesAgainstThePreBlockStateAndReleasesEverySnapshot() throws Exception {
        List<Long> seen = new CopyOnWriteArrayList<>();
        LedgerValidationEngine engine = new LedgerValidationEngine() {
            @Override
            public String name() {
                return "java-julc";
            }

            @Override
            public TxValidationOutcome validate(TxValidationRequest request) {
                seen.add(request.view().utxo(MARKER).require("marker").output().getValue().getCoin().longValueExact());
                // The forecast basis is next(parent): the parent block is 10 slots earlier.
                assertThat(request.env().forecastBasisSlot()).isEqualTo(request.env().currentSlot() - 9);
                String id = SyncBlockIds.first(request.txCbor());
                return new TxValidationOutcome.Valid(new TxEffects(id, true, List.of(), List.of(), List.of()),
                        new ValidatedTx(request.txCbor(), HexUtil.decodeHexString(id), 10, 0, new byte[32], true,
                                TxValidationRequest.Origin.SYNC), false);
            }
        };
        ShadowSyncSettings settings = new ShadowSyncSettings(List.of("java-julc"), null, null, 0, 2, 1, 10_000, 0);
        CanonicalStateGate gate = stores.gate;
        try (ShadowSyncValidator validator = new ShadowSyncValidator(settings, List.of(engine),
                (slot, view) -> new ValidationEnv(slot, 0, 10, 0, NetworkId.TESTNET, new SlotConfig(1000, 0, 0),
                        new byte[32]),
                PreBlockState.ofGate(() -> gate), hash -> null, gate::isWriteHeldByCurrentThread,
                new ShadowSyncReport(null, null, 0))) {
            for (int block = 2; block <= 4; block++) {
                long value = block * 10L;
                int number = block;
                gate.runWrite(() -> {
                    store(number, 10L * number);
                    putUtxo(value);                                            // "boundary" before the block
                    validator.onBlockApplied(event(10L * number, number));
                    putUtxo(value + 1);                                        // the block itself
                });
            }
            assertThat(validator.awaitIdle(10, TimeUnit.SECONDS)).isTrue();
            ShadowSyncReport.Stats stats = validator.status().report();
            assertThat(stats.blocksValidated()).isEqualTo(3);
            assertThat(stats.agreed("java-julc")).isEqualTo(3);
        }
        assertThat(seen).containsExactlyInAnyOrder(20L, 30L, 40L);
        assertThat(stores.gate.liveSnapshotCount()).isZero();
    }

    // ------------------------------------------------------------------ helpers

    /** The id of a reassembled one-transaction block's transaction. */
    private static final class SyncBlockIds {
        static String first(byte[] tx) {
            // [body, witnesses, is_valid, aux]: the body is {0: 0} (3 bytes) after the array header
            byte[] body = new byte[3];
            System.arraycopy(tx, 1, body, 0, 3);
            return HexUtil.encodeHexString(Blake2bUtil.blake2bHash256(body));
        }
    }

    private static BlockAppliedEvent event(long slot, long number) {
        String cbor = "82" + "07" + "85" + "828080" + "81" + "a10000" + "81a0" + "a0" + "80";
        String id = SyncBlock.parse(HexUtil.decodeHexString(cbor)).txIds().getFirst();
        Block block = new Block(Era.Conway, null, List.of(TransactionBody.builder().txHash(id).build()), List.of(),
                Map.of(), List.of(), cbor);
        return new BlockAppliedEvent(Era.Conway, slot, number, HexUtil.encodeHexString(hash(number)), block);
    }

    private void store(long number, long slot) {
        stores.chain.storeBlockHeader(hash(number), number, slot, new byte[]{(byte) 0x80});
        stores.chain.storeBlock(hash(number), number, slot, new byte[]{(byte) 0x80});
    }

    private static byte[] hash(long number) {
        byte[] hash = new byte[32];
        hash[31] = (byte) number;
        return hash;
    }

    private void putUtxo(long value) {
        try {
            UtxoTestRecords.putLovelace(stores.chain, MARKER_TX, 0, BigInteger.valueOf(value));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private void putAccount(long value) {
        try {
            LedgerStateTestRecords.putStakeAccount(stores.db(), stores.cfState(), 0, ACCOUNT.hashHex(),
                    BigInteger.valueOf(value), BigInteger.TWO);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static long lovelace(CanonicalLedgerView view) {
        UtxoEntry entry = view.utxo(MARKER).require("marker");
        return entry.output().getValue().getCoin().longValueExact();
    }
}
