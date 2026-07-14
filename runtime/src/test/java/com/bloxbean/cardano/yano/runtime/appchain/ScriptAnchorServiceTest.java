package com.bloxbean.cardano.yano.runtime.appchain;

import com.bloxbean.cardano.client.transaction.spec.Transaction;
import com.bloxbean.cardano.client.transaction.spec.TransactionOutput;
import com.bloxbean.cardano.yaci.core.protocol.appmsg.model.AppMessage;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import com.bloxbean.cardano.yano.api.appchain.AppBlock;
import com.bloxbean.cardano.yano.api.appchain.AppChainConfig;
import com.bloxbean.cardano.yano.api.appchain.FinalityCert;
import com.bloxbean.cardano.yano.api.appchain.signer.SignerProvider;
import com.bloxbean.cardano.yano.api.rollback.RollbackCapableStore;
import com.bloxbean.cardano.yano.api.utxo.UtxoState;
import com.bloxbean.cardano.yano.api.utxo.model.AssetAmount;
import com.bloxbean.cardano.yano.api.utxo.model.Outpoint;
import com.bloxbean.cardano.yano.api.utxo.model.Utxo;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigInteger;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Full A2 loop without an L1 (ADR 008.4): leader bootstraps the thread NFT,
 * then leader + follower run the {@code ~anchor/sign}/{@code ~anchor/sig}
 * co-signing protocol against a fake UTxO view; the assembled advance tx is
 * checked structurally (datum, required signers, member witnesses).
 */
class ScriptAnchorServiceTest {

    private static final Logger log = LoggerFactory.getLogger(ScriptAnchorServiceTest.class);
    private static final String CHAIN_ID = "test-chain";
    private static final byte[] L1_TIP_HASH = fill(32, 0x55);

    /**
     * Real protocol params (full PlutusV3 cost model) from the devnet
     * protocol-param.json — the same static-params file the node uses, loaded
     * through the shared mapper. Exercises the actual pricing/evaluation path.
     */
    private static final com.bloxbean.cardano.client.api.model.ProtocolParams DEVNET_PARAMS =
            loadDevnetParams();

    private static com.bloxbean.cardano.client.api.model.ProtocolParams loadDevnetParams() {
        try {
            return com.bloxbean.cardano.yano.runtime.blockproducer.ProtocolParamsMapper
                    .fromNodeProtocolParamToCardanoClient(
                            java.nio.file.Files.readString(
                                    testPath("app/config/network/devnet/protocol-param.json")), 0);
        } catch (Exception e) {
            throw new IllegalStateException("Cannot load devnet protocol params for test", e);
        }
    }

    /** Resolve a repo-relative path whether tests run from repo root or the module dir. */
    private static Path testPath(String path) {
        Path direct = Path.of(path);
        return java.nio.file.Files.exists(direct) ? direct : Path.of("..").resolve(path);
    }

    @TempDir
    Path tempDir;

    private AppLedgerStore leaderLedger;
    private AppLedgerStore followerLedger;
    private final List<byte[]> submitted = new ArrayList<>();
    private final long[] tip = {5};
    private final boolean[] failBlockLookup = {false};
    private final AddressedUtxoState utxoState = new AddressedUtxoState();

    private AppMessageSigner leaderSigner;
    private AppMessageSigner followerSigner;
    private Set<String> members;

    private ScriptAnchorService leader;
    private ScriptAnchorService follower;

    @BeforeEach
    void setUp() {
        leaderLedger = new AppLedgerStore(tempDir.resolve("leader-ledger").toString(), log);
        followerLedger = new AppLedgerStore(tempDir.resolve("follower-ledger").toString(), log);
        leaderSigner = new AppMessageSigner("11".repeat(32));
        followerSigner = new AppMessageSigner("22".repeat(32));
        members = Set.of(leaderSigner.publicKeyHex().toLowerCase(),
                followerSigner.publicKeyHex().toLowerCase());

        AppChainConfig.AnchorConfig leaderConfig = new AppChainConfig.AnchorConfig(
                true, "aa".repeat(32), 10, 60, 7014,
                AppChainConfig.AnchorConfig.DEFAULT_VALIDITY_SLOTS,
                AppChainConfig.AnchorConfig.DEFAULT_FALLBACK_FEE_LOVELACE,
                AppChainConfig.AnchorConfig.MODE_SCRIPT, null);
        AppChainConfig.AnchorConfig followerConfig = new AppChainConfig.AnchorConfig(
                false, "", 0, 0, 0);

        // Diffusers cross-deliver through the fields, so tests can swap the
        // counterpart; the sender identity is the diffusing node's member key
        leader = new ScriptAnchorService(CHAIN_ID, leaderConfig, leaderLedger,
                cbor -> {
                    submitted.add(cbor);
                    return txHash(cbor);
                },
                () -> utxoState, this::blockAt, () -> tip[0],
                new AnchorScriptArtifacts(AppChainConfig.AnchorScriptConfig.defaults()),
                leaderSigner, () -> members, () -> 2,
                (topic, body) -> deliver(this.follower, leaderSigner.publicKey(), topic, body),
                true, 42, log);
        leader.wireTxPricing(() -> DEVNET_PARAMS,
                () -> new AppChainEngine.L1Ref(500L, L1_TIP_HASH));
        wallet = leader.anchorAddress(); // pre-bootstrap: the wallet address

        follower = new ScriptAnchorService(CHAIN_ID, followerConfig, followerLedger,
                cbor -> {
                    throw new IllegalStateException("Follower must never submit");
                },
                () -> utxoState, this::blockAt, () -> tip[0],
                new AnchorScriptArtifacts(AppChainConfig.AnchorScriptConfig.defaults()),
                followerSigner, () -> members, () -> 2,
                (topic, body) -> deliver(this.leader, followerSigner.publicKey(), topic, body),
                false, 42, log);
        follower.wireTxPricing(() -> DEVNET_PARAMS,
                () -> new AppChainEngine.L1Ref(500L, L1_TIP_HASH));
    }

    private String wallet;

    @AfterEach
    void tearDown() {
        leaderLedger.close();
        followerLedger.close();
    }

    private void deliver(ScriptAnchorService target, byte[] sender, String topic, byte[] body) {
        target.onAnchorMessage(AppMessage.builder()
                .messageId(new byte[32])
                .chainId(CHAIN_ID)
                .topic(topic)
                .sender(sender)
                .body(body)
                .build());
    }

    /** Deterministic app block — identical on leader and follower. */
    private AppBlock blockAt(long height) {
        if (failBlockLookup[0]) {
            throw new AssertionError("block-provider-secret-must-not-leak");
        }
        return new AppBlock(AppBlock.BLOCK_VERSION, CHAIN_ID, height, new byte[32],
                0, new byte[0], 1_000_000 + height, new byte[32], fill(32, (int) height),
                List.of(), new byte[32], FinalityCert.empty());
    }

    private static byte[] fill(int len, int b) {
        byte[] bytes = new byte[len];
        java.util.Arrays.fill(bytes, (byte) b);
        return bytes;
    }

    private static String txHash(byte[] txCbor) {
        try {
            return com.bloxbean.cardano.client.transaction.util.TransactionUtil.getTxHash(
                    Transaction.deserialize(txCbor));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void bootstrap_thenCosignedAdvance_endToEnd() throws Exception {
        // --- Bootstrap: fund the leader wallet, mint the thread NFT
        utxoState.put(leader.anchorAddress(), List.of(
                walletUtxo("cc".repeat(32), 0, 100_000_000)));

        Map<String, Object> boot = leader.bootstrap();
        assertThat(submitted).hasSize(1);
        Transaction bootstrapTx = Transaction.deserialize(submitted.get(0));
        String bootstrapHash = (String) boot.get("txHash");
        String scriptAddress = (String) boot.get("scriptAddress");
        String policyIdHex = (String) boot.get("threadPolicyId");

        // Structure: one output locks the NFT + inline datum at the script
        TransactionOutput anchorOut = outputTo(bootstrapTx, scriptAddress);
        assertThat(anchorOut.getInlineDatum()).isNotNull();
        AnchorDatumCodec.AnchorDatum datum0 = AnchorDatumCodec.decode(anchorOut.getInlineDatum());
        assertThat(datum0.chainId()).isEqualTo(CHAIN_ID);
        assertThat(datum0.height()).isZero();
        assertThat(datum0.blockHash()).containsOnly((byte) 0);
        assertThat(datum0.stateRoot()).containsOnly((byte) 0);
        assertThat(datum0.threshold()).isEqualTo(2);
        assertThat(datum0.memberKeys()).hasSize(2);
        assertThat(bootstrapTx.getBody().getMint().get(0).getPolicyId()).isEqualTo(policyIdHex);
        assertThat(bootstrapTx.getWitnessSet().getPlutusV3Scripts()).hasSize(1);
        // buildAndSign regression guard: the wallet key must witness its inputs
        assertThat(bootstrapTx.getWitnessSet().getVkeyWitnesses()).hasSize(1);

        // Idempotence while pending: no double-submit
        assertThat(leader.bootstrap()).containsKey("pendingTx");
        assertThat(submitted).hasSize(1);

        // --- L1 confirms bootstrap: identity persists; anchor UTxO appears
        leader.onL1Block(100, List.of(bootstrapHash));
        assertThat(leader.bootstrapped()).isTrue();
        assertThat(leader.anchorAddress()).isEqualTo(scriptAddress);

        int anchorOutIndex = bootstrapTx.getBody().getOutputs().indexOf(anchorOut);
        utxoState.put(scriptAddress, List.of(
                anchorUtxo(bootstrapHash, anchorOutIndex, anchorOut, policyIdHex)));
        // Wallet change becomes the fee input for the advance
        TransactionOutput changeOut = outputTo(bootstrapTx, wallet);
        int changeIndex = bootstrapTx.getBody().getOutputs().indexOf(changeOut);
        utxoState.put(wallet, List.of(walletUtxo(bootstrapHash, changeIndex,
                changeOut.getValue().getCoin().longValue())));

        // --- First real anchor: bootstrap is identity-only at h0, so the
        // leader immediately starts the existing threshold co-sign round for
        // the current app tip. No extra user transaction/cadence wait needed.
        // The follower (no anchor config at all) adopts the identity from the
        // verified sign request, checks the datum against ITS OWN ledger, and
        // returns a witness; the leader assembles + submits.
        tip[0] = 9;
        leader.tick();

        assertThat(submitted).hasSize(2);
        Transaction advanceTx = Transaction.deserialize(submitted.get(1));

        // Datum advanced to the follower-verified tip
        TransactionOutput nextOut = outputTo(advanceTx, scriptAddress);
        AnchorDatumCodec.AnchorDatum next = AnchorDatumCodec.decode(nextOut.getInlineDatum());
        assertThat(next.height()).isEqualTo(9);
        assertThat(next.blockHash()).isEqualTo(
                com.bloxbean.cardano.yano.api.appchain.codec.AppBlockCodec.blockHash(blockAt(9)));
        assertThat(next.stateRoot()).isEqualTo(fill(32, 9));

        // Both members listed as required signers; wallet + 2 member witnesses
        assertThat(advanceTx.getBody().getRequiredSigners()).hasSize(2);
        assertThat(advanceTx.getWitnessSet().getVkeyWitnesses()).hasSize(3);
        assertThat(advanceTx.getWitnessSet().getPlutusV3Scripts()).hasSize(1);
        assertThat(advanceTx.getBody().getCollateral()).hasSize(1);
        assertThat(advanceTx.getBody().getTotalCollateral()).isNotNull();

        // One authenticated member request records only a candidate. It does
        // not authoritatively pin the identity or open finality/effect gates.
        assertThat(follower.bootstrapped()).isFalse();
        assertThat(follower.lastAnchoredHeight()).isZero();
        assertThat(follower.status())
                .containsEntry("identityCandidatePending", true)
                .containsEntry("anchoredCount", 0L)
                .containsEntry("observedAnchorCount", 0L)
                .doesNotContainKeys("lastAnchorTx", "lastAnchorL1Slot");
        assertThat(followerLedger.metaBytes("anchor_confirmation_history_v1")).isNull();

        // --- L1 confirms the advance
        String advanceHash = txHash(submitted.get(1));
        int nextOutIndex = advanceTx.getBody().getOutputs().indexOf(nextOut);
        utxoState.put(scriptAddress, List.of(
                anchorUtxo(advanceHash, nextOutIndex, nextOut, policyIdHex, 200)));
        failBlockLookup[0] = true;
        assertThat(leader.onL1Block(200, List.of(advanceHash))).isNull();
        assertThat(leader.lastAnchoredHeight()).isZero();
        assertThat(leader.status()).containsEntry("confirmationObservedAtL1Slot", 200L);
        assertThat(leader.status().get("lastError").toString())
                .isEqualTo("Script-anchor L1 confirmation failed (errorType="
                        + AssertionError.class.getName() + ")")
                .doesNotContain("block-provider-secret");
        failBlockLookup[0] = false;
        leader.tick(); // no replay of the L1 block/tx list
        assertThat(leader.lastAnchoredHeight()).isEqualTo(9);
        assertThat(leader.status()).doesNotContainKey("confirmationObservedAtL1Slot");
        assertThat(submitted).hasSize(2); // the confirmed tx was never resubmitted

        // Raw BlockApplied callbacks precede the UTxO-store commit, so a
        // member never advances from the transaction-hash callback. The
        // guarded periodic pass reads the committed thread UTxO instead.
        assertThat(follower.onL1Block(200, List.of(advanceHash))).isNull();
        assertThat(follower.lastAnchoredHeight()).isZero();
        AnchorService.ConfirmedAnchor observed = follower.tick();
        assertThat(observed).isNotNull();
        assertThat(observed.fromHeight()).isEqualTo(1);
        assertThat(observed.toHeight()).isEqualTo(9);
        assertThat(observed.txHash()).isEqualTo(advanceHash);
        assertThat(observed.l1Slot()).isEqualTo(200);
        assertThat(follower.lastAnchoredHeight()).isEqualTo(9);
        assertThat(follower.bootstrapped()).isTrue();
        assertThat(follower.status())
                .containsEntry("identityCandidatePending", false)
                .containsEntry("lastAnchorTx", advanceHash)
                .containsEntry("lastAnchorL1Slot", 200L)
                .containsEntry("anchoredCount", 0L)
                .containsEntry("observedAnchorCount", 1L);
        assertThat(AnchorService.ConfirmationHistory.decode(
                followerLedger.metaBytes("anchor_confirmation_history_v1")))
                .extracting(AnchorService.Confirmation::toHeight)
                .containsExactly(9L);
        assertThat(follower.tick()).isNull();
        assertThat(follower.status())
                .containsEntry("anchoredCount", 0L)
                .containsEntry("observedAnchorCount", 1L);

        // The first threshold-confirmed advance is the adopted identity's
        // trust checkpoint. Rolling it back clears both identity and frontier.
        follower.onL1Rollback(150);
        assertThat(follower.bootstrapped()).isFalse();
        assertThat(follower.lastAnchoredHeight()).isZero();
        assertThat(followerLedger.metaBytes("anchor_script_policy_id")).isEmpty();
        assertThat(followerLedger.metaBytes("anchor_script_hash")).isEmpty();
    }

    @Test
    void followerPeriodicRepairIsFailClosed_restartSafe_andRollbackSafe() throws Exception {
        utxoState.put(leader.anchorAddress(), List.of(
                walletUtxo("cc".repeat(32), 0, 100_000_000)));

        Map<String, Object> boot = leader.bootstrap();
        Transaction bootstrapTx = Transaction.deserialize(submitted.get(0));
        String bootstrapHash = (String) boot.get("txHash");
        String scriptAddress = (String) boot.get("scriptAddress");
        String policyIdHex = (String) boot.get("threadPolicyId");
        leader.onL1Block(100, List.of(bootstrapHash));

        TransactionOutput bootstrapOut = outputTo(bootstrapTx, scriptAddress);
        int bootstrapIndex = bootstrapTx.getBody().getOutputs().indexOf(bootstrapOut);
        Utxo bootstrapUtxo = anchorUtxo(
                bootstrapHash, bootstrapIndex, bootstrapOut, policyIdHex, 100);
        utxoState.put(scriptAddress, List.of(bootstrapUtxo));
        TransactionOutput changeOut = outputTo(bootstrapTx, wallet);
        utxoState.put(wallet, List.of(walletUtxo(bootstrapHash,
                bootstrapTx.getBody().getOutputs().indexOf(changeOut),
                changeOut.getValue().getCoin().longValue())));

        // The sign request records a non-authoritative candidate. Neither the
        // current h0 identity output nor proposed h9 opens gates before L1 acceptance.
        tip[0] = 9;
        leader.tick();
        assertThat(submitted).hasSize(2);
        assertThat(follower.lastAnchoredHeight()).isZero();

        Transaction advanceTx = Transaction.deserialize(submitted.get(1));
        String advanceHash = txHash(submitted.get(1));
        TransactionOutput advanceOut = outputTo(advanceTx, scriptAddress);
        int advanceIndex = advanceTx.getBody().getOutputs().indexOf(advanceOut);

        // A current thread UTxO with a forged state root must never move the
        // durable frontier, even when its policy/address/outpoint look valid.
        AnchorDatumCodec.AnchorDatum valid = AnchorDatumCodec.decode(advanceOut.getInlineDatum());
        AnchorDatumCodec.AnchorDatum forged = new AnchorDatumCodec.AnchorDatum(
                valid.version(), valid.chainId(), valid.height(), valid.blockHash(),
                fill(32, 0x7f), valid.memberKeys(), valid.threshold());
        Utxo forgedUtxo = new Utxo(new Outpoint(advanceHash, advanceIndex), scriptAddress,
                advanceOut.getValue().getCoin(),
                List.of(new AssetAmount(policyIdHex, "", BigInteger.ONE)),
                null, AnchorDatumCodec.encode(forged).serializeToBytes(),
                null, null, false, 200, 0, null);
        utxoState.put(scriptAddress, List.of(forgedUtxo));
        follower.tick();
        assertThat(follower.lastAnchoredHeight()).isZero();

        // Simulate a restart after the L1 callback was missed/ran before the
        // UTxO commit. The fresh service has no pendingSubmit and must repair
        // from the now-committed, independently-validated thread UTxO.
        Utxo validAdvanceUtxo = anchorUtxo(
                advanceHash, advanceIndex, advanceOut, policyIdHex, 200);
        utxoState.put(scriptAddress, List.of(validAdvanceUtxo));

        // If the leader's raw callback was missed, the same committed-view
        // repair completes its matching pending submission exactly once.
        assertThat(leader.tick()).isNotNull();
        assertThat(leader.lastAnchoredHeight()).isEqualTo(9);
        assertThat(leader.status())
                .doesNotContainKey("pendingTx")
                .containsEntry("anchoredCount", 1L);
        assertThat(submitted).hasSize(2);

        AppChainEngine.L1Ref[] visibleL1Point = {
                new AppChainEngine.L1Ref(500L, L1_TIP_HASH)};
        AtomicInteger followerSubmissions = new AtomicInteger();
        ScriptAnchorService restarted = new ScriptAnchorService(CHAIN_ID,
                new AppChainConfig.AnchorConfig(false, "", 0, 0, 0), followerLedger,
                cbor -> {
                    followerSubmissions.incrementAndGet();
                    throw new IllegalStateException("Follower must never submit");
                },
                () -> utxoState, this::blockAt, () -> tip[0],
                new AnchorScriptArtifacts(AppChainConfig.AnchorScriptConfig.defaults()),
                followerSigner, () -> members, () -> 2, (topic, body) -> { },
                false, 42, log);
        restarted.wireTxPricing(() -> DEVNET_PARAMS, () -> visibleL1Point[0]);

        restarted.tick();
        assertThat(restarted.lastAnchoredHeight()).isEqualTo(9);
        assertThat(restarted.bootstrapped()).isTrue();
        assertThat(restarted.status())
                .containsEntry("lastAnchorTx", advanceHash)
                .containsEntry("lastAnchorL1Slot", 200L)
                .containsEntry("anchoredCount", 0L)
                .containsEntry("observedAnchorCount", 1L);
        assertThat(followerSubmissions).hasValue(0);

        // Repair is idempotent, including its local since-restart counter.
        restarted.tick();
        assertThat(restarted.status()).containsEntry("observedAnchorCount", 1L);

        // Startup/snapshot repair is bidirectional: an exact canonical UTxO
        // point can safely correct stale h9 metadata down to the surviving
        // h0 identity output, even without the historical RollbackEvent.
        utxoState.put(scriptAddress, List.of(bootstrapUtxo));
        visibleL1Point[0] = new AppChainEngine.L1Ref(501L, fill(32, 0x67));
        utxoState.setAppliedPoint(501L, fill(32, 0x67));
        restarted.tick();
        assertThat(restarted.lastAnchoredHeight()).isZero();
        assertThat(restarted.status()).containsEntry("observedAnchorCount", 2L);

        // Re-observe the later anchor on a canonical point so history contains
        // both h0 and h9 for the explicit rollback race below.
        utxoState.put(scriptAddress, List.of(validAdvanceUtxo));
        visibleL1Point[0] = new AppChainEngine.L1Ref(502L, fill(32, 0x68));
        utxoState.setAppliedPoint(502L, fill(32, 0x68));
        restarted.tick();
        assertThat(restarted.lastAnchoredHeight()).isEqualTo(9);
        assertThat(restarted.status()).containsEntry("observedAnchorCount", 3L);

        // Model an already-established identity, then reproduce raw rollback
        // before async UTxO rollback. Exact point correlation rejects stale h9.
        followerLedger.metaPutLong("anchor_script_bootstrap_slot", 100L);
        restarted.onL1Rollback(150);
        visibleL1Point[0] = new AppChainEngine.L1Ref(150L, fill(32, 0x15));
        restarted.tick();
        assertThat(restarted.lastAnchoredHeight()).isZero();
        assertThat(restarted.status())
                .containsEntry("lastAnchorTx", bootstrapHash)
                .containsEntry("lastAnchorL1Slot", 100L)
                .containsEntry("observedAnchorCount", 3L);

        // Even when the new raw fork reaches the SAME numeric slot as the
        // stale store, a different hash prevents orphan resurrection.
        visibleL1Point[0] = new AppChainEngine.L1Ref(502L, fill(32, 0x66));
        restarted.tick();
        assertThat(restarted.lastAnchoredHeight()).isZero();

        // And advancing beyond the stale anchor slot is still deferred until
        // the UTxO store commits that exact new-fork point.
        visibleL1Point[0] = new AppChainEngine.L1Ref(503L, fill(32, 0x69));
        restarted.tick();
        assertThat(restarted.lastAnchoredHeight()).isZero();

        // Once the UTxO rollback commits, the surviving thread output and
        // durable metadata agree and remain stable.
        utxoState.put(scriptAddress, List.of(bootstrapUtxo));
        utxoState.setAppliedPoint(503L, fill(32, 0x69));
        restarted.tick();
        assertThat(restarted.lastAnchoredHeight()).isZero();
        assertThat(followerSubmissions).hasValue(0);
    }

    @Test
    void bootstrapRollbackAtomicallyClearsIdentityAndAllowsRebootstrap() {
        utxoState.put(leader.anchorAddress(), List.of(
                walletUtxo("cc".repeat(32), 0, 100_000_000)));
        Map<String, Object> boot = leader.bootstrap();
        String bootstrapHash = (String) boot.get("txHash");
        leader.onL1Block(100, List.of(bootstrapHash));

        assertThat(leader.bootstrapped()).isTrue();
        assertThat(leaderLedger.metaLong("anchor_last_height", -1)).isZero();

        leader.onL1Rollback(99);

        assertThat(leader.bootstrapped()).isFalse();
        assertThat(leaderLedger.metaBytes("anchor_script_policy_id")).isEmpty();
        assertThat(leaderLedger.metaBytes("anchor_script_hash")).isEmpty();
        assertThat(leaderLedger.metaLong("anchor_script_bootstrap_slot", -1)).isZero();
        assertThat(leaderLedger.metaLong("anchor_last_height", -1)).isZero();
        assertThat(leaderLedger.metaLong("anchor_last_slot", -1)).isZero();
        assertThat(leaderLedger.metaString("anchor_last_tx")).isEmpty();

        assertThat(leader.bootstrap()).containsKeys("txHash", "threadPolicyId", "scriptHash");
        assertThat(submitted).hasSize(2);
    }

    @Test
    void bootstrappedRequiresCompletePersistedIdentity() {
        followerLedger.metaPutBytes("anchor_script_policy_id", new byte[28]);
        assertThat(follower.bootstrapped()).isFalse();

        followerLedger.metaPutBytes("anchor_script_hash", new byte[28]);
        assertThat(follower.bootstrapped()).isTrue();
    }

    @Test
    void follower_refusesWrongChainHistory() throws Exception {
        // Bootstrap + confirm as above
        utxoState.put(leader.anchorAddress(), List.of(walletUtxo("cc".repeat(32), 0, 100_000_000)));
        Map<String, Object> boot = leader.bootstrap();
        Transaction bootstrapTx = Transaction.deserialize(submitted.get(0));
        String bootstrapHash = (String) boot.get("txHash");
        String scriptAddress = (String) boot.get("scriptAddress");
        String policyIdHex = (String) boot.get("threadPolicyId");
        leader.onL1Block(100, List.of(bootstrapHash));
        TransactionOutput anchorOut = outputTo(bootstrapTx, scriptAddress);
        int anchorOutIndex = bootstrapTx.getBody().getOutputs().indexOf(anchorOut);
        utxoState.put(scriptAddress, List.of(
                anchorUtxo(bootstrapHash, anchorOutIndex, anchorOut, policyIdHex)));
        TransactionOutput changeOut = outputTo(bootstrapTx, wallet);
        utxoState.put(wallet, List.of(walletUtxo(bootstrapHash,
                bootstrapTx.getBody().getOutputs().indexOf(changeOut),
                changeOut.getValue().getCoin().longValue())));

        // Follower's ledger DISAGREES about block 9 (different state root)
        ScriptAnchorService divergentFollower = new ScriptAnchorService(CHAIN_ID,
                new AppChainConfig.AnchorConfig(false, "", 0, 0, 0), followerLedger,
                cbor -> {
                    throw new IllegalStateException("never");
                },
                () -> utxoState,
                h -> new AppBlock(AppBlock.BLOCK_VERSION, CHAIN_ID, h, new byte[32],
                        0, new byte[0], 1_000_000 + h, new byte[32], fill(32, 0x77),
                        List.of(), new byte[32], FinalityCert.empty()),
                () -> tip[0],
                new AnchorScriptArtifacts(AppChainConfig.AnchorScriptConfig.defaults()),
                followerSigner, () -> members, () -> 2,
                (topic, body) -> deliver(this.leader, followerSigner.publicKey(), topic, body),
                false, 42, log);

        // Re-point the leader's diffuser at the divergent follower
        this.follower = divergentFollower;

        tip[0] = 9;
        leader.tick();

        // Round 1 targets both members but the divergent follower refuses to
        // sign — no advance is submitted (leader-only signature < threshold 2)
        assertThat(submitted).hasSize(1); // bootstrap only
        assertThat(leader.status().toString()).contains("cosignPending");
        assertThat(divergentFollower.bootstrapped()).isFalse();
    }

    @Test
    void bootstrap_failsClosed_whenNoProtocolParams() {
        // No protocol params source (tracked or configured file) → the anchor
        // must NOT silently price against a hardcoded default (wrong cost model
        // = wrong script-integrity hash). It fails closed with a clear message.
        leader.wireTxPricing(() -> null,
                () -> new AppChainEngine.L1Ref(500L, L1_TIP_HASH));
        utxoState.put(leader.anchorAddress(), List.of(walletUtxo("cc".repeat(32), 0, 100_000_000)));

        assertThatThrownBy(leader::bootstrap)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Script-anchor bootstrap failed")
                .hasMessageContaining(IllegalStateException.class.getName())
                .hasMessageNotContaining("requires protocol parameters");
        assertThat(submitted).isEmpty();
    }

    @Test
    void tickContainsAndRedactsRecoverableErrorSoLaterTicksStillRun() {
        String secret = "kms-token-must-not-reach-health-or-logs";
        AssertionError callbackFailure = new AssertionError(secret);
        AtomicInteger tipCalls = new AtomicInteger();
        Logger safeLog = mock(Logger.class);
        AppLedgerStore ledger = new AppLedgerStore(
                tempDir.resolve("tick-error-ledger").toString(), log);
        try {
            ledger.metaPutBytes("anchor_script_policy_id", new byte[28]);
            ledger.metaPutBytes("anchor_script_hash", new byte[28]);
            AppChainConfig.AnchorConfig config = new AppChainConfig.AnchorConfig(
                    true, "aa".repeat(32), 1, 1, 7014,
                    AppChainConfig.AnchorConfig.DEFAULT_VALIDITY_SLOTS,
                    AppChainConfig.AnchorConfig.DEFAULT_FALLBACK_FEE_LOVELACE,
                    AppChainConfig.AnchorConfig.MODE_SCRIPT, null);
            ScriptAnchorService service = new ScriptAnchorService(
                    CHAIN_ID, config, ledger, ignored -> "unused", () -> utxoState,
                    this::blockAt, () -> {
                        tipCalls.incrementAndGet();
                        throw callbackFailure;
                    },
                    new AnchorScriptArtifacts(AppChainConfig.AnchorScriptConfig.defaults()),
                    leaderSigner, () -> members, () -> 2, (topic, body) -> { },
                    true, 42, safeLog);

            assertThat(service.forceAnchorNow()).isFalse();
            assertThat(service.status().get("lastError"))
                    .isEqualTo("Script-anchor force-anchor failed (errorType="
                            + AssertionError.class.getName() + ")");
            service.tick();
            service.tick();

            assertThat(tipCalls).hasValue(3);
            assertThat(service.status().get("lastError"))
                    .isEqualTo("Script-anchor tick failed (errorType="
                            + AssertionError.class.getName() + ")");
            assertThat(service.status().toString()).doesNotContain(secret);
            verify(safeLog, times(2)).warn(
                    "Script-anchor {} failed (errorType={})",
                    "tick", AssertionError.class.getName());
        } finally {
            ledger.close();
        }
    }

    @Test
    void signerAssertionIsRedactedAndDoesNotCancelLaterTicks() throws Exception {
        String secret = "hsm-pin-must-not-reach-health-or-logs";
        AssertionError signerFailure = new AssertionError(secret);
        AtomicInteger signCalls = new AtomicInteger();
        SignerProvider failingSigner = new SignerProvider() {
            @Override
            public byte[] sign(byte[] message) {
                signCalls.incrementAndGet();
                throw signerFailure;
            }

            @Override public byte[] publicKey() { return leaderSigner.publicKey(); }
            @Override public String publicKeyHex() { return leaderSigner.publicKeyHex(); }
        };
        Logger safeLog = mock(Logger.class);
        List<byte[]> localSubmissions = new ArrayList<>();
        AppLedgerStore ledger = new AppLedgerStore(
                tempDir.resolve("signer-error-ledger").toString(), log);
        try {
            AppChainConfig.AnchorConfig config = new AppChainConfig.AnchorConfig(
                    true, "aa".repeat(32), 1, 1, 7014,
                    AppChainConfig.AnchorConfig.DEFAULT_VALIDITY_SLOTS,
                    AppChainConfig.AnchorConfig.DEFAULT_FALLBACK_FEE_LOVELACE,
                    AppChainConfig.AnchorConfig.MODE_SCRIPT, null);
            String signerHex = failingSigner.publicKeyHex()
                    .toLowerCase(java.util.Locale.ROOT);
            ScriptAnchorService service = new ScriptAnchorService(
                    CHAIN_ID, config, ledger,
                    cbor -> {
                        localSubmissions.add(cbor);
                        return txHash(cbor);
                    },
                    () -> utxoState, this::blockAt, () -> tip[0],
                    new AnchorScriptArtifacts(AppChainConfig.AnchorScriptConfig.defaults()),
                    failingSigner, () -> Set.of(signerHex), () -> 1,
                    (topic, body) -> { }, true, 42, safeLog);
            service.wireTxPricing(() -> DEVNET_PARAMS,
                    () -> new AppChainEngine.L1Ref(500L, L1_TIP_HASH));
            String localWallet = service.anchorAddress();
            utxoState.put(localWallet, List.of(
                    new Utxo(new Outpoint("dd".repeat(32), 0), localWallet,
                            BigInteger.valueOf(100_000_000), List.of(), null,
                            null, null, null, false, 0, 0, null)));

            Map<String, Object> boot = service.bootstrap();
            Transaction bootstrapTx = Transaction.deserialize(localSubmissions.getFirst());
            String bootstrapHash = (String) boot.get("txHash");
            String scriptAddress = (String) boot.get("scriptAddress");
            String policyIdHex = (String) boot.get("threadPolicyId");
            service.onL1Block(100, List.of(bootstrapHash));
            TransactionOutput anchorOut = outputTo(bootstrapTx, scriptAddress);
            int anchorIndex = bootstrapTx.getBody().getOutputs().indexOf(anchorOut);
            utxoState.put(scriptAddress, List.of(
                    anchorUtxo(bootstrapHash, anchorIndex, anchorOut, policyIdHex)));
            TransactionOutput changeOut = outputTo(bootstrapTx, localWallet);
            int changeIndex = bootstrapTx.getBody().getOutputs().indexOf(changeOut);
            utxoState.put(localWallet, List.of(
                    new Utxo(new Outpoint(bootstrapHash, changeIndex), localWallet,
                            changeOut.getValue().getCoin(), List.of(), null,
                            null, null, null, false, 0, 0, null)));

            tip[0] = 9;
            service.tick();
            service.tick();

            assertThat(signCalls).hasValue(2);
            assertThat(service.status().get("lastError"))
                    .isEqualTo("Script-anchor co-sign round start failed (errorType="
                            + AssertionError.class.getName() + ")");
            assertThat(service.status().toString()).doesNotContain(secret);
            verify(safeLog, times(2)).warn(
                    "Script-anchor {} failed (errorType={})",
                    "co-sign round start", AssertionError.class.getName());
        } finally {
            ledger.close();
        }
    }

    // ------------------------------------------------------------------

    /** The single output paying the given address (order-independent). */
    private static TransactionOutput outputTo(Transaction tx, String address) {
        return tx.getBody().getOutputs().stream()
                .filter(o -> address.equals(o.getAddress()))
                .findFirst().orElseThrow();
    }

    private Utxo walletUtxo(String txHash, int index, long lovelace) {
        return new Utxo(new Outpoint(txHash, index), wallet, BigInteger.valueOf(lovelace),
                List.of(), null, null, null, null, false, 0, 0, null);
    }

    private Utxo anchorUtxo(String txHash, int index, TransactionOutput anchorOut, String policyIdHex)
            throws Exception {
        return anchorUtxo(txHash, index, anchorOut, policyIdHex, 100);
    }

    private Utxo anchorUtxo(String txHash, int index, TransactionOutput anchorOut,
                            String policyIdHex, long slot) throws Exception {
        return new Utxo(new Outpoint(txHash, index), anchorOut.getAddress(),
                anchorOut.getValue().getCoin(),
                List.of(new AssetAmount(policyIdHex, "", BigInteger.ONE)),
                null, anchorOut.getInlineDatum().serializeToBytes(),
                null, null, false, slot, 0, null);
    }

    /** Address-keyed UTxO view (the shared fake "L1"). */
    private static final class AddressedUtxoState
            implements UtxoState, RollbackCapableStore {
        private final Map<String, List<Utxo>> byAddress = new HashMap<>();
        private long appliedSlot = 500L;
        private String appliedBlockHash = HexUtil.encodeHexString(L1_TIP_HASH);

        void put(String address, List<Utxo> utxos) {
            byAddress.put(address, new ArrayList<>(utxos));
        }

        List<Utxo> get(String address) {
            return byAddress.getOrDefault(address, List.of());
        }

        void setAppliedPoint(long slot, byte[] blockHash) {
            this.appliedSlot = slot;
            this.appliedBlockHash = HexUtil.encodeHexString(blockHash);
        }

        @Override
        public List<Utxo> getUtxosByAddress(String bech32OrHexAddress, int page, int pageSize) {
            // Page-aware: everything on page 1 — further pages are empty, or
            // QuickTx's selection would see infinite duplicate pages
            return page <= 1 ? byAddress.getOrDefault(bech32OrHexAddress, List.of()) : List.of();
        }

        @Override
        public List<Utxo> getUtxosByPaymentCredential(String credentialHexOrAddress, int page, int pageSize) {
            return List.of();
        }

        @Override
        public Optional<Utxo> getUtxo(Outpoint outpoint) {
            // Input resolution for QuickTx balancing + the julc evaluator
            return byAddress.values().stream()
                    .flatMap(List::stream)
                    .filter(u -> u.outpoint().equals(outpoint))
                    .findFirst();
        }

        @Override
        public boolean isEnabled() {
            return true;
        }

        @Override public String storeName() { return "test-utxo"; }
        @Override public long getLatestAppliedSlot() { return appliedSlot; }
        @Override public AppliedPoint getLatestAppliedPoint() {
            return new AppliedPoint(appliedSlot, appliedBlockHash);
        }
        @Override public long getRollbackFloorSlot() { return 0L; }
        @Override public void rollbackToSlot(long targetSlot) { appliedSlot = targetSlot; }
    }
}
