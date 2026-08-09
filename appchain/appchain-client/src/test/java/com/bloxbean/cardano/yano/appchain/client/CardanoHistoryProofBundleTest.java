package com.bloxbean.cardano.yano.appchain.client;

import com.bloxbean.cardano.vds.core.api.NodeStore;
import com.bloxbean.cardano.vds.jmt.JellyfishMerkleTree;
import com.bloxbean.cardano.vds.jmt.JmtProfile;
import com.bloxbean.cardano.vds.jmt.store.InMemoryJmtStore;
import com.bloxbean.cardano.vds.mpf.MpfTrie;
import com.bloxbean.cardano.yano.api.appchain.anchor.AnchorDatumV1;
import com.bloxbean.cardano.yano.api.appchain.state.StateProofSubject;
import com.bloxbean.cardano.yano.appchain.stdlib.contracts.EpochGovernanceContract;
import com.bloxbean.cardano.yano.appchain.stdlib.contracts.EpochStakeContract;
import com.fasterxml.jackson.dataformat.cbor.CBORFactory;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CardanoHistoryProofBundleTest {
    private static final long EPOCH = 500;
    private static final long HEIGHT = 42;
    private static final String CHAIN = "cardano-history";
    private static final String APPLICATION = "cardano-history-v1";
    private static final String PARAMS = "epoch-params";
    private static final String STAKE = "epoch-stake";
    private static final String GOVERNANCE = "epoch-governance";
    private static final byte[] GENESIS = filled(0x11, 32);

    @Test
    void verifiesEveryTypedHistoryClaimAgainstOneCardanoAnchorRoot() throws Exception {
        Fixture f = fixture();
        var identity = new CardanoHistoryProofBundle.AnchorIdentity(CHAIN, GENESIS, APPLICATION);

        var params = new CardanoHistoryProofBundle.ProtocolParameters(EPOCH, PARAMS,
                f.present(f.paramsSubject, f.paramsValue));
        assertThat(params.verify(f.anchor, identity)).isTrue();

        var stake = new CardanoHistoryProofBundle.Stake(EPOCH, STAKE, 0, f.credential,
                CardanoHistoryProofBundle.StakeMode.MINIMUM_AND_POOL,
                BigInteger.valueOf(1_000_000), f.pool,
                f.present(f.stakeSubject, f.stakeValue),
                f.present(f.stakeCompleteSubject, f.stakeCompleteValue));
        assertThat(stake.verify(f.anchor, identity)).isTrue();
        assertThat(new CardanoHistoryProofBundle.Stake(EPOCH, STAKE, 0, f.credential,
                CardanoHistoryProofBundle.StakeMode.POOL, BigInteger.ZERO, filled(9, 28),
                f.present(f.stakeSubject, f.stakeValue),
                f.present(f.stakeCompleteSubject, f.stakeCompleteValue))
                .verify(f.anchor, identity)).isFalse();

        var proposal = new CardanoHistoryProofBundle.Proposal(EPOCH, GOVERNANCE, f.txId, 2,
                EpochGovernanceContract.ActionType.PARAMETER_CHANGE,
                EpochGovernanceContract.ProposalStatus.RATIFIED,
                EpochGovernanceContract.ProposalReason.RATIFIED,
                f.present(f.proposalSubject, f.proposalValue),
                f.present(f.proposalCompleteSubject, f.proposalCompleteValue));
        assertThat(proposal.verify(f.anchor, identity)).isTrue();

        var drep = new CardanoHistoryProofBundle.DRepAmount(EPOCH, GOVERNANCE, 0, f.drepHash,
                CardanoHistoryProofBundle.AmountMode.EXACT, BigInteger.valueOf(9_000_000),
                f.present(f.drepSubject, f.drepValue),
                f.present(f.drepCompleteSubject, f.drepCompleteValue));
        assertThat(drep.verify(f.anchor, identity)).isTrue();
    }

    @Test
    void absenceRequiresARealNonMembershipProofAndSameRootCompleteness() throws Exception {
        Fixture f = fixture();
        var identity = new CardanoHistoryProofBundle.AnchorIdentity(CHAIN, GENESIS, APPLICATION);
        byte[] absentCredential = filled(0x7f, 28);
        var absentSubject = ProofSubjects.epochStake(STAKE, EPOCH, 0, absentCredential);
        var absent = new CardanoHistoryProofBundle.Stake(EPOCH, STAKE, 0, absentCredential,
                CardanoHistoryProofBundle.StakeMode.ABSENT, BigInteger.ZERO, new byte[0],
                f.absent(absentSubject), f.present(f.stakeCompleteSubject, f.stakeCompleteValue));
        assertThat(absent.verify(f.anchor, identity)).isTrue();

        AnchorDatumV1 wrongRoot = anchor(filled(8, 32));
        assertThat(absent.verify(wrongRoot, identity)).isFalse();
        assertThatThrownBy(() -> identity.trustedRoot(new AnchorDatumV1(
                "another-chain", GENESIS, APPLICATION, ProofVerifier.MPF_BLAKE2B256_V1,
                f.anchor.formatFingerprint(), HEIGHT, new byte[32], f.root,
                List.of(filled(3, 32)), 1))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void classicJmtVerifiesTheSameTypedStakeSemanticsOffChain() {
        byte[] credential = filled(0x21, 28);
        byte[] pool = filled(0x22, 28);
        var factSubject = ProofSubjects.epochStake(STAKE, EPOCH, 0, credential);
        var completeSubject = ProofSubjects.epochStakeCompleteness(STAKE, EPOCH);
        byte[] factValue = EpochStakeContract.encodeValue(new EpochStakeContract.Entry(
                0, credential, BigInteger.valueOf(7_000_000), pool));
        byte[] completeValue = EpochStakeContract.encodeMeta(new EpochStakeContract.Meta(
                new EpochStakeContract.Manifest(EPOCH, 1, 1, 1, filled(0x23, 32)),
                1, true));
        try (InMemoryJmtStore store = new InMemoryJmtStore()) {
            JellyfishMerkleTree tree = new JellyfishMerkleTree(
                    store, JmtProfile.classicBlake2b256V1());
            Map<byte[], byte[]> updates = new LinkedHashMap<>();
            updates.put(factSubject.canonicalKey(), factValue);
            updates.put(completeSubject.canonicalKey(), completeValue);
            byte[] root = tree.put(HEIGHT, updates).rootHash();
            AnchorDatumV1 anchor = anchor(
                    ProofVerifier.JMT_BLAKE2B256_V1, root);
            var identity = new CardanoHistoryProofBundle.AnchorIdentity(
                    CHAIN, GENESIS, APPLICATION);
            var claim = new CardanoHistoryProofBundle.Stake(
                    EPOCH, STAKE, 0, credential,
                    CardanoHistoryProofBundle.StakeMode.MINIMUM_AND_POOL,
                    BigInteger.valueOf(6_000_000), pool,
                    jmtTyped(tree, root, factSubject, factValue,
                            AppChainClient.ProofPresence.PRESENT),
                    jmtTyped(tree, root, completeSubject, completeValue,
                            AppChainClient.ProofPresence.PRESENT));
            assertThat(claim.verify(anchor, identity)).isTrue();

            byte[] missing = filled(0x24, 28);
            var missingSubject = ProofSubjects.epochStake(STAKE, EPOCH, 0, missing);
            var absence = new CardanoHistoryProofBundle.Stake(
                    EPOCH, STAKE, 0, missing, CardanoHistoryProofBundle.StakeMode.ABSENT,
                    BigInteger.ZERO, new byte[0],
                    jmtTyped(tree, root, missingSubject, null,
                            AppChainClient.ProofPresence.ABSENT),
                    jmtTyped(tree, root, completeSubject, completeValue,
                            AppChainClient.ProofPresence.PRESENT));
            assertThat(absence.verify(anchor, identity)).isTrue();
        }
    }

    private static Fixture fixture() throws Exception {
        MpfTrie trie = new MpfTrie(new MapNodeStore());
        byte[] credential = filled(3, 28); byte[] pool = filled(4, 28);
        byte[] txId = filled(5, 32); byte[] drepHash = filled(6, 28);
        var paramsSubject = ProofSubjects.epochProtocolParameters(PARAMS, EPOCH);
        var stakeSubject = ProofSubjects.epochStake(STAKE, EPOCH, 0, credential);
        var stakeCompleteSubject = ProofSubjects.epochStakeCompleteness(STAKE, EPOCH);
        var proposalSubject = ProofSubjects.governanceProposal(GOVERNANCE, EPOCH, txId, 2);
        var proposalCompleteSubject = ProofSubjects.governanceProposalCompleteness(GOVERNANCE, EPOCH);
        var drepSubject = ProofSubjects.drepDistribution(GOVERNANCE, EPOCH, 0, drepHash);
        var drepCompleteSubject = ProofSubjects.drepDistributionCompleteness(GOVERNANCE, EPOCH);

        byte[] paramsValue = canonicalParams();
        byte[] stakeValue = EpochStakeContract.encodeValue(new EpochStakeContract.Entry(
                0, credential, BigInteger.valueOf(1_234_567), pool));
        byte[] stakeCompleteValue = EpochStakeContract.encodeMeta(new EpochStakeContract.Meta(
                new EpochStakeContract.Manifest(EPOCH, 1, 1, 1, filled(7, 32)), 1, true));
        var proposalClaim = new EpochGovernanceContract.Proposal(EPOCH, txId, 2,
                EpochGovernanceContract.ActionType.PARAMETER_CHANGE,
                EpochGovernanceContract.ProposalStatus.RATIFIED,
                EpochGovernanceContract.ProposalReason.RATIFIED, EPOCH - 1, EPOCH + 4);
        byte[] proposalValue = EpochGovernanceContract.encodeProposalValue(proposalClaim);
        byte[] proposalCompleteValue = EpochGovernanceContract.encodeProposalMeta(
                new EpochGovernanceContract.ProposalMeta(EPOCH, 1, filled(8, 32), 1, true));
        byte[] drepValue = EpochGovernanceContract.encodeCoin(BigInteger.valueOf(9_000_000));
        byte[] drepCompleteValue = EpochGovernanceContract.encodeDRepMeta(
                new EpochGovernanceContract.DRepMeta(EPOCH, 1, 1, 1, filled(9, 32), 1, true));

        put(trie, paramsSubject, paramsValue); put(trie, stakeSubject, stakeValue);
        put(trie, stakeCompleteSubject, stakeCompleteValue); put(trie, proposalSubject, proposalValue);
        put(trie, proposalCompleteSubject, proposalCompleteValue); put(trie, drepSubject, drepValue);
        put(trie, drepCompleteSubject, drepCompleteValue);
        byte[] root = trie.getRootHash();
        return new Fixture(trie, root, anchor(root), credential, pool, txId, drepHash,
                paramsSubject, stakeSubject, stakeCompleteSubject, proposalSubject,
                proposalCompleteSubject, drepSubject, drepCompleteSubject,
                paramsValue, stakeValue, stakeCompleteValue, proposalValue,
                proposalCompleteValue, drepValue, drepCompleteValue);
    }

    private static byte[] canonicalParams() throws Exception {
        List<Object> fields = new ArrayList<>(java.util.Collections.nCopies(56, null));
        fields.set(0, 1); fields.set(1, EPOCH);
        return new ObjectMapper(new CBORFactory()).writeValueAsBytes(fields);
    }
    private static void put(MpfTrie trie, StateProofSubject<?> subject, byte[] value) {
        trie.put(subject.canonicalKey(), value);
    }
    private static AnchorDatumV1 anchor(byte[] root) {
        return anchor(ProofVerifier.MPF_BLAKE2B256_V1, root);
    }
    private static AnchorDatumV1 anchor(String profileId, byte[] root) {
        var profile = ProofVerifier.profileMetadata(profileId).orElseThrow();
        return new AnchorDatumV1(CHAIN, GENESIS, APPLICATION, profileId,
                Hex.decode(profile.formatFingerprintHex()), HEIGHT, new byte[32], root,
                List.of(filled(3, 32)), 1);
    }

    private static <T> AppChainClient.TypedProof<T> jmtTyped(
            JellyfishMerkleTree tree, byte[] root, StateProofSubject<T> subject,
            byte[] value, AppChainClient.ProofPresence presence) {
        var metadata = ProofVerifier.profileMetadata(
                ProofVerifier.JMT_BLAKE2B256_V1).orElseThrow();
        byte[] key = subject.canonicalKey();
        AppChainClient.Proof proof = new AppChainClient.Proof(
                Hex.encode(key), CHAIN, Hex.encode(root),
                Hex.encode(tree.getProofWire(key, HEIGHT).orElseThrow()),
                value == null ? null : Hex.encode(value), HEIGHT, HEIGHT, 1,
                ProofVerifier.JMT_BLAKE2B256_V1, metadata.backend(),
                metadata.commitmentFormatId(), metadata.formatFingerprintHex(),
                Hex.encode(GENESIS), metadata.proofEncodingId(), metadata.nativeVersioning(),
                metadata.physicalDelete(), HEIGHT, presence, null, null);
        return new AppChainClient.TypedProof<>(subject.subjectType(), proof,
                value == null ? null : subject.decodePresentValue(value));
    }
    private static byte[] filled(int value, int size) { byte[] b = new byte[size];
        Arrays.fill(b, (byte) value); return b; }

    private record Fixture(MpfTrie trie, byte[] root, AnchorDatumV1 anchor,
                           byte[] credential, byte[] pool, byte[] txId, byte[] drepHash,
                           StateProofSubject<byte[]> paramsSubject,
                           StateProofSubject<EpochStakeContract.Value> stakeSubject,
                           StateProofSubject<EpochStakeContract.Meta> stakeCompleteSubject,
                           StateProofSubject<EpochGovernanceContract.ProposalValue> proposalSubject,
                           StateProofSubject<EpochGovernanceContract.ProposalMeta> proposalCompleteSubject,
                           StateProofSubject<BigInteger> drepSubject,
                           StateProofSubject<EpochGovernanceContract.DRepMeta> drepCompleteSubject,
                           byte[] paramsValue, byte[] stakeValue, byte[] stakeCompleteValue,
                           byte[] proposalValue, byte[] proposalCompleteValue,
                           byte[] drepValue, byte[] drepCompleteValue) {
        private <T> AppChainClient.TypedProof<T> present(StateProofSubject<T> subject, byte[] value) {
            return typed(subject, value, AppChainClient.ProofPresence.PRESENT);
        }
        private <T> AppChainClient.TypedProof<T> absent(StateProofSubject<T> subject) {
            return typed(subject, null, AppChainClient.ProofPresence.ABSENT);
        }
        private <T> AppChainClient.TypedProof<T> typed(StateProofSubject<T> subject, byte[] value,
                                                       AppChainClient.ProofPresence presence) {
            var metadata = ProofVerifier.profileMetadata(ProofVerifier.MPF_BLAKE2B256_V1).orElseThrow();
            byte[] key = subject.canonicalKey();
            AppChainClient.Proof proof = new AppChainClient.Proof(Hex.encode(key), CHAIN, Hex.encode(root),
                    Hex.encode(trie.getProofWire(key).orElseThrow()), value == null ? null : Hex.encode(value),
                    HEIGHT, HEIGHT, 1, ProofVerifier.MPF_BLAKE2B256_V1, metadata.backend(),
                    metadata.commitmentFormatId(), metadata.formatFingerprintHex(), Hex.encode(GENESIS),
                    metadata.proofEncodingId(), metadata.nativeVersioning(), metadata.physicalDelete(),
                    HEIGHT, presence, null, null);
            return new AppChainClient.TypedProof<>(subject.subjectType(), proof,
                    value == null ? null : subject.decodePresentValue(value));
        }
    }

    private static final class MapNodeStore implements NodeStore {
        private final Map<String, byte[]> nodes = new HashMap<>();
        @Override public byte[] get(byte[] hash) { return nodes.get(HexFormat.of().formatHex(hash)); }
        @Override public void put(byte[] hash, byte[] bytes) { nodes.put(HexFormat.of().formatHex(hash), bytes); }
        @Override public void delete(byte[] hash) { nodes.remove(HexFormat.of().formatHex(hash)); }
    }
}
