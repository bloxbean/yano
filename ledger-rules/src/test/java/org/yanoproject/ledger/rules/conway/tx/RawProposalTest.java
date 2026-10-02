package org.yanoproject.ledger.rules.conway.tx;

import com.bloxbean.cardano.client.util.HexUtil;

import org.junit.jupiter.api.Test;
import org.yanoproject.ledger.rules.conway.gov.GovChecks;
import org.yanoproject.ledger.rules.conway.ruleset.ConwayRuleSets;
import org.yanoproject.ledger.rules.conway.ruleset.ConwayScopes;

import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import java.util.SortedSet;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The Yano-owned decoders for proposal procedures and Conway parameter updates ({@link RawProposal},
 * {@link RawParamUpdate}): Haskell's {@code DecCBOR} at decoder versions 9–11 (every refusal is a
 * {@link TxDecodingException}, {@code ENGINE.DecodingFailure}), {@code ppuWellFormed} and the security group.
 */
class RawProposalTest {

    private static final String KEY_HASH = "93c191b1094746961f6f00fba27f3d8eff6a66490baf806d4e179fd8";
    private static final String ACCOUNT = "581d" + "e0" + KEY_HASH;
    private static final String MAINNET_ACCOUNT = "581d" + "e1" + KEY_HASH;
    private static final String ANCHOR = "82" + "60" + "5820" + "00".repeat(32);
    private static final String DEPOSIT = "1a05f5e100";

    private static RawParamUpdate update(String hex) {
        return RawParamUpdate.read(StrictCbor.span(HexUtil.decodeHexString(hex)));
    }

    private static RawProposal proposal(String actionHex) {
        return RawProposal.read(StrictCbor.span(HexUtil.decodeHexString("84" + DEPOSIT + ACCOUNT + actionHex + ANCHOR)),
                0);
    }

    // ------------------------------------------------------------------------------------------------ PParamsUpdate

    @Test
    void decodesEveryConwayParameterType() {
        // One entry per Conway parameter (keys 0-11 and 16-33), each value of its type.
        Map<Integer, String> values = new TreeMap<>();
        values.put(0, "182c");
        values.put(1, "1a00025ef5");
        values.put(2, "1a00016000");
        values.put(3, "194000");
        values.put(4, "190440");
        values.put(5, "1a001e8480");
        values.put(6, "1a1dcd6500");
        values.put(7, "12");
        values.put(8, "18fa");
        values.put(9, "d81e820301"); // NonNegativeInterval 3/1
        values.put(10, "d81e820114"); // UnitInterval 1/20
        values.put(11, "d81e820105"); // UnitInterval 1/5
        values.put(16, "1a1443fd00");
        values.put(17, "191106");
        values.put(18, "a1008201" + "20"); // {PlutusV1: [1, -1]}
        values.put(19, "82" + "d81e82190241192710" + "d81e82021903e8"); // prices
        values.put(20, "82" + "1a00d59f80" + "1b00000002540be400"); // max tx ex units
        values.put(21, "82" + "1a03b20b80" + "1b00000004a817c800"); // max block ex units
        values.put(22, "191388");
        values.put(23, "1896");
        values.put(24, "03");
        values.put(25, "85" + "d81e820102".repeat(5));
        values.put(26, "8a" + "d81e820102".repeat(10));
        values.put(27, "07");
        values.put(28, "1892");
        values.put(29, "06");
        values.put(30, "1b000000174876e800");
        values.put(31, "1a1dcd6500");
        values.put(32, "14");
        values.put(33, "d81e820f01");
        StringBuilder hex = new StringBuilder("b8").append(String.format("%02x", values.size()));
        values.forEach((key, value) -> hex.append(key < 24 ? String.format("%02x", key) : "18" + String.format("%02x",
                key)).append(value));
        RawParamUpdate update = update(hex.toString());
        assertThat(update.keys()).hasSize(30);
        assertThat(update.integer(30)).contains(new BigInteger("100000000000"));
        assertThat(malformedKeys(update, 10)).isEmpty();
        assertThat(malformedKeys(update, 11)).isEmpty();
        assertThat(update.anyInSecurityGroup()).isTrue();
    }

    @Test
    void refusesKeysThatAreNotConwayParameters() {
        // 12 d, 13 extraEntropy, 15 minUTxOValue are not Conway parameters; 14 (protocolVersion) is not updatable.
        for (String key : List.of("0c", "0d", "0e", "0f", "1822", "1903e8")) {
            assertThatThrownBy(() -> update("a1" + key + "00")).as(key).isInstanceOf(TxDecodingException.class);
        }
    }

    @Test
    void refusesDuplicateKeysAndOutOfRangeValues() {
        assertThatThrownBy(() -> update("a2" + "0301" + "0302")).isInstanceOf(TxDecodingException.class)
                .hasMessageContaining("duplicate");
        assertThatThrownBy(() -> update("a1" + "04" + "1a00010000")).isInstanceOf(TxDecodingException.class); // Word16
        assertThatThrownBy(() -> update("a1" + "02" + "1b0000000100000000")).isInstanceOf(TxDecodingException.class);
        assertThatThrownBy(() -> update("a1" + "0a" + "d81e820302")).isInstanceOf(TxDecodingException.class); // > 1
        assertThat(update("a1" + "09" + "d81e820302").keys()).containsExactly(9); // a0 may exceed 1
        assertThatThrownBy(() -> update("a1" + "09" + "820302")).isInstanceOf(TxDecodingException.class); // no tag 30
        assertThatThrownBy(() -> update("a1" + "09" + "d81e820300")).isInstanceOf(TxDecodingException.class); // 3/0
        assertThatThrownBy(() -> update("a1" + "12" + "a2" + "0080" + "0080")).isInstanceOf(TxDecodingException.class);
        assertThatThrownBy(() -> update("a1" + "12" + "a1" + "190100" + "80")).isInstanceOf(TxDecodingException.class);
        assertThat(update("a1" + "12" + "a1" + "07" + "80").keys()).containsExactly(18); // unknown language kept
        assertThatThrownBy(() -> update("a1" + "14" + "82" + "1b8000000000000000" + "00"))
                .isInstanceOf(TxDecodingException.class); // ExUnits above Int64
        assertThatThrownBy(() -> update("a1" + "1819" + "84" + "d81e820102".repeat(4)))
                .isInstanceOf(TxDecodingException.class); // four pool thresholds
        assertThat(update("bf" + "0301" + "ff").keys()).containsExactly(3); // indefinite map
        // Rational parts may be bignums (cborg's decodeInteger: one-byte tag heads c2/c3, a definite byte string).
        assertThat(update("a1" + "0a" + "d81e82" + "c24101" + "c24102").keys()).containsExactly(10); // 1/2
        assertThatThrownBy(() -> update("a1" + "0a" + "d81e82" + "d8024101" + "02"))
                .isInstanceOf(TxDecodingException.class); // a two-byte tag-2 head is a plain tag
        assertThatThrownBy(() -> update("a1" + "09" + "d81e82" + "c34100" + "01"))
                .isInstanceOf(TxDecodingException.class); // -1: not non-negative

    }

    @Test
    void wellFormednessFollowsTheProtocolVersion() {
        for (int key : new int[]{2, 3, 4, 22, 23, 28, 29, 6, 30, 31, 17}) {
            String encoded = key < 24 ? String.format("%02x", key) : "18" + String.format("%02x", key);
            assertThat(malformedKeys(update("a1" + encoded + "00"), 10)).as("key " + key).containsExactly(key);
        }
        // coinsPerUTxOByte = 0 is allowed during the bootstrap phase only (protocol version 9).
        assertThat(malformedKeys(update("a1" + "11" + "00"), 9)).isEmpty();
        // nOpt = 0 is malformed from protocol version 11.
        assertThat(malformedKeys(update("a1" + "08" + "00"), 10)).isEmpty();
        assertThat(malformedKeys(update("a1" + "08" + "00"), 11)).containsExactly(8);
        // Other zeros are fine (key deposit, minPoolCost, the DRep activity).
        assertThat(malformedKeys(update("a3" + "0500" + "1000" + "182000"), 11)).isEmpty();
        // The empty update.
        assertThat(malformedKeys(update("a0"), 10)).containsExactly(-1);
    }

    @Test
    void theSecurityGroup() {
        assertThat(update("a1" + "17" + "1896").anyInSecurityGroup()).isFalse();
        assertThat(update("a1" + "181e" + "01").anyInSecurityGroup()).isTrue();
        assertThat(update("a1" + "1821" + "d81e820f01").anyInSecurityGroup()).isTrue();
        assertThat(update("a2" + "17" + "1896" + "1821" + "d81e820f01").anyInSecurityGroup()).isTrue();
    }

    // ------------------------------------------------------------------------------------------------ proposals

    @Test
    void readsEveryFieldTheRulesCheck() {
        RawProposal info = proposal("8106");
        assertThat(info.deposit()).isEqualTo(BigInteger.valueOf(100_000_000));
        assertThat(info.returnAccountNetwork()).isZero();
        assertThat(info.actionTypeName()).isEqualTo("InfoAction");
        assertThat(proposal("8106").prevActionId()).isNull();

        RawProposal hardFork = proposal("83" + "01" + "82" + "5820" + "11".repeat(32) + "03" + "82" + "0c" + "00");
        assertThat(hardFork.prevActionId().toString()).isEqualTo("11".repeat(32) + "#3");
        assertThat(hardFork.protocolVersion()).isEqualTo(new RawProposal.ProtVer(12, 0));

        RawProposal withdrawals = proposal("83" + "02" + "a2" + MAINNET_ACCOUNT + "01" + ACCOUNT + "02" + "f6");
        // Map AccountAddress order: the testnet account first.
        assertThat(withdrawals.withdrawals()).extracting(RawProposal.Withdrawal::network).containsExactly(0, 1);
        assertThat(withdrawals.withdrawals()).extracting(RawProposal.Withdrawal::amount)
                .containsExactly(BigInteger.TWO, BigInteger.ONE);

        String credential = "8200581c" + KEY_HASH;
        RawProposal committee = proposal("85" + "04" + "f6" + "d90102" + "81" + credential + "a1" + "8201581c"
                + "22".repeat(28) + "1b" + "ffffffffffffffff" + "d81e820102");
        assertThat(committee.committeeRemovals()).hasSize(1);
        assertThat(committee.committeeAdditions().values()).containsExactly(new BigInteger("18446744073709551615"));

        RawProposal parameterChange = proposal("84" + "00" + "f6" + "a1" + "0301" + "581c" + "33".repeat(28));
        assertThat(parameterChange.paramUpdate().keys()).containsExactly(3);
        assertThat(parameterChange.policyHash()).hasSize(28);
    }

    @Test
    void refusesWhatHaskellDoesNotDecode() {
        // A protocol major version above succVersion (ProtVerHigh ConwayEra) = 12, a minor beyond Word32.
        assertThatThrownBy(() -> proposal("83" + "01" + "f6" + "82" + "0d" + "00"))
                .isInstanceOf(TxDecodingException.class).hasMessageContaining("exceeds the maximum");
        assertThatThrownBy(() -> proposal("83" + "01" + "f6" + "82" + "0b" + "1b0000000100000000"))
                .isInstanceOf(TxDecodingException.class);
        // Duplicate treasury withdrawal accounts, duplicate committee members.
        assertThatThrownBy(() -> proposal("83" + "02" + "a2" + ACCOUNT + "01" + ACCOUNT + "02" + "f6"))
                .isInstanceOf(TxDecodingException.class);
        String credential = "8200581c" + KEY_HASH;
        assertThatThrownBy(() -> proposal("85" + "04" + "f6" + "82" + credential + credential + "a0" + "d81e820102"))
                .isInstanceOf(TxDecodingException.class);
        assertThatThrownBy(() -> proposal("85" + "04" + "f6" + "80" + "a2" + credential + "01" + credential + "02"
                + "d81e820102")).isInstanceOf(TxDecodingException.class);
        // A quorum above 1, an index beyond Word16, a return address that is not an account address.
        assertThatThrownBy(() -> proposal("85" + "04" + "f6" + "80" + "a0" + "d81e820302"))
                .isInstanceOf(TxDecodingException.class);
        assertThatThrownBy(() -> proposal("82" + "03" + "82" + "5820" + "11".repeat(32) + "1a00010000"))
                .isInstanceOf(TxDecodingException.class);
        assertThatThrownBy(() -> RawProposal.read(StrictCbor.span(HexUtil.decodeHexString("84" + DEPOSIT + "581d01"
                + KEY_HASH + "8106" + ANCHOR)), 0)).isInstanceOf(TxDecodingException.class);
        // Indefinite-length byte and text strings: definite only below decoder version 12 (decodeBytesDefinite,
        // decodeByteArrayDefinite, cborg's decodeString).
        assertThatThrownBy(() -> RawProposal.read(StrictCbor.span(HexUtil.decodeHexString("84" + DEPOSIT + "5f" + "41e0"
                + "581c" + KEY_HASH + "ff" + "8106" + ANCHOR)), 0)).isInstanceOf(TxDecodingException.class)
                .hasMessageContaining("indefinite-length byte string");
        assertThatThrownBy(() -> proposal("82" + "03" + "82" + "5f" + "5820" + "11".repeat(32) + "ff" + "00"))
                .isInstanceOf(TxDecodingException.class);
        assertThatThrownBy(() -> RawProposal.read(StrictCbor.span(HexUtil.decodeHexString("84" + DEPOSIT + ACCOUNT
                + "8106" + "82" + "7f" + "60" + "ff" + "5820" + "00".repeat(32))), 0)).isInstanceOf(TxDecodingException.class)
                .hasMessageContaining("indefinite-length text string");
        // A guardrails policy hash that is not 28 bytes.
        assertThatThrownBy(() -> proposal("84" + "00" + "f6" + "a1" + "0301" + "5820" + "33".repeat(32)))
                .isInstanceOf(TxDecodingException.class);
    }

    @Test
    void hardForkVersionsFollowPvCanFollow() {
        RawProposal.ProtVer ten = new RawProposal.ProtVer(10, 0);
        assertThat(new RawProposal.ProtVer(11, 0).canFollow(ten)).isTrue();
        assertThat(new RawProposal.ProtVer(10, 1).canFollow(ten)).isTrue();
        assertThat(new RawProposal.ProtVer(11, 1).canFollow(ten)).isFalse();
        assertThat(new RawProposal.ProtVer(10, 0).canFollow(ten)).isFalse();
        assertThat(new RawProposal.ProtVer(12, 0).canFollow(ten)).isFalse();
        // Word32 minor arithmetic wraps.
        assertThat(new RawProposal.ProtVer(10, 0).canFollow(new RawProposal.ProtVer(10, 0xFFFF_FFFFL))).isTrue();
    }

    /** {@code ppuWellFormed pv} as the protocol version's rule set checks it ({@code GOV.MalformedProposal}). */
    private static SortedSet<Integer> malformedKeys(RawParamUpdate update, int protocolMajor) {
        GovChecks.MalformedProposal check = (GovChecks.MalformedProposal) ConwayRuleSets.forProtocol(protocolMajor)
                .orElseThrow().unit(ConwayScopes.GOV_PROPOSAL, "GOV.MalformedProposal").orElseThrow();
        return update.malformedKeys(check.nonZeroKeys());
    }
}
