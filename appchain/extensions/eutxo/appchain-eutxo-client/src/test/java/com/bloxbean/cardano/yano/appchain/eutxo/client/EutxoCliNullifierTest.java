package com.bloxbean.cardano.yano.appchain.eutxo.client;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ADR-UTXO-009 SP-M4: the standalone nullifier reconstruction/proof CLI
 * rebuilds shard roots offline and verifies them against the on-chain root.
 */
class EutxoCliNullifierTest {

    @TempDir
    Path tmp;

    @Test
    void reconstructMatchesTheOnChainShardRoot() throws Exception {
        List<byte[]> shard5 = shardIds(5, 6);
        Path ids = writeIds(shard5);
        String expected = HexFormat.of().formatHex(
                NullifierShardMirror.reconstructShardRoot(shard5));

        Result ok = run("nullifier", "reconstruct",
                "--ids", ids.toString(), "--shard", "5",
                "--expected-root", expected);
        assertThat(ok.exit).isEqualTo(EutxoCli.EXIT_OK);
        assertThat(ok.out).contains("\"status\":\"MATCH\"");

        Result mismatch = run("nullifier", "reconstruct",
                "--ids", ids.toString(), "--shard", "5",
                "--expected-root", "00".repeat(32));
        assertThat(mismatch.exit).isEqualTo(EutxoCli.EXIT_INVALID);
        assertThat(mismatch.out).contains("\"status\":\"MISMATCH\"");
    }

    @Test
    void reconstructGroupsEveryShardWhenNoShardGiven() throws Exception {
        List<byte[]> mixed = new ArrayList<>();
        mixed.addAll(shardIds(1, 3));
        mixed.addAll(shardIds(2, 2));
        Path ids = writeIds(mixed);

        Result result = run("nullifier", "reconstruct", "--ids", ids.toString());
        assertThat(result.exit).isEqualTo(EutxoCli.EXIT_OK);
        assertThat(result.out).contains("\"claimCount\":5");
        assertThat(result.out)
                .contains(HexFormat.of().formatHex(
                        NullifierShardMirror.reconstructShardRoot(shardIds(1, 3))))
                .contains(HexFormat.of().formatHex(
                        NullifierShardMirror.reconstructShardRoot(shardIds(2, 2))));
    }

    @Test
    void proofEmitsAVerifiedMembershipProofForASettledClaim() throws Exception {
        List<byte[]> shard5 = shardIds(5, 4);
        Path ids = writeIds(shard5);
        byte[] settled = shard5.get(2);

        Result result = run("nullifier", "proof",
                HexFormat.of().formatHex(settled), "--ids", ids.toString());
        assertThat(result.exit).isEqualTo(EutxoCli.EXIT_OK);
        assertThat(result.out).contains("\"kind\":\"membership\"")
                .contains("\"verified\":true")
                .contains("\"shard\":5");
    }

    @Test
    void proofEmitsAVerifiedNonMembershipProofForAnUnsettledClaim() throws Exception {
        List<byte[]> shard5 = shardIds(5, 4);
        Path ids = writeIds(shard5);
        byte[] absent = claimId(0xC3, 5); // shard 5 but not in the id set

        Result result = run("nullifier", "proof",
                HexFormat.of().formatHex(absent), "--ids", ids.toString());
        assertThat(result.exit).isEqualTo(EutxoCli.EXIT_OK);
        assertThat(result.out).contains("\"kind\":\"non-membership\"")
                .contains("\"verified\":true");
    }

    @Test
    void rejectsAClaimIdThatDoesNotMatchTheRequestedShard() throws Exception {
        Path ids = writeIds(List.of(claimId(0x33, 2)));
        Result result = run("nullifier", "reconstruct",
                "--ids", ids.toString(), "--shard", "3");
        assertThat(result.exit).isEqualTo(EutxoCli.EXIT_USAGE);
    }

    // --- helpers ----------------------------------------------------------

    private Path writeIds(List<byte[]> ids) throws Exception {
        List<String> lines = new ArrayList<>();
        lines.add("# settled claim ids extracted from L1 shard spend history");
        for (byte[] id : ids) {
            lines.add(HexFormat.of().formatHex(id));
        }
        Path file = tmp.resolve("ids-" + ids.size() + "-" + System.nanoTime() + ".txt");
        Files.write(file, lines);
        return file;
    }

    private static Result run(String... args) {
        StringWriter outBuf = new StringWriter();
        StringWriter errBuf = new StringWriter();
        int exit;
        try (PrintWriter out = new PrintWriter(outBuf);
             PrintWriter err = new PrintWriter(errBuf)) {
            exit = EutxoCli.run(args, out, err);
        }
        return new Result(exit, outBuf.toString(), errBuf.toString());
    }

    private static List<byte[]> shardIds(int shard, int count) {
        List<byte[]> ids = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            byte[] id = new byte[32];
            Arrays.fill(id, (byte) (0x10 + i));
            id[0] = (byte) (shard * 16 + i);
            id[31] = (byte) (shard & 0x0F);
            ids.add(id);
        }
        return ids;
    }

    private static byte[] claimId(int fill, int shard) {
        byte[] id = new byte[32];
        Arrays.fill(id, (byte) fill);
        id[31] = (byte) ((fill & 0xF0) | (shard & 0x0F));
        return id;
    }

    private record Result(int exit, String out, String err) {
    }
}
