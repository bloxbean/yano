package org.yanoproject.runtime.ledger.canonical;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.rocksdb.WriteBatch;
import org.rocksdb.WriteOptions;
import org.yanoproject.api.model.ProtocolParamsSnapshot;
import org.yanoproject.ledger.rules.view.Lookup;
import org.yanoproject.ledger.rules.view.model.CommitteeMemberState;
import org.yanoproject.ledger.rules.view.model.CredentialKey;
import org.yanoproject.ledgerstate.governance.ConwayGenesisGovernance;
import org.yanoproject.ledgerstate.governance.GovernanceStateStore;
import org.yanoproject.ledgerstate.governance.model.CommitteeMemberRecord;
import org.yanoproject.runtime.blockproducer.ProtocolParamsMapper;

import java.math.BigInteger;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ADR-056 step 1d (decision 6a): before Yano persists its Conway genesis bootstrap (a fresh devnet, until its
 * first epoch boundary) the canonical view answers governance reads from the Conway genesis, without
 * persisting anything.
 */
class CanonicalGenesisFallbackTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String MEMBER = "11".repeat(28);
    private static final String HOT = "22".repeat(28);
    private static final String GUARDRAIL = "33".repeat(28);

    @TempDir
    Path tempDir;

    private CanonicalTestStores stores;

    @AfterEach
    void tearDown() {
        if (stores != null) {
            stores.close();
        }
    }

    @Test
    void genesisCommitteeConstitutionAndTreasuryBeforeTheBootstrap() throws Exception {
        open(genesis(false), 10);

        assertThat(read(CanonicalLedgerView::guardrailScriptHash)).isEqualTo(Lookup.present(GUARDRAIL));
        assertThat(read(CanonicalLedgerView::treasury)).isEqualTo(Lookup.present(BigInteger.ZERO));
        List<CommitteeMemberState> members = read(CanonicalLedgerView::committeeMembers).require("members");
        assertThat(members).singleElement().satisfies(m -> {
            assertThat(m.cold()).isEqualTo(CredentialKey.key(MEMBER));
            assertThat(m.expiryEpoch()).isEqualTo(50L);
            assertThat(m.isElected()).isTrue();
            assertThat(m.hot()).isNull();
        });
        assertThat(read(v -> v.account(CredentialKey.key(HOT))).isAbsent()).isTrue();

        // A hot key authorised before the bootstrap keeps the genesis term.
        storeMember(MEMBER, new CommitteeMemberRecord(0, HOT, 0, false));
        CommitteeMemberState member = read(v -> v.committeeMemberByCold(CredentialKey.key(MEMBER))).require("member");
        assertThat(member.hot()).isEqualTo(CredentialKey.key(HOT));
        assertThat(member.expiryEpoch()).isEqualTo(50L);
        assertThat(read(v -> v.committeeMembersByHot(CredentialKey.key(HOT))).require("by hot")).hasSize(1);
    }

    @Test
    void initialDRepsOrDelegationsFailAccountAndDRepReadsClosed() throws Exception {
        open(genesis(true), 10);

        assertThat(reason(read(v -> v.account(CredentialKey.key(HOT))))).contains("initialDReps/delegs");
        assertThat(reason(read(v -> v.drep(CredentialKey.key(HOT))))).contains("initialDReps/delegs");
        // Governance reads still come from the genesis.
        assertThat(read(CanonicalLedgerView::guardrailScriptHash)).isEqualTo(Lookup.present(GUARDRAIL));
    }

    @Test
    void noFallbackOnceBootstrappedOrBeforeConway() throws Exception {
        open(genesis(false), 8);
        assertThat(read(CanonicalLedgerView::guardrailScriptHash).isUnavailable()).isTrue();
        stores.close();

        stores = null;
        open(genesis(true), 10);
        GovernanceStateStore governance = new GovernanceStateStore(stores.db(), stores.cfState());
        try (WriteBatch batch = new WriteBatch(); WriteOptions options = new WriteOptions()) {
            governance.storeCommitteeThreshold(BigInteger.ONE, BigInteger.TWO, batch, new ArrayList<>());
            stores.db().write(options, batch);
        }
        // Bootstrapped: the stored (empty) state is authoritative; no constitution stored → unavailable.
        assertThat(read(CanonicalLedgerView::guardrailScriptHash).isUnavailable()).isTrue();
        assertThat(read(CanonicalLedgerView::committeeMembers).require("members")).isEmpty();
        assertThat(read(v -> v.account(CredentialKey.key(HOT))).isAbsent()).isTrue();
    }

    // ------------------------------------------------------------------ helpers

    private static ConwayGenesisGovernance genesis(boolean withDReps) throws Exception {
        String json = """
                {"constitution": {"anchor": {"url": "u", "dataHash": "%s"}, "script": "%s"},
                 "committee": {"members": {"keyHash-%s": 50}, "threshold": 0.66},
                 "initialDReps": %s}
                """.formatted("44".repeat(32), GUARDRAIL, MEMBER, withDReps ? "{\"x\": 1}" : "{}");
        return ConwayGenesisGovernance.parse(JSON.readTree(json), BigInteger.ZERO);
    }

    private void open(ConwayGenesisGovernance genesis, int protocolMajor) throws Exception {
        ProtocolParamsSnapshot params = ProtocolParamsMapper.fromNodeProtocolParamSnapshot(
                "{\"protocol_major_ver\": " + protocolMajor + ", \"protocol_minor_ver\": 0}", 0);
        stores = new CanonicalTestStores(tempDir.resolve("db" + protocolMajor + System.nanoTime()), true,
                epoch -> Optional.of(params));
        CanonicalTestStores s = stores;
        stores.gate.installSnapshotSource(new RocksCanonicalSnapshotSource(s::db, () -> s.accounts, () -> s.utxos,
                epoch -> Optional.of(params), () -> false, () -> genesis));
        stores.gate.runWrite(() -> stores.chain.storeBlock(new byte[32], 1L, 100L, new byte[]{0}));
    }

    private void storeMember(String cold, CommitteeMemberRecord record) throws Exception {
        GovernanceStateStore governance = new GovernanceStateStore(stores.db(), stores.cfState());
        try (WriteBatch batch = new WriteBatch(); WriteOptions options = new WriteOptions()) {
            governance.storeCommitteeMember(0, cold, record, batch, new ArrayList<>());
            stores.db().write(options, batch);
        }
    }

    private <T> Lookup<T> read(Function<CanonicalLedgerView, Lookup<T>> read) {
        CanonicalSnapshot snapshot = stores.gate.acquireSnapshot(SnapshotPurpose.ADMISSION).require("snapshot");
        try (CanonicalLedgerView view = CanonicalLedgerView.over(snapshot)) {
            snapshot.release();
            return read.apply(view);
        }
    }

    private static String reason(Lookup<?> lookup) {
        assertThat(lookup).isInstanceOf(Lookup.Unavailable.class);
        return ((Lookup.Unavailable<?>) lookup).reason();
    }
}
