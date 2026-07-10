package com.bloxbean.cardano.yano.runtime.appchain;

import com.bloxbean.cardano.client.transaction.spec.Transaction;
import com.bloxbean.cardano.client.transaction.spec.TransactionOutput;
import com.bloxbean.cardano.yaci.core.protocol.appmsg.model.AppMessage;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import com.bloxbean.cardano.yano.api.appchain.AppBlock;
import com.bloxbean.cardano.yano.api.appchain.AppChainConfig;
import com.bloxbean.cardano.yano.api.appchain.FinalityCert;
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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Full A2 loop without an L1 (ADR 008.4): leader bootstraps the thread NFT,
 * then leader + follower run the {@code ~anchor/sign}/{@code ~anchor/sig}
 * co-signing protocol against a fake UTxO view; the assembled advance tx is
 * checked structurally (datum, required signers, member witnesses).
 */
class ScriptAnchorServiceTest {

    private static final Logger log = LoggerFactory.getLogger(ScriptAnchorServiceTest.class);
    private static final String CHAIN_ID = "test-chain";

    @TempDir
    Path tempDir;

    private AppLedgerStore leaderLedger;
    private AppLedgerStore followerLedger;
    private final List<byte[]> submitted = new ArrayList<>();
    private final long[] tip = {5};
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
        leader.wireFees(() -> null, () -> 500L);

        follower = new ScriptAnchorService(CHAIN_ID, followerConfig, followerLedger,
                cbor -> {
                    throw new IllegalStateException("Follower must never submit");
                },
                () -> utxoState, this::blockAt, () -> tip[0],
                new AnchorScriptArtifacts(AppChainConfig.AnchorScriptConfig.defaults()),
                followerSigner, () -> members, () -> 2,
                (topic, body) -> deliver(this.leader, followerSigner.publicKey(), topic, body),
                false, 42, log);
        follower.wireFees(() -> null, () -> 500L);
    }

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

        // Structure: output 0 locks the NFT + inline datum at the script
        TransactionOutput anchorOut = bootstrapTx.getBody().getOutputs().get(0);
        assertThat(anchorOut.getAddress()).isEqualTo(scriptAddress);
        assertThat(anchorOut.getInlineDatum()).isNotNull();
        AnchorDatumCodec.AnchorDatum datum0 = AnchorDatumCodec.decode(anchorOut.getInlineDatum());
        assertThat(datum0.chainId()).isEqualTo(CHAIN_ID);
        assertThat(datum0.height()).isEqualTo(5);
        assertThat(datum0.threshold()).isEqualTo(2);
        assertThat(datum0.memberKeys()).hasSize(2);
        assertThat(bootstrapTx.getBody().getMint().get(0).getPolicyId()).isEqualTo(policyIdHex);
        assertThat(bootstrapTx.getWitnessSet().getPlutusV3Scripts()).hasSize(1);

        // Idempotence while pending: no double-submit
        assertThat(leader.bootstrap()).containsKey("pendingTx");
        assertThat(submitted).hasSize(1);

        // --- L1 confirms bootstrap: identity persists; anchor UTxO appears
        leader.onL1Block(100, List.of(bootstrapHash));
        assertThat(leader.bootstrapped()).isTrue();
        assertThat(leader.anchorAddress()).isEqualTo(scriptAddress);

        utxoState.put(scriptAddress, List.of(anchorUtxo(bootstrapHash, 0, anchorOut, policyIdHex)));
        // Wallet change becomes the fee input for the advance
        long change = bootstrapTx.getBody().getOutputs().get(1).getValue().getCoin().longValue();
        utxoState.put(walletAddressOf(bootstrapTx), List.of(walletUtxo(bootstrapHash, 1, change)));

        // --- Advance: chain has moved; leader starts a co-sign round.
        // The follower (no anchor config at all) adopts the identity from the
        // verified sign request, checks the datum against ITS OWN ledger, and
        // returns a witness; the leader assembles + submits.
        tip[0] = 9;
        leader.tick();

        assertThat(submitted).hasSize(2);
        Transaction advanceTx = Transaction.deserialize(submitted.get(1));

        // Datum advanced to the follower-verified tip
        TransactionOutput nextOut = advanceTx.getBody().getOutputs().get(0);
        assertThat(nextOut.getAddress()).isEqualTo(scriptAddress);
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

        // Follower adopted the identity from the sign request
        assertThat(follower.bootstrapped()).isTrue();

        // --- L1 confirms the advance
        String advanceHash = txHash(submitted.get(1));
        AnchorService.ConfirmedAnchor confirmed = leader.onL1Block(200, List.of(advanceHash));
        assertThat(confirmed).isNotNull();
        assertThat(confirmed.toHeight()).isEqualTo(9);
        assertThat(leader.lastAnchoredHeight()).isEqualTo(9);
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
        TransactionOutput anchorOut = bootstrapTx.getBody().getOutputs().get(0);
        utxoState.put(scriptAddress, List.of(anchorUtxo(bootstrapHash, 0, anchorOut, policyIdHex)));
        long change = bootstrapTx.getBody().getOutputs().get(1).getValue().getCoin().longValue();
        utxoState.put(walletAddressOf(bootstrapTx), List.of(walletUtxo(bootstrapHash, 1, change)));

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
    }

    // ------------------------------------------------------------------

    private String walletAddressOf(Transaction bootstrapTx) throws Exception {
        // Change output address == leader wallet address
        return bootstrapTx.getBody().getOutputs().get(1).getAddress();
    }

    private Utxo walletUtxo(String txHash, int index, long lovelace) {
        return new Utxo(new Outpoint(txHash, index), "wallet", BigInteger.valueOf(lovelace),
                List.of(), null, null, null, null, false, 0, 0, null);
    }

    private Utxo anchorUtxo(String txHash, int index, TransactionOutput anchorOut, String policyIdHex)
            throws Exception {
        return new Utxo(new Outpoint(txHash, index), anchorOut.getAddress(),
                anchorOut.getValue().getCoin(),
                List.of(new AssetAmount(policyIdHex, "", BigInteger.ONE)),
                null, anchorOut.getInlineDatum().serializeToBytes(),
                null, null, false, 0, 0, null);
    }

    /** Address-keyed UTxO view (the shared fake "L1"). */
    private static final class AddressedUtxoState implements UtxoState {
        private final Map<String, List<Utxo>> byAddress = new HashMap<>();

        void put(String address, List<Utxo> utxos) {
            byAddress.put(address, new ArrayList<>(utxos));
        }

        List<Utxo> get(String address) {
            return byAddress.getOrDefault(address, List.of());
        }

        @Override
        public List<Utxo> getUtxosByAddress(String bech32OrHexAddress, int page, int pageSize) {
            return byAddress.getOrDefault(bech32OrHexAddress, List.of());
        }

        @Override
        public List<Utxo> getUtxosByPaymentCredential(String credentialHexOrAddress, int page, int pageSize) {
            return List.of();
        }

        @Override
        public Optional<Utxo> getUtxo(Outpoint outpoint) {
            return Optional.empty();
        }

        @Override
        public boolean isEnabled() {
            return true;
        }
    }
}
