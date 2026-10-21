package org.yanoproject.runtime.ledger.canonical;

import com.bloxbean.cardano.client.address.Address;
import com.bloxbean.cardano.yaci.core.model.Amount;
import com.bloxbean.cardano.yaci.core.model.Block;
import com.bloxbean.cardano.yaci.core.model.BlockHeader;
import com.bloxbean.cardano.yaci.core.model.Era;
import com.bloxbean.cardano.yaci.core.model.HeaderBody;
import com.bloxbean.cardano.yaci.core.model.TransactionBody;
import com.bloxbean.cardano.yaci.core.model.TransactionOutput;
import com.bloxbean.cardano.yaci.core.model.certs.StakeCredType;
import com.bloxbean.cardano.yaci.core.model.certs.StakeCredential;
import com.bloxbean.cardano.yaci.core.model.certs.StakeRegistration;
import com.bloxbean.cardano.yaci.core.protocol.chainsync.messages.Point;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import com.bloxbean.cardano.yaci.events.api.SubscriptionOptions;
import com.bloxbean.cardano.yaci.helper.PeerClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.yanoproject.api.events.BlockAppliedEvent;
import org.yanoproject.api.events.RollbackEvent;
import org.yanoproject.api.utxo.model.Outpoint;
import org.yanoproject.ledger.rules.view.Lookup;
import org.yanoproject.ledger.rules.view.model.CredentialKey;
import org.yanoproject.ledgerstate.AccountStateEventHandler;
import org.yanoproject.runtime.BodyFetchManager;
import org.yanoproject.runtime.chain.InMemoryChainState;
import org.yanoproject.runtime.events.PropagatingEventBus;
import org.yanoproject.runtime.utxo.UtxoEventHandler;

import java.math.BigInteger;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ADR-056: the production follower path ({@link BodyFetchManager} with the real UTxO and account
 * listeners) applies one block as one canonical write section, and a compensating rollback joins
 * the failed block's section.
 */
class CanonicalWriteSectionTest {

    private static final String BLOCK_HASH = "b1".repeat(32);
    private static final String TX_HASH = "a1".repeat(32);
    private static final String STAKE = "11".repeat(28);

    @TempDir
    Path tempDir;

    private CanonicalTestStores stores;
    private PropagatingEventBus bus;

    @AfterEach
    void tearDown() {
        if (bus != null) {
            bus.close();
        }
        if (stores != null) {
            stores.close();
        }
    }

    @Test
    @Timeout(30)
    void followerBlockApplyIsOneSectionOverTheRealListeners() throws Exception {
        stores = new CanonicalTestStores(tempDir, false, epoch -> Optional.empty());
        bus = new PropagatingEventBus();
        new UtxoEventHandler(bus, stores.utxos);                 // order 100
        new AccountStateEventHandler(bus, stores.accounts);      // order 110
        CountDownLatch utxoApplied = new CountDownLatch(1);
        CountDownLatch resume = new CountDownLatch(1);
        List<String> mempool = Collections.synchronizedList(new ArrayList<>());
        bus.subscribe(BlockAppliedEvent.class, ctx -> {
            CanonicalStateGate.runAfterWriteRelease(() -> mempool.add(
                    "held=" + stores.gate.isWriteHeldByCurrentThread()));
            utxoApplied.countDown();
            await(resume);
        }, SubscriptionOptions.builder().priority(105).build());
        BodyFetchManager manager = new BodyFetchManager(peerClient(), stores.chain, bus, 5, 10, 5_000, 1000);
        long generation = stores.gate.generation();

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> apply = executor.submit(() -> manager.applyBlock(Era.Conway, block(), List.of()));
            assertThat(utxoApplied.await(10, TimeUnit.SECONDS)).isTrue();
            // The UTxO of the block is committed, the account is not yet.
            Future<List<Lookup<?>>> capture = executor.submit(this::readBoth);
            assertThatThrownBy(() -> capture.get(300, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);

            resume.countDown();
            apply.get(10, TimeUnit.SECONDS);
            List<Lookup<?>> observed = capture.get(10, TimeUnit.SECONDS);
            assertThat(observed.get(0)).isInstanceOf(Lookup.Present.class);
            assertThat(observed.get(1)).isInstanceOf(Lookup.Present.class);
        } finally {
            resume.countDown();
            executor.shutdownNow();
        }
        assertThat(stores.gate.generation()).isEqualTo(generation + 1);
        assertThat(stores.gate.tip().blockHash()).isEqualTo(BLOCK_HASH);
        assertThat(mempool).containsExactly("held=false");
    }

    @Test
    void compensatingRollbackJoinsTheFailedBlockSection() {
        InMemoryChainState chain = new InMemoryChainState();
        chain.storeBlock(HexUtil.decodeHexString("0a".repeat(32)), 500L, 1000L, new byte[]{0});
        CanonicalStateGate gate = CanonicalStateGate.of(chain);
        bus = new PropagatingEventBus();
        List<String> rollbacks = Collections.synchronizedList(new ArrayList<>());
        bus.subscribe(BlockAppliedEvent.class, ctx -> {
            throw new IllegalStateException("account apply failed");
        }, SubscriptionOptions.builder().build());
        bus.subscribe(RollbackEvent.class, ctx -> rollbacks.add(
                "held=" + gate.isWriteHeldByCurrentThread() + " gen=" + gate.generation()),
                SubscriptionOptions.builder().build());
        BodyFetchManager manager = new BodyFetchManager(peerClient(), chain, bus, 5, 10, 5_000, 1000);
        long generation = gate.generation();

        Block block = Block.builder()
                .header(BlockHeader.builder().headerBody(HeaderBody.builder()
                        .slot(1001).blockNumber(501).blockHash(BLOCK_HASH).build()).build())
                .cbor("deadbeef")
                .build();
        assertThatThrownBy(() -> manager.applyBlock(Era.Conway, block, List.of()));

        // One section: the rollback ran while the block's section was still held, and only one
        // generation was published for store + failed apply + compensation.
        assertThat(rollbacks).containsExactly("held=true gen=" + generation);
        assertThat(gate.generation()).isEqualTo(generation + 1);
        assertThat(chain.getTip().getSlot()).isEqualTo(1000L);
    }

    private List<Lookup<?>> readBoth() {
        Lookup<CanonicalSnapshot> acquired = stores.gate.acquireSnapshot(SnapshotPurpose.ADMISSION);
        CanonicalSnapshot snapshot = ((Lookup.Present<CanonicalSnapshot>) acquired).value();
        try (CanonicalLedgerView view = CanonicalLedgerView.over(snapshot)) {
            snapshot.release();
            return List.of(view.utxo(new Outpoint(TX_HASH, 0)), view.account(CredentialKey.key(STAKE)));
        }
    }

    private static Block block() {
        String address = new Address(HexUtil.decodeHexString("60" + "22".repeat(28))).toBech32();
        TransactionBody tx = TransactionBody.builder()
                .txHash(TX_HASH)
                .outputs(new ArrayList<>(List.of(TransactionOutput.builder()
                        .address(address)
                        .amounts(new ArrayList<>(List.of(Amount.builder()
                                .unit("lovelace").quantity(BigInteger.valueOf(5_000_000)).build())))
                        .build())))
                .certificates(new ArrayList<>(List.of(StakeRegistration.builder()
                        .stakeCredential(StakeCredential.builder()
                                .type(StakeCredType.ADDR_KEYHASH).hash(STAKE).build())
                        .build())))
                .build();
        return Block.builder()
                .era(Era.Conway)
                .header(BlockHeader.builder().headerBody(HeaderBody.builder()
                        .slot(100).blockNumber(1).blockHash(BLOCK_HASH).build()).build())
                .transactionBodies(new ArrayList<>(List.of(tx)))
                .cbor("deadbeef")
                .build();
    }

    private static PeerClient peerClient() {
        return new PeerClient("mock-host", 3001, 1, Point.ORIGIN) {
        };
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(20, TimeUnit.SECONDS)) {
                throw new IllegalStateException("latch timed out");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
