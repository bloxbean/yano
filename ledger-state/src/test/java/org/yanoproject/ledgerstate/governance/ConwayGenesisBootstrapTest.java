package org.yanoproject.ledgerstate.governance;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.rocksdb.WriteBatch;
import org.yanoproject.ledgerstate.test.TestRocksDBHelper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConwayGenesisBootstrapTest {

    private static final String COLD = "c1".repeat(28);

    @TempDir
    Path tempDir;

    private TestRocksDBHelper rocks;

    @BeforeEach
    void setUp() throws Exception {
        rocks = TestRocksDBHelper.create(tempDir);
    }

    @AfterEach
    void tearDown() {
        rocks.close();
    }

    @Test
    void genesisCommitteeMemberWithTermZeroIsAConfigurationError() throws Exception {
        // Yano tells a member from a pre-enrollment placeholder (term 0) by its term; on-chain terms are >= 1.
        ConwayGenesisBootstrap bootstrap = bootstrapFor(0);

        try (WriteBatch batch = new WriteBatch()) {
            assertThatThrownBy(() -> bootstrap.bootstrap(0, batch, new ArrayList<>()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("keyHash-" + COLD)
                    .hasMessageContaining("at least 1");
        }
    }

    @Test
    void genesisCommitteeMemberWithATermIsStored() throws Exception {
        ConwayGenesisBootstrap bootstrap = bootstrapFor(100);

        try (WriteBatch batch = new WriteBatch()) {
            assertThat(bootstrap.bootstrap(0, batch, new ArrayList<>())).isTrue();
        }
    }

    private ConwayGenesisBootstrap bootstrapFor(int term) throws Exception {
        Path genesis = tempDir.resolve("conway-genesis.json");
        Files.writeString(genesis, """
                {"committee": {"members": {"keyHash-%s": %d}, "threshold": {"numerator": 2, "denominator": 3}}}
                """.formatted(COLD, term));
        return new ConwayGenesisBootstrap(rocks.governanceStore(), genesis.toString());
    }
}
