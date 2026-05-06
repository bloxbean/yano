package com.bloxbean.cardano.yano.ledgerstate;

import com.bloxbean.cardano.yano.api.EpochParamProvider;
import com.bloxbean.cardano.yano.ledgerstate.governance.GovernanceBlockProcessor;
import com.bloxbean.cardano.yano.ledgerstate.governance.model.CommitteeMemberRecord;
import com.bloxbean.cardano.yano.ledgerstate.test.TestRocksDBHelper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.rocksdb.WriteBatch;
import org.rocksdb.WriteOptions;
import org.slf4j.LoggerFactory;

import java.math.BigInteger;
import java.nio.file.Path;
import java.util.ArrayList;

import static org.assertj.core.api.Assertions.assertThat;

class DefaultAccountStateStoreCommitteeValidationTest {

    private static final String HOT = "aa".repeat(28);
    private static final String COLD_EXPIRED = "bb".repeat(28);
    private static final String COLD_ACTIVE = "cc".repeat(28);
    private static final String COLD_RESIGNED = "dd".repeat(28);

    @TempDir
    Path tempDir;

    @Test
    void committeeHotAuthorizationUsesActiveNonResignedMemberThroughExpiryEpoch() throws Exception {
        try (var rocks = TestRocksDBHelper.create(tempDir)) {
            var governanceStore = rocks.governanceStore();
            var accountStore = new DefaultAccountStateStore(
                    rocks.db(), rocks.cfSupplier(),
                    LoggerFactory.getLogger(DefaultAccountStateStoreCommitteeValidationTest.class),
                    true, provider());
            accountStore.setGovernanceBlockProcessor(new GovernanceBlockProcessor(governanceStore, provider()));

            try (var batch = new WriteBatch(); var options = new WriteOptions()) {
                governanceStore.storeCommitteeMember(0, COLD_EXPIRED,
                        new CommitteeMemberRecord(0, HOT, 200, false), batch, new ArrayList<>());
                governanceStore.storeCommitteeMember(0, COLD_ACTIVE,
                        new CommitteeMemberRecord(0, HOT, 232, false), batch, new ArrayList<>());
                governanceStore.storeCommitteeMember(0, COLD_RESIGNED,
                        new CommitteeMemberRecord(0, HOT, 300, true), batch, new ArrayList<>());
                rocks.db().write(options, batch);
            }

            assertThat(accountStore.isCommitteeHotCredentialAuthorized(0, HOT, 232)).contains(true);
            assertThat(accountStore.isCommitteeHotCredentialAuthorized(0, HOT, 233)).contains(false);
        }
    }

    @Test
    void committeeHotAuthorizationDoesNotAcceptPlaceholderAtEpochZero() throws Exception {
        try (var rocks = TestRocksDBHelper.create(tempDir)) {
            var governanceStore = rocks.governanceStore();
            var accountStore = new DefaultAccountStateStore(
                    rocks.db(), rocks.cfSupplier(),
                    LoggerFactory.getLogger(DefaultAccountStateStoreCommitteeValidationTest.class),
                    true, provider());
            accountStore.setGovernanceBlockProcessor(new GovernanceBlockProcessor(governanceStore, provider()));

            try (var batch = new WriteBatch(); var options = new WriteOptions()) {
                governanceStore.storeCommitteeMember(0, COLD_ACTIVE,
                        new CommitteeMemberRecord(0, HOT, 0, false), batch, new ArrayList<>());
                rocks.db().write(options, batch);
            }

            assertThat(accountStore.isCommitteeHotCredentialAuthorized(0, HOT, 0)).contains(false);
        }
    }

    private static EpochParamProvider provider() {
        return new EpochParamProvider() {
            @Override
            public BigInteger getKeyDeposit(long epoch) {
                return BigInteger.ZERO;
            }

            @Override
            public BigInteger getPoolDeposit(long epoch) {
                return BigInteger.ZERO;
            }
        };
    }
}
